package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.captcha.solver.CaptchaResult
import com.example.data.captcha.solver.CaptchaSolver
import com.example.data.captcha.solver.MlKitCaptchaSolver
import com.example.data.local.UserPreferences
import com.example.data.logger.AppLogger
import com.example.data.model.LoginPageState
import com.example.data.model.UserSession
import com.example.data.repository.AuthRepository
import com.example.data.security.BiometricAuthenticator
import com.example.data.security.BiometricCapabilityChecker
import com.example.data.security.BiometricHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AuthViewModel(
    application: Application,
    private val authRepository: AuthRepository,
    private val userPrefs: UserPreferences,
    private val biometricCapabilityChecker: BiometricCapabilityChecker,
    private val captchaSolverFactory: () -> CaptchaSolver = { MlKitCaptchaSolver() }
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        AuthRepository(),
        UserPreferences(application),
        BiometricCapabilityChecker { BiometricHelper.canAuthenticate(it) }
    )

    constructor(application: Application, authRepository: AuthRepository) : this(
        application,
        authRepository,
        UserPreferences(application),
        BiometricCapabilityChecker { BiometricHelper.canAuthenticate(it) }
    )

    constructor(
        application: Application,
        authRepository: AuthRepository,
        userPrefs: UserPreferences
    ) : this(
        application,
        authRepository,
        userPrefs,
        BiometricCapabilityChecker { BiometricHelper.canAuthenticate(it) }
    )

    private val _isEncryptionAvailable = MutableStateFlow(userPrefs.isEncryptionAvailable)
    val isEncryptionAvailable: StateFlow<Boolean> = _isEncryptionAvailable.asStateFlow()

    private val _rememberMe = MutableStateFlow(userPrefs.isEncryptionAvailable && userPrefs.rememberMe)
    val rememberMe: StateFlow<Boolean> = _rememberMe.asStateFlow()

    private val _requiresBiometricUnlock = MutableStateFlow(false)
    val requiresBiometricUnlock: StateFlow<Boolean> = _requiresBiometricUnlock.asStateFlow()

    private val _biometricUnavailableWhileLocked = MutableStateFlow(false)
    val biometricUnavailableWhileLocked: StateFlow<Boolean> = _biometricUnavailableWhileLocked.asStateFlow()

    private val _canQuickFillSavedCredentials = MutableStateFlow(false)
    val canQuickFillSavedCredentials: StateFlow<Boolean> = _canQuickFillSavedCredentials.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isPasswordVisible = MutableStateFlow(false)
    val isPasswordVisible: StateFlow<Boolean> = _isPasswordVisible.asStateFlow()

    private val _captchaInput = MutableStateFlow("")
    val captchaInput: StateFlow<String> = _captchaInput.asStateFlow()

    private val _pageState = MutableStateFlow(LoginPageState())
    val pageState: StateFlow<LoginPageState> = _pageState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isCaptchaRefreshing = MutableStateFlow(false)
    val isCaptchaRefreshing: StateFlow<Boolean> = _isCaptchaRefreshing.asStateFlow()

    private val _isAutoCaptchaEnabled = MutableStateFlow(userPrefs.isAutoCaptchaEnabled)
    val isAutoCaptchaEnabled: StateFlow<Boolean> = _isAutoCaptchaEnabled.asStateFlow()

    private val _isSolvingCaptcha = MutableStateFlow(false)
    val isSolvingCaptcha: StateFlow<Boolean> = _isSolvingCaptcha.asStateFlow()

    private val _isCaptchaAutoFilled = MutableStateFlow(false)
    val isCaptchaAutoFilled: StateFlow<Boolean> = _isCaptchaAutoFilled.asStateFlow()

    private val _captchaSoftMessage = MutableStateFlow<String?>(null)
    val captchaSoftMessage: StateFlow<String?> = _captchaSoftMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val isCompatibilityModeActive: StateFlow<Boolean> = com.example.data.network.CullinanHttpClient.isCompatibilityModeActive

    private var loadPageJob: Job? = null
    private var refreshCaptchaJob: Job? = null
    private var captchaSolveJob: Job? = null
    private var captchaImageVersion: Long = 0L
    private var processedCaptchaVersion: Long = -1L
    private var cachedCaptchaSolver: CaptchaSolver? = null
    private var isBiometricUnlockedForCurrentForm: Boolean = false
    private var suppressAutoFillAfterLogout: Boolean = false

    init {
        syncCredentialState(initialLoad = true)
        AppLogger.logDebug(
            "AuthViewModel",
            "Init",
            "AuthViewModel initialized (rememberMe=${_rememberMe.value}, encryptionAvailable=${_isEncryptionAvailable.value}, biometricRequired=${_requiresBiometricUnlock.value}, failClosedUnavailable=${_biometricUnavailableWhileLocked.value}, autoCaptchaEnabled=${_isAutoCaptchaEnabled.value})"
        )
        loadLoginPage()
    }

    fun toggleCompatibilityMode() {
        val current = com.example.data.network.CullinanHttpClient.isCompatibilityModeActive.value
        val next = !current
        com.example.data.network.CullinanHttpClient.setCompatibilityMode(next)
        AppLogger.logAction("Login SSL Compatibility toggled", "enabled=$next")
        loadLoginPage()
    }

    fun syncCredentialState(initialLoad: Boolean = false) {
        syncAutoCaptchaPreferenceState()
        val encryptionOk = userPrefs.isEncryptionAvailable
        _isEncryptionAvailable.value = encryptionOk
        if (!encryptionOk) {
            _rememberMe.value = false
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            _canQuickFillSavedCredentials.value = false
            isBiometricUnlockedForCurrentForm = false
            suppressAutoFillAfterLogout = false
            if (initialLoad) {
                _username.value = ""
                _password.value = ""
                _isPasswordVisible.value = false
            }
            return
        }

        val rem = userPrefs.rememberMe
        _rememberMe.value = rem

        if (!rem || !userPrefs.hasSavedCredentials()) {
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            _canQuickFillSavedCredentials.value = false
            isBiometricUnlockedForCurrentForm = false
            suppressAutoFillAfterLogout = false
            if (initialLoad) {
                _username.value = ""
                _password.value = ""
                _isPasswordVisible.value = false
            }
            return
        }

        val lockEnabled = userPrefs.isBiometricLockEnabled
        val canAuth = biometricCapabilityChecker.canAuthenticate(getApplication())

        if (lockEnabled) {
            _canQuickFillSavedCredentials.value = false
            if (!canAuth) {
                // Fail-closed: lock is ON, device auth unavailable -> NEVER auto-fill credentials
                _username.value = ""
                _password.value = ""
                _isPasswordVisible.value = false
                isBiometricUnlockedForCurrentForm = false
                _requiresBiometricUnlock.value = false
                _biometricUnavailableWhileLocked.value = true
            } else {
                _biometricUnavailableWhileLocked.value = false
                if (initialLoad || !isBiometricUnlockedForCurrentForm || _password.value.isEmpty()) {
                    _username.value = ""
                    _password.value = ""
                    _isPasswordVisible.value = false
                    isBiometricUnlockedForCurrentForm = false
                    _requiresBiometricUnlock.value = true
                }
            }
        } else {
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            if (suppressAutoFillAfterLogout) {
                _canQuickFillSavedCredentials.value = _password.value.isEmpty()
            } else {
                _canQuickFillSavedCredentials.value = false
                if (initialLoad || (_username.value.isBlank() && _password.value.isBlank())) {
                    _username.value = userPrefs.savedUsername
                    _password.value = userPrefs.savedPassword
                    _isPasswordVisible.value = false
                }
            }
        }
    }

    fun unlockWithBiometrics(authenticator: BiometricAuthenticator?) {
        if (!userPrefs.isEncryptionAvailable || !userPrefs.rememberMe || !userPrefs.hasSavedCredentials()) {
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            _isPasswordVisible.value = false
            isBiometricUnlockedForCurrentForm = false
            return
        }

        if (userPrefs.isBiometricLockEnabled && !biometricCapabilityChecker.canAuthenticate(getApplication())) {
            _username.value = ""
            _password.value = ""
            _isPasswordVisible.value = false
            isBiometricUnlockedForCurrentForm = false
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = true
            _errorMessage.value = "احراز هویت دستگاه در دسترس نیست؛ بازیابی رمز ذخیره‌شده مسدود شد."
            return
        }

        if (authenticator == null) {
            onBiometricAuthError("امکان نمایش پنجره احراز هویت بیومتریک در این صفحه وجود ندارد.")
            return
        }

        authenticator.authenticate(
            title = "تأیید هویت امنیتی",
            subtitle = "برای استفاده از اطلاعات ورود ذخیره‌شده، هویت خود را با اثر انگشت یا رمز دستگاه تأیید کنید.",
            onSuccess = { unlockSavedCredentialsAfterBiometric() },
            onError = { errMsg -> onBiometricAuthError(errMsg) },
            onCancelled = { onBiometricAuthCancelled() }
        )
    }

    fun unlockSavedCredentialsAfterBiometric() {
        if (!userPrefs.isEncryptionAvailable || !userPrefs.rememberMe || !userPrefs.hasSavedCredentials()) {
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            _isPasswordVisible.value = false
            isBiometricUnlockedForCurrentForm = false
            return
        }
        if (userPrefs.isBiometricLockEnabled && !biometricCapabilityChecker.canAuthenticate(getApplication())) {
            _username.value = ""
            _password.value = ""
            _isPasswordVisible.value = false
            isBiometricUnlockedForCurrentForm = false
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = true
            _errorMessage.value = "احراز هویت دستگاه در دسترس نیست؛ بازیابی رمز ذخیره‌شده مسدود شد."
            return
        }
        _username.value = userPrefs.savedUsername
        _password.value = userPrefs.savedPassword
        // Password remains masked after unlock until user explicitly toggles visibility
        _isPasswordVisible.value = false
        isBiometricUnlockedForCurrentForm = true
        suppressAutoFillAfterLogout = false
        _requiresBiometricUnlock.value = false
        _biometricUnavailableWhileLocked.value = false
        _canQuickFillSavedCredentials.value = false
        _errorMessage.value = null
        AppLogger.logInfo("AuthViewModel", "BiometricUnlockSuccess", "Populated saved credentials after biometric verification")
    }

    fun onBiometricAuthCancelled() {
        _isPasswordVisible.value = false
        if (userPrefs.isBiometricLockEnabled && !isBiometricUnlockedForCurrentForm) {
            _username.value = ""
            _password.value = ""
            val canAuth = biometricCapabilityChecker.canAuthenticate(getApplication())
            val hasSaved = userPrefs.isEncryptionAvailable && userPrefs.rememberMe && userPrefs.hasSavedCredentials()
            _requiresBiometricUnlock.value = hasSaved && canAuth
            _biometricUnavailableWhileLocked.value = hasSaved && !canAuth
        }
        AppLogger.logInfo("AuthViewModel", "BiometricUnlockCancelled", "Biometric unlock cancelled by user; credentials remain locked")
    }

    fun onBiometricAuthError(message: String) {
        _isPasswordVisible.value = false
        if (userPrefs.isBiometricLockEnabled && !isBiometricUnlockedForCurrentForm) {
            _username.value = ""
            _password.value = ""
        }
        _errorMessage.value = message
    }

    fun clearSavedCredentialsForRecovery() {
        userPrefs.clearCredentials()
        userPrefs.isBiometricLockEnabled = false
        _rememberMe.value = false
        _username.value = ""
        _password.value = ""
        _isPasswordVisible.value = false
        isBiometricUnlockedForCurrentForm = false
        suppressAutoFillAfterLogout = false
        _requiresBiometricUnlock.value = false
        _biometricUnavailableWhileLocked.value = false
        _canQuickFillSavedCredentials.value = false
        _errorMessage.value = null
        AppLogger.logInfo("AuthViewModel", "BiometricRecoveryWipe", "Cleared saved credentials and reset biometric lock for recovery")
    }

    fun refillSavedCredentialsAfterLogout() {
        if (!userPrefs.isEncryptionAvailable || !userPrefs.rememberMe || !userPrefs.hasSavedCredentials()) {
            _canQuickFillSavedCredentials.value = false
            return
        }
        if (userPrefs.isBiometricLockEnabled) {
            // Never bypass biometric lock
            syncCredentialState(initialLoad = false)
            return
        }
        _username.value = userPrefs.savedUsername
        _password.value = userPrefs.savedPassword
        _isPasswordVisible.value = false
        suppressAutoFillAfterLogout = false
        _canQuickFillSavedCredentials.value = false
        _errorMessage.value = null
    }

    fun togglePasswordVisibility() {
        _isPasswordVisible.value = !_isPasswordVisible.value
    }

    fun setPasswordVisible(visible: Boolean) {
        _isPasswordVisible.value = visible
    }

    fun onUsernameChange(newVal: String) {
        _username.value = newVal
        suppressAutoFillAfterLogout = false
        _canQuickFillSavedCredentials.value = false
        _errorMessage.value = null
    }

    fun onPasswordChange(newVal: String) {
        _password.value = newVal
        suppressAutoFillAfterLogout = false
        _canQuickFillSavedCredentials.value = false
        _errorMessage.value = null
    }

    fun onCaptchaChange(newVal: String) {
        // Enforce max 4 digits and treat manual edits as user intent
        val digitsOnly = newVal.filter { it.isDigit() }.take(4)
        if (digitsOnly.isNotEmpty()) {
            captchaSolveJob?.cancel()
            captchaSolveJob = null
            _isSolvingCaptcha.value = false
        }
        _captchaInput.value = digitsOnly
        _isCaptchaAutoFilled.value = false
        _captchaSoftMessage.value = null
        _errorMessage.value = null
    }

    fun toggleRememberMe() {
        if (!userPrefs.isEncryptionAvailable) {
            _rememberMe.value = false
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            _canQuickFillSavedCredentials.value = false
            _isPasswordVisible.value = false
            _errorMessage.value = "رمزنگاری امن سخت‌افزاری روی این دستگاه در دسترس نیست؛ گزینه «مرا به خاطر بسپار» غیرفعال است."
            return
        }
        val next = !_rememberMe.value
        _rememberMe.value = next
        userPrefs.rememberMe = next
        AppLogger.logInfo("AuthViewModel", "RememberMeToggled", "rememberMe=$next")
        if (!next) {
            userPrefs.clearCredentials()
            isBiometricUnlockedForCurrentForm = false
            suppressAutoFillAfterLogout = false
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            _canQuickFillSavedCredentials.value = false
            _isPasswordVisible.value = false
        }
    }

    fun loadLoginPage() {
        loadPageJob?.cancel()
        refreshCaptchaJob?.cancel()
        cancelCaptchaSolveAndClearStatus(clearSoftMessage = true)
        captchaImageVersion++
        AppLogger.logInfo("AuthViewModel", "LoadLoginPage", "Loading initial Login.aspx state and captcha")
        loadPageJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = authRepository.fetchLoginPage()
            result.onSuccess { state ->
                _pageState.value = state
                _captchaInput.value = ""
                _isCaptchaAutoFilled.value = false
                _captchaSoftMessage.value = null
                val imageBytes = state.captchaBytes
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    captchaImageVersion++
                    maybeSolveCaptcha(imageBytes, captchaImageVersion)
                }
                AppLogger.logInfo(
                    "AuthViewModel",
                    "LoadLoginPageSuccess",
                    "Login page ready (hasCaptcha=${state.captchaBytes != null}, pemLen=${state.publicKeyPem.length})"
                )
            }.onFailure { error ->
                val msg = error.message ?: "خطا در برقراری ارتباط با سرور سامانه تغذیه"
                _errorMessage.value = msg
                AppLogger.logError("AuthViewModel", "LoadLoginPage failed: $msg", error, operation = "LoadLoginPageFailure", errorCategory = "LOGIN_PAGE_FETCH_ERROR")
            }
            _isLoading.value = false
        }
    }

    fun refreshCaptcha() {
        refreshCaptchaJob?.cancel()
        cancelCaptchaSolveAndClearStatus(clearSoftMessage = true)
        captchaImageVersion++
        AppLogger.logInfo("AuthViewModel", "RefreshCaptcha", "Refreshing captcha image")
        refreshCaptchaJob = viewModelScope.launch {
            _isCaptchaRefreshing.value = true
            try {
                val result = authRepository.refreshCaptcha()
                result.onSuccess { bytes ->
                    _pageState.value = _pageState.value.copy(captchaBytes = bytes)
                    _captchaInput.value = ""
                    _isCaptchaAutoFilled.value = false
                    _captchaSoftMessage.value = null
                    captchaImageVersion++
                    AppLogger.logDebug("AuthViewModel", "RefreshCaptchaSuccess", "Captcha refreshed (${bytes.size} bytes)")
                    if (bytes.isNotEmpty()) {
                        maybeSolveCaptcha(bytes, captchaImageVersion)
                    }
                }.onFailure { error ->
                    _errorMessage.value = error.message
                    AppLogger.logWarn(
                        component = "AuthViewModel",
                        operation = "RefreshCaptchaFailed",
                        message = "Captcha refresh failed: ${error.message}",
                        throwable = error,
                        errorCategory = "CAPTCHA_REFRESH_FAILED"
                    )
                }
            } finally {
                _isCaptchaRefreshing.value = false
            }
        }
    }

    fun login(onSuccess: (UserSession) -> Unit) {
        val user = _username.value.trim()
        val pass = _password.value.trim()
        val captcha = _captchaInput.value.trim()

        if (user.isEmpty()) {
            AppLogger.logWarn("AuthViewModel", "LoginValidationFailed", "Username empty", errorCategory = "VALIDATION_ERROR")
            _errorMessage.value = "لطفاً نام کاربری را وارد نمایید."
            return
        }
        if (pass.isEmpty()) {
            AppLogger.logWarn("AuthViewModel", "LoginValidationFailed", "Password empty", errorCategory = "VALIDATION_ERROR")
            _errorMessage.value = "لطفاً رمز عبور را وارد نمایید."
            return
        }
        if (captcha.isEmpty()) {
            AppLogger.logWarn("AuthViewModel", "LoginValidationFailed", "Captcha empty", errorCategory = "VALIDATION_ERROR")
            _errorMessage.value = "لطفاً کد امنیتی را وارد نمایید."
            return
        }

        AppLogger.logInfo("AuthViewModel", "LoginSubmitted", "Submitting login form (rememberMe=${_rememberMe.value}, captchaLen=${captcha.length})")
        cancelCaptchaSolveAndClearStatus(clearSoftMessage = true)
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val result = authRepository.login(
                usernamePlain = user,
                passwordPlain = pass,
                captchaInput = captcha,
                pageState = _pageState.value
            )

            result.onSuccess { session ->
                if (_rememberMe.value && userPrefs.isEncryptionAvailable) {
                    userPrefs.rememberMe = true
                    userPrefs.saveCredentials(user, pass)
                } else {
                    userPrefs.clearCredentials()
                }
                _username.value = ""
                _password.value = ""
                _isPasswordVisible.value = false
                _captchaInput.value = ""
                isBiometricUnlockedForCurrentForm = false
                suppressAutoFillAfterLogout = false
                _requiresBiometricUnlock.value = false
                _biometricUnavailableWhileLocked.value = false
                _canQuickFillSavedCredentials.value = false
                AppLogger.logInfo("AuthViewModel", "LoginSuccess", "Login succeeded (hasUnreadMessages=${session.hasUnreadMessages})")
                onSuccess(session)
            }.onFailure { error ->
                if (!_rememberMe.value || !userPrefs.isEncryptionAvailable) {
                    userPrefs.clearCredentials()
                }
                val msg = error.message ?: "ورود به سامانه با خطا مواجه شد"
                _errorMessage.value = msg
                AppLogger.logWarn(
                    component = "AuthViewModel",
                    operation = "LoginFailure",
                    message = "Login rejected or failed: $msg; auto-refreshing captcha",
                    throwable = error,
                    errorCategory = "LOGIN_FAILED"
                )
                // Refresh captcha on failure as ASP.NET invalidates the previous captcha
                refreshCaptcha()
            }

            _isLoading.value = false
        }
    }

    fun onLogout() {
        cancelCaptchaSolveAndClearStatus(clearSoftMessage = true)
        captchaImageVersion++
        _captchaInput.value = ""
        _errorMessage.value = null
        // Always clear transient in-memory credentials and password visibility on logout
        _username.value = ""
        _password.value = ""
        _isPasswordVisible.value = false
        isBiometricUnlockedForCurrentForm = false

        if (!_rememberMe.value || !userPrefs.isEncryptionAvailable) {
            userPrefs.clearCredentials()
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            _canQuickFillSavedCredentials.value = false
            suppressAutoFillAfterLogout = false
        } else if (userPrefs.isBiometricLockEnabled) {
            val canAuth = biometricCapabilityChecker.canAuthenticate(getApplication())
            val hasSaved = userPrefs.hasSavedCredentials()
            _requiresBiometricUnlock.value = hasSaved && canAuth
            _biometricUnavailableWhileLocked.value = hasSaved && !canAuth
            _canQuickFillSavedCredentials.value = false
            suppressAutoFillAfterLogout = false
        } else {
            val hasSaved = userPrefs.hasSavedCredentials()
            _requiresBiometricUnlock.value = false
            _biometricUnavailableWhileLocked.value = false
            suppressAutoFillAfterLogout = hasSaved
            _canQuickFillSavedCredentials.value = hasSaved
        }
        loadLoginPage()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun syncAutoCaptchaPreferenceState() {
        val enabled = userPrefs.isAutoCaptchaEnabled
        val previouslyEnabled = _isAutoCaptchaEnabled.value
        _isAutoCaptchaEnabled.value = enabled
        if (!enabled) {
            captchaSolveJob?.cancel()
            captchaSolveJob = null
            _isSolvingCaptcha.value = false
            if (previouslyEnabled) {
                _captchaSoftMessage.value = null
            }
            return
        }
        val currentBytes = _pageState.value.captchaBytes
        if (currentBytes != null &&
            currentBytes.isNotEmpty() &&
            _captchaInput.value.isEmpty() &&
            processedCaptchaVersion != captchaImageVersion &&
            captchaSolveJob?.isActive != true
        ) {
            maybeSolveCaptcha(currentBytes, captchaImageVersion)
        }
    }

    private fun cancelCaptchaSolveAndClearStatus(clearSoftMessage: Boolean) {
        captchaSolveJob?.cancel()
        captchaSolveJob = null
        _isSolvingCaptcha.value = false
        _isCaptchaAutoFilled.value = false
        if (clearSoftMessage) {
            _captchaSoftMessage.value = null
        }
    }

    private fun getOrCreateCaptchaSolver(): CaptchaSolver {
        return cachedCaptchaSolver ?: captchaSolverFactory().also { cachedCaptchaSolver = it }
    }

    private fun maybeSolveCaptcha(imageBytes: ByteArray, version: Long) {
        val enabled = userPrefs.isAutoCaptchaEnabled
        _isAutoCaptchaEnabled.value = enabled
        if (!enabled || imageBytes.isEmpty()) {
            return
        }
        if (processedCaptchaVersion == version) {
            return
        }
        processedCaptchaVersion = version

        captchaSolveJob?.cancel()
        captchaSolveJob = viewModelScope.launch {
            _isSolvingCaptcha.value = true
            try {
                val result = getOrCreateCaptchaSolver().solve(imageBytes)
                if (!isActive || version != captchaImageVersion) {
                    return@launch
                }
                when (result) {
                    is CaptchaResult.Success -> {
                        val validDigits = result.digits.filter { it.isDigit() }.take(4)
                        if (_captchaInput.value.isEmpty() && validDigits.length == 4) {
                            _captchaInput.value = validDigits
                            _isCaptchaAutoFilled.value = true
                            _captchaSoftMessage.value = null
                        }
                    }
                    is CaptchaResult.NotConfident,
                    is CaptchaResult.Error -> {
                        _isCaptchaAutoFilled.value = false
                        if (_captchaInput.value.isEmpty()) {
                            _captchaSoftMessage.value = CAPTCHA_SOLVE_FAILED_MESSAGE
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                if (version == captchaImageVersion) {
                    _isSolvingCaptcha.value = false
                }
            }
        }
    }

    override fun onCleared() {
        loadPageJob?.cancel()
        refreshCaptchaJob?.cancel()
        captchaSolveJob?.cancel()
        super.onCleared()
    }

    companion object {
        internal const val CAPTCHA_SOLVE_FAILED_MESSAGE = "تشخیص ممکن نشد، دستی وارد کنید"
    }
}
