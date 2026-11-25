package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.logger.AppLogger
import com.example.data.model.BasketActionButton
import com.example.data.model.BasketItemState
import com.example.data.model.BasketMealType
import com.example.data.model.BasketPageSession
import com.example.data.model.BasketReserveType
import com.example.data.model.BasketSlot
import com.example.data.model.BasketSubMode
import com.example.data.model.BasketUncertainTimeoutException
import com.example.data.model.FoodBasketDateUtils
import com.example.data.model.MandatorySurveyException
import com.example.data.model.MealType
import com.example.data.model.QuickReservedSlot
import com.example.data.model.ReservationStatus
import com.example.data.model.UnreadMessagesException
import com.example.data.model.WeeklyMenu
import com.example.data.network.CullinanHttpClient
import com.example.data.repository.FoodBasketDataSource
import com.example.data.repository.FoodBasketRepository
import com.example.data.repository.FoodBasketSessionExpiredException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PendingBasketConfirmation(
    val action: BasketActionButton,
    val item: BasketItemState
)

@Immutable
data class BasketFeedback(
    val message: String,
    val isSuccess: Boolean,
    val isWarning: Boolean = false
)

@Immutable
data class FoodBasketUiState(
    val subMode: BasketSubMode = BasketSubMode.SALE,
    val jalaliDateInput: String = "",
    val selectedReserveType: BasketReserveType = BasketReserveType.WEEKLY,
    val selectedMealType: BasketMealType = BasketMealType.LUNCH,
    val pageSession: BasketPageSession? = null,
    val currentItem: BasketItemState? = null,
    val noReservationForSlot: BasketSlot? = null,
    val quickReservedSlots: List<QuickReservedSlot> = emptyList(),
    val isInitializingPage: Boolean = false,
    val isReloadingSlot: Boolean = false,
    val isExecutingTransaction: Boolean = false,
    val isRecoveringFromTimeout: Boolean = false,
    val pendingConfirmation: PendingBasketConfirmation? = null,
    val feedbackBanner: BasketFeedback? = null,
    val errorMessage: String? = null,
    val hasUnreadMessagesBlocked: Boolean = false,
    val surveyBlockInfo: SurveyBlockInfo? = null,
    val sessionExpiredMessage: String? = null
) {
    val isBusy: Boolean
        get() = isInitializingPage || isReloadingSlot || isExecutingTransaction || isRecoveringFromTimeout
}

class FoodBasketViewModel(
    private val repository: FoodBasketDataSource = FoodBasketRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodBasketUiState())
    val uiState: StateFlow<FoodBasketUiState> = _uiState.asStateFlow()

    private val _isTransactionInProgress = MutableStateFlow(false)
    val isTransactionInProgress: StateFlow<Boolean> = _isTransactionInProgress.asStateFlow()

    private var pageLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var transactionJob: Job? = null

    fun ensurePageLoaded() {
        val current = _uiState.value
        if (current.pageSession == null && !current.isInitializingPage && !current.isExecutingTransaction) {
            refreshPage()
        }
    }

    fun refreshPage() {
        if (_uiState.value.isExecutingTransaction) return
        pageLoadJob?.cancel()
        pageLoadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInitializingPage = true,
                    errorMessage = null,
                    sessionExpiredMessage = null,
                    hasUnreadMessagesBlocked = false,
                    surveyBlockInfo = null
                )
            }
            val result = repository.openSalePage()
            result.onSuccess { session ->
                _uiState.update { prev ->
                    val effectiveDate = prev.jalaliDateInput.ifBlank { session.defaultDateJalali }
                    prev.copy(
                        isInitializingPage = false,
                        pageSession = session,
                        jalaliDateInput = effectiveDate,
                        // Clear item bound to an older GET state so hdnReservationId never drifts
                        currentItem = null,
                        noReservationForSlot = null,
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                applyFailureError(err, defaultMsg = "خطا در بارگذاری صفحه فروش به سبد غذایی")
                _uiState.update { it.copy(isInitializingPage = false) }
            }
        }
    }

    fun updateWeeklyMenu(menu: WeeklyMenu?) {
        val slots = if (menu == null) {
            emptyList()
        } else {
            val mealOrder = mapOf(
                MealType.BREAKFAST to 0,
                MealType.LUNCH to 1,
                MealType.DINNER to 2
            )
            menu.days
                .filter { it.dateJalali.isNotBlank() }
                .sortedBy { it.dayIndex }
                .flatMap { day ->
                    day.meals.entries
                        .sortedBy { mealOrder[it.key] ?: 99 }
                        .flatMap { (mealType, meals) ->
                            meals
                                .filter { it.status.isReserved && it.status != ReservationStatus.CONSUMED }
                                .map { meal ->
                                    QuickReservedSlot(
                                        dayName = day.dayName,
                                        jalaliDate = FoodBasketDateUtils.normalizeJalaliDate(day.dateJalali),
                                        mealType = BasketMealType.fromMealType(mealType),
                                        foodName = meal.foodName,
                                        diningHall = meal.diningHall,
                                        priceFormatted = meal.priceFormatted,
                                        count = meal.count.coerceAtLeast(1),
                                        isLockedInWeeklyMenu = meal.status.isLocked
                                    )
                                }
                        }
                }
        }
        _uiState.update { it.copy(quickReservedSlots = slots) }
    }

    fun onJalaliDateChanged(newDate: String) {
        _uiState.update {
            it.copy(
                jalaliDateInput = FoodBasketDateUtils.toEnglishDigits(newDate),
                errorMessage = null
            )
        }
    }

    fun fillTodayDate() {
        val defaultDate = _uiState.value.pageSession?.defaultDateJalali.orEmpty()
        if (defaultDate.isNotBlank()) {
            _uiState.update {
                it.copy(
                    jalaliDateInput = defaultDate,
                    errorMessage = null
                )
            }
        }
    }

    fun onReserveTypeSelected(reserveType: BasketReserveType) {
        if (_uiState.value.isExecutingTransaction) return
        _uiState.update {
            it.copy(
                selectedReserveType = reserveType,
                errorMessage = null
            )
        }
    }

    fun onMealTypeSelected(mealType: BasketMealType) {
        if (_uiState.value.isExecutingTransaction) return
        _uiState.update {
            it.copy(
                selectedMealType = mealType,
                errorMessage = null
            )
        }
    }

    fun selectQuickSlot(quick: QuickReservedSlot) {
        if (_uiState.value.isBusy) return
        _uiState.update {
            it.copy(
                jalaliDateInput = quick.jalaliDate,
                selectedMealType = quick.mealType,
                selectedReserveType = BasketReserveType.WEEKLY,
                currentItem = null,
                noReservationForSlot = null,
                errorMessage = null,
                feedbackBanner = null
            )
        }
        loadCurrentSlot()
    }

    fun loadCurrentSlot() {
        val current = _uiState.value
        if (current.isBusy) return

        val normalizedDate = FoodBasketDateUtils.normalizeJalaliDate(current.jalaliDateInput)
        if (!FoodBasketDateUtils.isValidJalaliDate(normalizedDate)) {
            _uiState.update {
                it.copy(
                    errorMessage = "لطفاً تاریخ جلالی معتبر با قالب yyyy/MM/dd وارد کنید (مثلاً 1405/09/03)."
                )
            }
            return
        }

        val targetSlot = BasketSlot(
            jalaliDate = normalizedDate,
            mealType = current.selectedMealType,
            reserveType = current.selectedReserveType
        )

        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    jalaliDateInput = normalizedDate,
                    isReloadingSlot = true,
                    errorMessage = null,
                    feedbackBanner = null,
                    noReservationForSlot = null
                )
            }

            val activeSession = ensureActivePageSession()
            if (activeSession == null) {
                _uiState.update { it.copy(isReloadingSlot = false) }
                return@launch
            }

            val reloadResult = repository.reloadSlot(targetSlot, activeSession)
            reloadResult.onSuccess { res ->
                _uiState.update {
                    it.copy(
                        isReloadingSlot = false,
                        pageSession = res.updatedSession,
                        currentItem = res.item,
                        noReservationForSlot = if (res.item == null) res.slot else null,
                        feedbackBanner = if (!res.toastMessage.isNullOrBlank()) {
                            BasketFeedback(
                                message = res.toastMessage,
                                isSuccess = res.toastType.equals("success", ignoreCase = true),
                                isWarning = res.toastType.equals("warning", ignoreCase = true)
                            )
                        } else null,
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                applyFailureError(err, defaultMsg = "خطا در استعلام وضعیت غذا از سبد غذایی")
                _uiState.update { it.copy(isReloadingSlot = false) }
            }
        }
    }

    fun requestActionConfirmation(action: BasketActionButton) {
        val current = _uiState.value
        val item = current.currentItem ?: return
        if (current.isBusy || item.actionButton != action) return
        _uiState.update {
            it.copy(pendingConfirmation = PendingBasketConfirmation(action = action, item = item))
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    fun confirmPendingAction() {
        val current = _uiState.value
        val pending = current.pendingConfirmation ?: return
        val session = current.pageSession ?: return
        if (current.isExecutingTransaction || current.isReloadingSlot) return

        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                isExecutingTransaction = true,
                errorMessage = null,
                feedbackBanner = null
            )
        }
        _isTransactionInProgress.value = true

        transactionJob = viewModelScope.launch {
            try {
                val result = repository.executeSaleOrCancel(
                    action = pending.action,
                    item = pending.item,
                    session = session
                )

                result.onSuccess { outcome ->
                    _uiState.update {
                        it.copy(
                            pageSession = outcome.updatedSession,
                            currentItem = outcome.updatedItem ?: it.currentItem,
                            feedbackBanner = BasketFeedback(
                                message = outcome.toastMessage,
                                isSuccess = outcome.isSuccess,
                                isWarning = !outcome.isSuccess
                            )
                        )
                    }

                    // Mandatory post-action reload to replace server's stale #cphMain_lblStatus text
                    if (outcome.isSuccess) {
                        val syncReload = repository.reloadSlot(pending.item.slot, outcome.updatedSession)
                        syncReload.onSuccess { synced ->
                            _uiState.update {
                                it.copy(
                                    pageSession = synced.updatedSession,
                                    currentItem = synced.item,
                                    noReservationForSlot = if (synced.item == null) synced.slot else null
                                )
                            }
                        }.onFailure { syncErr ->
                            AppLogger.logWarn(
                                component = "FoodBasketViewModel",
                                operation = "PostActionSyncReloadFailed",
                                message = "Post-action reload failed (${syncErr.javaClass.simpleName}); keeping button-derived state",
                                errorCategory = "FOOD_BASKET_SYNC_WARN"
                            )
                        }
                    }
                }.onFailure { err ->
                    if (err is BasketUncertainTimeoutException) {
                        _uiState.update {
                            it.copy(
                                isRecoveringFromTimeout = true,
                                feedbackBanner = BasketFeedback(
                                    message = err.message ?: "خطای شبکه؛ در حال بررسی وضعیت واقعی در سرور...",
                                    isSuccess = false,
                                    isWarning = true
                                )
                            )
                        }
                        val recovery = repository.reloadSlot(pending.item.slot, session)
                        recovery.onSuccess { synced ->
                            _uiState.update {
                                it.copy(
                                    isRecoveringFromTimeout = false,
                                    pageSession = synced.updatedSession,
                                    currentItem = synced.item,
                                    noReservationForSlot = if (synced.item == null) synced.slot else null,
                                    feedbackBanner = BasketFeedback(
                                        message = "وضعیت واقعی غذا از سرور بازیابی شد.",
                                        isSuccess = true
                                    )
                                )
                            }
                        }.onFailure { recErr ->
                            _uiState.update { it.copy(isRecoveringFromTimeout = false) }
                            applyFailureError(
                                recErr,
                                defaultMsg = "خطای شبکه هنگام بررسی وضعیت سبد؛ لطفاً با «بارگذاری غذا» وضعیت را بررسی کنید."
                            )
                        }
                    } else {
                        applyFailureError(err, defaultMsg = "خطا در ثبت عملیات سبد غذایی")
                    }
                }
            } finally {
                _isTransactionInProgress.value = false
                _uiState.update {
                    it.copy(
                        isExecutingTransaction = false,
                        isRecoveringFromTimeout = false
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackBanner = null, errorMessage = null) }
    }

    fun cancelNonTransactionalRequests() {
        pageLoadJob?.cancel()
        reloadJob?.cancel()
        pageLoadJob = null
        reloadJob = null
    }

    fun clearSession() {
        pageLoadJob?.cancel()
        reloadJob?.cancel()
        transactionJob?.cancel()
        pageLoadJob = null
        reloadJob = null
        transactionJob = null
        _isTransactionInProgress.value = false
        _uiState.value = FoodBasketUiState()
    }

    private suspend fun ensureActivePageSession(): BasketPageSession? {
        val existing = _uiState.value.pageSession
        if (existing != null && existing.webFormsState.viewState.isNotBlank()) {
            return existing
        }
        val getResult = repository.openSalePage()
        return getResult.fold(
            onSuccess = { loaded ->
                _uiState.update { prev ->
                    prev.copy(
                        pageSession = loaded,
                        jalaliDateInput = prev.jalaliDateInput.ifBlank { loaded.defaultDateJalali }
                    )
                }
                loaded
            },
            onFailure = { err ->
                applyFailureError(err, defaultMsg = "خطا در آماده سازی فرم سبد غذایی")
                null
            }
        )
    }

    private fun applyFailureError(err: Throwable, defaultMsg: String) {
        when (err) {
            is FoodBasketSessionExpiredException -> {
                _uiState.update {
                    it.copy(
                        sessionExpiredMessage = err.message ?: defaultMsg,
                        errorMessage = err.message ?: defaultMsg
                    )
                }
            }
            is UnreadMessagesException -> {
                _uiState.update {
                    it.copy(
                        hasUnreadMessagesBlocked = true,
                        surveyBlockInfo = null,
                        errorMessage = err.message
                    )
                }
            }
            is MandatorySurveyException -> {
                _uiState.update {
                    it.copy(
                        hasUnreadMessagesBlocked = false,
                        surveyBlockInfo = SurveyBlockInfo(
                            message = err.surveyMessage,
                            surveyUrl = err.surveyUrl.ifBlank { CullinanHttpClient.SURVEY_URL }
                        ),
                        errorMessage = err.surveyMessage
                    )
                }
            }
            else -> {
                _uiState.update {
                    it.copy(errorMessage = err.message ?: defaultMsg)
                }
            }
        }
    }
}
