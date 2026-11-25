package com.example.data.repository

import com.example.data.logger.AppLogger
import com.example.data.logger.LogContext
import com.example.data.logger.LogEntry
import com.example.data.logger.LogLevel
import com.example.data.model.BasketActionButton
import com.example.data.model.BasketActionOutcome
import com.example.data.model.BasketItemState
import com.example.data.model.BasketPageSession
import com.example.data.model.BasketReloadResult
import com.example.data.model.BasketSlot
import com.example.data.model.BasketStatus
import com.example.data.model.BasketUncertainTimeoutException
import com.example.data.model.FoodBasketDateUtils
import com.example.data.model.MandatorySurveyException
import com.example.data.model.UnreadMessagesException
import com.example.data.network.CullinanHttpClient
import com.example.data.parser.DeltaParser
import com.example.data.parser.FoodBasketParser
import com.example.data.parser.PageClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

class FoodBasketSessionExpiredException(
    message: String = "نشست کاری شما در سامانه منقضی شده است. لطفاً مجدداً وارد شوید."
) : Exception(message)

class FoodBasketRepository {

    /**
     * Loads GET /FoodBasket/FoodBasketSale.aspx and extracts initial WebForms tokens and form defaults.
     * Uses the shared portal mutex so basket operations never overlap with auth/logout transitions.
     */
    suspend fun openSalePage(): Result<BasketPageSession> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("basket_get")
        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.INFO,
                component = "FoodBasketRepository",
                operation = "OpenSalePage",
                flowId = flowId,
                message = "Fetching FoodBasketSale.aspx initial form"
            )
        )

        CullinanHttpClient.portalSessionMutex.withLock {
            try {
                val request = Request.Builder()
                    .url(CullinanHttpClient.FOOD_BASKET_SALE_URL)
                    .get()
                    .header("Referer", CullinanHttpClient.DEFAULT_PAGE_URL)
                    .tag(LogContext::class.java, LogContext(flowId, "FoodBasketGetPage"))
                    .build()

                var isSuccessful = false
                var responseCode = 0
                var finalUrl = ""
                var html = ""
                CullinanHttpClient.executeCall(request).use { response ->
                    isSuccessful = response.isSuccessful
                    responseCode = response.code
                    finalUrl = response.request.url.toString()
                    if (isSuccessful) {
                        html = response.body?.string() ?: ""
                    }
                }

                if (!isSuccessful) {
                    AppLogger.logError(
                        tag = "FoodBasketRepository",
                        message = "OpenSalePage failed with HTTP $responseCode",
                        flowId = flowId,
                        operation = "OpenSalePageHttpError",
                        errorCategory = "FOOD_BASKET_HTTP_$responseCode"
                    )
                    return@withLock Result.failure(Exception("خطا در بارگذاری صفحه سبد غذایی ($responseCode)"))
                }

                val classification = PageClassifier.classify(html, responseCode, finalUrl, flowId = flowId)
                if (classification.pageType != PageClassifier.TYPE_FOOD_BASKET_SALE) {
                    return@withLock Result.failure(mapGateOrInvalidPageException(classification))
                }

                val session = FoodBasketParser.parseInitialPage(html)
                AppLogger.log(
                    LogEntry(
                        timestamp = "",
                        level = LogLevel.INFO,
                        component = "FoodBasketRepository",
                        operation = "OpenSalePageSuccess",
                        flowId = flowId,
                        message = "Loaded FoodBasketSale.aspx (httpStatus=$responseCode, vsLen=${session.webFormsState.viewState.length}, hasResult=${session.hasResultPanel})"
                    )
                )
                Result.success(session)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.logError(
                    tag = "FoodBasketRepository",
                    message = "OpenSalePage exception: ${e.javaClass.simpleName}",
                    throwable = e,
                    flowId = flowId,
                    operation = "OpenSalePageException",
                    errorCategory = "FOOD_BASKET_GET_ERROR"
                )
                Result.failure(e)
            }
        }
    }

    /**
     * Queries the server status for one (date, reserveType, mealType) slot via btnFoodReload.
     * Logs only non-sensitive metadata (slot date/meal/reserveType, HTTP code, status enum).
     */
    suspend fun reloadSlot(
        slot: BasketSlot,
        session: BasketPageSession
    ): Result<BasketReloadResult> = withContext(Dispatchers.IO) {
        val normalizedSlot = slot.copy(jalaliDate = FoodBasketDateUtils.normalizeJalaliDate(slot.jalaliDate))
        val flowId = AppLogger.generateFlowId("basket_reload")

        if (session.webFormsState.viewState.isBlank()) {
            return@withContext Result.failure(Exception("وضعیت فرم صفحه آماده نیست؛ لطفاً صفحه را تازه‌سازی کنید."))
        }

        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.INFO,
                component = "FoodBasketRepository",
                operation = "ReloadBasketSlot",
                flowId = flowId,
                message = "Querying FoodBasket slot (date=${normalizedSlot.jalaliDate}, meal=${normalizedSlot.mealType.formValue}, reserveType=${normalizedSlot.reserveType.formValue})"
            )
        )

        CullinanHttpClient.portalSessionMutex.withLock {
            try {
                val formBody = FoodBasketFormBuilder.buildReloadBody(normalizedSlot, session)
                val postResult = executeDeltaPost(
                    formBody = formBody,
                    flowId = flowId,
                    operationTag = "FoodBasketReload"
                )

                val reloadResult = FoodBasketParser.parseUpdatePanelDelta(
                    deltaResponse = postResult.deltaResponse,
                    requestedSlot = normalizedSlot,
                    previousSession = session
                )

                val resolvedStatus = reloadResult.item?.status ?: BasketStatus.NO_RESERVATION
                AppLogger.log(
                    LogEntry(
                        timestamp = "",
                        level = LogLevel.INFO,
                        component = "FoodBasketRepository",
                        operation = "ReloadBasketSlotSuccess",
                        flowId = flowId,
                        message = "Slot reload completed (httpStatus=${postResult.responseCode}, status=${resolvedStatus.name}, toastType=${reloadResult.toastType ?: "none"}, hasAlert=${!reloadResult.alertMessage.isNullOrBlank()}, vsLen=${reloadResult.updatedSession.webFormsState.viewState.length})"
                    )
                )

                Result.success(reloadResult)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.logError(
                    tag = "FoodBasketRepository",
                    message = "ReloadBasketSlot failed: ${e.javaClass.simpleName}",
                    throwable = e,
                    flowId = flowId,
                    operation = "ReloadBasketSlotFailure",
                    errorCategory = "FOOD_BASKET_RELOAD_ERROR"
                )
                Result.failure(e)
            }
        }
    }

    /**
     * Executes a single non-retried financial marketplace POST (btnAccept or btnCancel).
     * Never retries automatically; wraps IOExceptions as BasketUncertainTimeoutException so callers
     * can run btnFoodReload first before deciding what happened on the server.
     */
    suspend fun executeSaleOrCancel(
        action: BasketActionButton,
        item: BasketItemState,
        session: BasketPageSession
    ): Result<BasketActionOutcome> = withContext(Dispatchers.IO) {
        val opName = if (action == BasketActionButton.ACCEPT) "SellToFoodBasket" else "CancelFromFoodBasket"
        val flowId = AppLogger.generateFlowId(if (action == BasketActionButton.ACCEPT) "basket_sell" else "basket_cancel")

        if (item.reservationId.isBlank()) {
            return@withContext Result.failure(Exception("شناسه رزرو معتبر برای این وعده دریافت نشده است."))
        }
        if (item.actionButton != action) {
            return@withContext Result.failure(Exception("وضعیت فعلی غذا با عملیات انتخاب‌شده هماهنگ نیست."))
        }
        if (session.webFormsState.viewState.isBlank()) {
            return@withContext Result.failure(Exception("اطلاعات فرم صفحه منقضی شده است؛ لطفاً ابتدا بارگذاری مجدد بزنید."))
        }

        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.INFO,
                component = "FoodBasketRepository",
                operation = opName,
                flowId = flowId,
                message = "Executing $opName (date=${item.slot.jalaliDate}, meal=${item.slot.mealType.formValue}, reserveType=${item.slot.reserveType.formValue})"
            )
        )

        CullinanHttpClient.portalSessionMutex.withLock {
            try {
                val formBody = FoodBasketFormBuilder.buildActionBody(
                    action = action,
                    slot = item.slot,
                    reservationId = item.reservationId,
                    buttonValue = item.buttonLabel,
                    session = session
                )

                val postResult = executeDeltaPost(
                    formBody = formBody,
                    flowId = flowId,
                    operationTag = opName,
                    isFinancialMutation = true
                )

                val parsedAfterAction = FoodBasketParser.parseUpdatePanelDelta(
                    deltaResponse = postResult.deltaResponse,
                    requestedSlot = item.slot,
                    previousSession = session
                )

                val toastType = postResult.deltaResponse.toastType.orEmpty()
                val toastTitle = postResult.deltaResponse.toastTitle.orEmpty()
                val toastMessage = postResult.deltaResponse.toastMessage.orEmpty()
                val alertMessage = parsedAfterAction.alertMessage

                val expectedNextButton = if (action == BasketActionButton.ACCEPT) {
                    BasketActionButton.CANCEL
                } else {
                    BasketActionButton.ACCEPT
                }

                val isSuccess = toastType.equals("success", ignoreCase = true) ||
                    (toastType.isBlank() && parsedAfterAction.item?.actionButton == expectedNextButton)

                val displayMessage = when {
                    toastMessage.isNotBlank() -> toastMessage
                    !alertMessage.isNullOrBlank() -> alertMessage
                    isSuccess && action == BasketActionButton.ACCEPT -> "این غذا به سبد غذایی جهت فروش اضافه شد"
                    isSuccess && action == BasketActionButton.CANCEL -> "این غذا از سبد غذایی حذف شد"
                    else -> "پاسخ سرور برای این عملیات نامشخص بود."
                }

                val outcomeStatus = parsedAfterAction.item?.status ?: item.status
                AppLogger.log(
                    LogEntry(
                        timestamp = "",
                        level = if (isSuccess) LogLevel.INFO else LogLevel.WARN,
                        component = "FoodBasketRepository",
                        operation = "${opName}Completed",
                        flowId = flowId,
                        message = "$opName completed (httpStatus=${postResult.responseCode}, isSuccess=$isSuccess, toastType=${toastType.ifBlank { "none" }}, buttonStatus=${outcomeStatus.name}, vsLen=${parsedAfterAction.updatedSession.webFormsState.viewState.length})"
                    )
                )

                Result.success(
                    BasketActionOutcome(
                        action = action,
                        isSuccess = isSuccess,
                        toastType = toastType,
                        toastTitle = toastTitle,
                        toastMessage = displayMessage,
                        updatedItem = parsedAfterAction.item,
                        updatedSession = parsedAfterAction.updatedSession,
                        alertMessage = alertMessage
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: BasketUncertainTimeoutException) {
                AppLogger.logWarn(
                    component = "FoodBasketRepository",
                    operation = "${opName}UncertainTimeout",
                    message = "Network interruption during $opName; caller must inspect server truth with btnFoodReload",
                    flowId = flowId,
                    errorCategory = "FOOD_BASKET_MUTATION_TIMEOUT"
                )
                Result.failure(e)
            } catch (e: Exception) {
                AppLogger.logError(
                    tag = "FoodBasketRepository",
                    message = "$opName failed: ${e.javaClass.simpleName}",
                    throwable = e,
                    flowId = flowId,
                    operation = "${opName}Failure",
                    errorCategory = "FOOD_BASKET_ACTION_ERROR"
                )
                Result.failure(e)
            }
        }
    }

    private data class DeltaPostExecution(
        val responseCode: Int,
        val deltaResponse: com.example.data.parser.DeltaResponse
    )

    private fun executeDeltaPost(
        formBody: FormBody,
        flowId: String,
        operationTag: String,
        isFinancialMutation: Boolean = false
    ): DeltaPostExecution {
        val request = Request.Builder()
            .url(CullinanHttpClient.FOOD_BASKET_SALE_URL)
            .post(formBody)
            .header("X-MicrosoftAjax", "Delta=true")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Cache-Control", "no-cache")
            .header("Accept", "*/*")
            .header("Origin", CullinanHttpClient.BASE_URL)
            .header("Referer", CullinanHttpClient.FOOD_BASKET_SALE_URL)
            .tag(LogContext::class.java, LogContext(flowId, operationTag))
            .build()

        var isSuccessful = false
        var responseCode = 0
        var finalUrl = ""
        var rawText = ""

        try {
            val responseCall = if (isFinancialMutation) {
                CullinanHttpClient.executeCallNoRetry(request)
            } else {
                CullinanHttpClient.executeCall(request)
            }
            responseCall.use { response ->
                isSuccessful = response.isSuccessful
                responseCode = response.code
                finalUrl = response.request.url.toString()
                if (isSuccessful) {
                    rawText = response.body?.string() ?: ""
                }
            }
        } catch (ioe: IOException) {
            if (isFinancialMutation) {
                throw BasketUncertainTimeoutException(
                    message = "ارتباط شبکه هنگام ارسال درخواست به سبد غذایی قطع شد؛ در حال بررسی وضعیت سرور...",
                    cause = ioe
                )
            }
            throw ioe
        }

        if (!isSuccessful) {
            throw Exception("خطا در ارتباط با سرویس سبد غذایی ($responseCode)")
        }

        val isFullHtmlFallback = (rawText.contains("<!DOCTYPE", ignoreCase = true) || rawText.contains("<html", ignoreCase = true)) &&
            !rawText.contains("|updatePanel|") && !rawText.contains("|hiddenField|")
        if (isFullHtmlFallback) {
            val classification = PageClassifier.classify(rawText, responseCode, finalUrl, flowId = flowId)
            throw mapGateOrInvalidPageException(classification)
        }

        val deltaResponse = DeltaParser.parse(rawText, flowId = flowId)
        if (deltaResponse.isSessionExpired) {
            throw FoodBasketSessionExpiredException()
        }

        val redirectUrl = deltaResponse.redirectUrl
        if (!redirectUrl.isNullOrBlank()) {
            when {
                redirectUrl.contains("Login.aspx", ignoreCase = true) -> throw FoodBasketSessionExpiredException()
                redirectUrl.contains("MyMessage.aspx", ignoreCase = true) -> throw UnreadMessagesException(1)
                redirectUrl.contains("Survey", ignoreCase = true) -> throw MandatorySurveyException(
                    surveyMessage = "شرکت در نظرسنجی سامانه قبل از ادامه الزامی است.",
                    surveyUrl = CullinanHttpClient.SURVEY_URL
                )
            }
        }

        if (!deltaResponse.errorMessage.isNullOrBlank()) {
            throw Exception("خطای سرور سبد غذایی: ${deltaResponse.errorMessage}")
        }

        return DeltaPostExecution(responseCode = responseCode, deltaResponse = deltaResponse)
    }

    private fun mapGateOrInvalidPageException(classification: PageClassifier.ClassificationResult): Exception {
        return when (classification.pageType) {
            PageClassifier.TYPE_LOGIN -> FoodBasketSessionExpiredException()
            PageClassifier.TYPE_MESSAGES -> UnreadMessagesException(classification.unreadMessageCount)
            PageClassifier.TYPE_SURVEY -> MandatorySurveyException(
                surveyMessage = classification.surveyMessage
                    ?: "شرکت در نظرسنجی، برای استفاده از امکانات سامانه ضروری است.",
                surveyUrl = classification.surveyUrl ?: CullinanHttpClient.SURVEY_URL
            )
            PageClassifier.TYPE_NOTICE -> Exception("اطلاعیه سامانه دسترسی به سبد غذایی را مسدود کرده است.")
            else -> Exception("پاسخ سرور برای صفحه سبد غذایی معتبر نبود. لطفاً صفحه را تازه‌سازی کنید.")
        }
    }
}
