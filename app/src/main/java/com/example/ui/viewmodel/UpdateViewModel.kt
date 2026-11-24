package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.logger.AppLogger
import com.example.data.model.AppUpdateInfo
import com.example.data.repository.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class UpdateStatusKind {
    UP_TO_DATE,
    NO_PUBLISHED_RELEASE,
    ERROR
}

data class UpdateUserStatus(
    val title: String,
    val message: String,
    val kind: UpdateStatusKind
)

class UpdateViewModel(
    private val updateRepository: UpdateRepository = UpdateRepository()
) : ViewModel() {

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _userStatus = MutableStateFlow<UpdateUserStatus?>(null)
    val userStatus: StateFlow<UpdateUserStatus?> = _userStatus.asStateFlow()

    init {
        // Automatic check on launch
        checkForUpdates(isManual = false)
    }

    fun checkForUpdates(isManual: Boolean) {
        if (_isChecking.value) return
        AppLogger.logInfo("UpdateViewModel", "CheckForUpdates", "Initiating update check (isManual=$isManual)")
        viewModelScope.launch {
            _isChecking.value = true
            try {
                val result = updateRepository.checkForUpdate()
                result.onSuccess { info ->
                    if (!info.hasPublishedRelease) {
                        AppLogger.logInfo("UpdateViewModel", "NoPublishedRelease", "No formal release published yet (current=${info.currentVersionName})")
                        if (isManual) {
                            val msg = "هنوز هیچ نسخه رسمی در مخزن گیت‌هاب منتشر نشده است (نسخه فعلی شما: ${info.currentVersionName})"
                            _userMessage.value = msg
                            _userStatus.value = UpdateUserStatus(
                                title = "انتشار رسمی ثبت نشده است",
                                message = msg,
                                kind = UpdateStatusKind.NO_PUBLISHED_RELEASE
                            )
                        }
                    } else if (info.isUpdateAvailable) {
                        _updateInfo.value = info
                        AppLogger.logInfo("UpdateViewModel", "UpdateAvailable", "New version ${info.latestVersionName} available (current=${info.currentVersionName})")
                    } else {
                        AppLogger.logDebug("UpdateViewModel", "AppUpToDate", "App is up to date (${info.currentVersionName})")
                        if (isManual) {
                            val msg = "برنامه شما به‌روز است (نسخه ${info.currentVersionName})"
                            _userMessage.value = msg
                            _userStatus.value = UpdateUserStatus(
                                title = "برنامه به‌روز است",
                                message = msg,
                                kind = UpdateStatusKind.UP_TO_DATE
                            )
                        }
                    }
                }.onFailure { error ->
                    AppLogger.logWarn(
                        component = "UpdateViewModel",
                        operation = "UpdateCheckFailed",
                        message = "Update check failed (isManual=$isManual): ${error.message}",
                        throwable = error,
                        errorCategory = "UPDATE_CHECK_ERROR"
                    )
                    if (isManual) {
                        val msg = "خطا در بررسی به‌روزرسانی: ${error.message}"
                        _userMessage.value = msg
                        _userStatus.value = UpdateUserStatus(
                            title = "خطا در بررسی به‌روزرسانی",
                            message = msg,
                            kind = UpdateStatusKind.ERROR
                        )
                    }
                }
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateInfo.value = null
    }

    fun dismissUserMessage() {
        _userMessage.value = null
        _userStatus.value = null
    }
}
