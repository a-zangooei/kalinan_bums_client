package com.example.data.repository

import com.example.data.crypto.RsaEncryptor
import com.example.data.logger.AppLogger
import com.example.data.logger.LogContext
import com.example.data.logger.LogEntry
import com.example.data.logger.LogLevel
import com.example.data.model.LoginPageState
import com.example.data.model.UserSession
import com.example.data.model.WeeklyMenu
import com.example.data.network.CullinanHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

enum class LogoutStrategy {
    DIRECT_PAGE_STATE,
    DELTA_PATCHED,
    DEFAULT_FALLBACK,
    ALREADY_LOGGED_OUT,
    LOCAL_ONLY
}

data class LogoutOutcome(
    val serverConfirmed: Boolean,
    val alreadyLoggedOut: Boolean = false,
    val strategy: LogoutStrategy,
    val sourcePagePath: String? = null,
    val httpStatus: Int? = null,
    val errorMessage: String? = null
)

class AuthRepository(
    private val pageTracker: com.example.data.network.MasterPageTracker = CullinanHttpClient.masterPageTracker
) {

    companion object {
        // Shared across AuthRepository and FoodBasketRepository so login, logout, and basket transactions serialize cleanly
        internal val sessionMutex get() = CullinanHttpClient.portalSessionMutex
    }

    suspend fun fetchLoginPage(): Result<LoginPageState> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("auth_init")
        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.INFO,
                component = "AuthRepository",
                operation = "FetchLoginPage",
                flowId = flowId,
                message = "Initiating fetch of login page"
            )
        )

        sessionMutex.withLock {
            try {
                // Start with a clean session state & cookie jar so pre-login state cannot collide with old sessions
                pageTracker.clear("fetch_login_page")
                CullinanHttpClient.cookieJar.clear()

                val request = Request.Builder()
                    .url(CullinanHttpClient.LOGIN_URL)
                    .get()
                    .tag(LogContext::class.java, LogContext(flowId, "FetchLoginPage"))
                    .build()

                var isSuccessful = false
                var responseCode = 0
                var html = ""
                CullinanHttpClient.executeCall(request).use { response ->
                    isSuccessful = response.isSuccessful
                    responseCode = response.code
                    if (isSuccessful) {
                        html = response.body?.string() ?: ""
                    }
                }
                if (!isSuccessful) {
                    AppLogger.logError("AuthRepository", "Fetch login page failed with HTTP $responseCode", flowId = flowId)
                    return@withLock Result.failure(Exception("خطا در برقراری ارتباط با سرور دانشگاه ($responseCode)"))
                }

                // Fetch live captcha with the exact same session
                val captchaBytes = fetchCaptchaBytesInternal(flowId)
                if (captchaBytes == null || captchaBytes.isEmpty()) {
                    AppLogger.logWarn(
                        component = "AuthRepository",
                        operation = "FetchLoginCaptchaMissing",
                        message = "Login page loaded but initial captcha image was empty or failed",
                        flowId = flowId,
                        errorCategory = "CAPTCHA_EMPTY"
                    )
                }

                Result.success(AuthHtmlParser.parseLoginPageState(html, captchaBytes, flowId))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.logError("AuthRepository", "Fetch login page exception: ${e.message}", e, flowId = flowId, operation = "FetchLoginPageError", errorCategory = "AUTH_INIT_EXCEPTION")
                Result.failure(e)
            }
        }
    }

    suspend fun refreshCaptcha(): Result<ByteArray> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("captcha")
        sessionMutex.withLock {
            try {
                val bytes = fetchCaptchaBytesInternal(flowId)
                if (bytes != null) {
                    Result.success(bytes)
                } else {
                    AppLogger.logError("AuthRepository", "Failed to retrieve fresh captcha bytes", flowId = flowId)
                    Result.failure(Exception("دریافت تصویر کد امنیتی از سرور ناموفق بود"))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.logError("AuthRepository", "Captcha refresh exception: ${e.message}", e, flowId = flowId)
                Result.failure(e)
            }
        }
    }

    suspend fun login(
        usernamePlain: String,
        passwordPlain: String,
        captchaInput: String,
        pageState: LoginPageState
    ): Result<UserSession> = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("login")
        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.INFO,
                component = "AuthRepository",
                operation = "LoginAttempt",
                flowId = flowId,
                message = "Initiating user login authentication attempt"
            )
        )

        sessionMutex.withLock {
            try {
                if (pageState.publicKeyPem.isBlank()) {
                    AppLogger.logError("AuthRepository", "Public key missing from page state", flowId = flowId)
                    return@withLock Result.failure(Exception("کلید عمومی رمزنگاری از سرور دریافت نشده است"))
                }

                // RSA encrypt username and password using public key from server
                val encryptedUsername = RsaEncryptor.encryptWithPem(usernamePlain.trim(), pageState.publicKeyPem)
                val encryptedPassword = RsaEncryptor.encryptWithPem(passwordPlain.trim(), pageState.publicKeyPem)

                val formBody = AuthFormBuilder.buildLoginFormBody(
                    encryptedUsername = encryptedUsername,
                    encryptedPassword = encryptedPassword,
                    captchaInput = captchaInput,
                    pageState = pageState
                )

                val request = Request.Builder()
                    .url(CullinanHttpClient.LOGIN_URL)
                    .post(formBody)
                    .header("Referer", CullinanHttpClient.LOGIN_URL)
                    .header("Origin", CullinanHttpClient.BASE_URL)
                    .tag(LogContext::class.java, LogContext(flowId, "LoginPost"))
                    .build()

                var statusCode = 0
                var location = ""
                var bodyString = ""
                CullinanHttpClient.executeCallNoRedirect(request).use { response ->
                    statusCode = response.code
                    location = response.header("Location") ?: ""
                    bodyString = response.body?.string() ?: ""
                }

                // Detection of successful login strictly based on 302 Location header:
                val isRedirect = statusCode in 300..399 || location.isNotBlank()
                val isMessagesRedirect = location.contains("MyMessage.aspx", ignoreCase = true)
                val isSurveyRedirect = location.contains("Survey", ignoreCase = true)
                val isDashboardRedirect = location.contains("Default.aspx", ignoreCase = true) ||
                    location.contains("Reservation", ignoreCase = true) ||
                    location.contains("/Master/", ignoreCase = true)

                if (isRedirect && (isMessagesRedirect || isDashboardRedirect || isSurveyRedirect)) {
                    AppLogger.log(
                        LogEntry(
                            timestamp = "",
                            level = LogLevel.INFO,
                            component = "AuthRepository",
                            operation = "LoginSuccess",
                            flowId = flowId,
                            message = "User successfully authenticated (HTTP $statusCode -> Location: $location, hasUnreadMessages: $isMessagesRedirect, hasMandatorySurvey: $isSurveyRedirect)"
                        )
                    )

                    Result.success(
                        UserSession(
                            isLoggedIn = true,
                            username = usernamePlain,
                            studentNumber = usernamePlain,
                            nationalId = "",
                            hasUnreadMessages = isMessagesRedirect,
                            hasMandatorySurvey = isSurveyRedirect
                        )
                    )
                } else {
                    val errorMsg = AuthHtmlParser.extractLoginRejectionMessage(bodyString)

                    AppLogger.log(
                        LogEntry(
                            timestamp = "",
                            level = LogLevel.WARN,
                            component = "AuthRepository",
                            operation = "LoginFailed",
                            flowId = flowId,
                            errorCategory = "AUTH_REJECTED",
                            message = "Authentication rejected (HTTP $statusCode, Location: $location): $errorMsg"
                        )
                    )

                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.logError("AuthRepository", "Login attempt exception: ${e.message}", e, flowId = flowId)
                Result.failure(e)
            }
        }
    }

    suspend fun logoutFromServer(currentMenu: WeeklyMenu? = null): Result<Boolean> {
        val outcome = logoutDetailed(currentMenu)
        return if (outcome.serverConfirmed) {
            Result.success(true)
        } else {
            Result.failure(Exception(outcome.errorMessage ?: "خروج در سمت سرور تأیید نشد."))
        }
    }

    suspend fun logoutDetailed(currentMenu: WeeklyMenu? = null): LogoutOutcome = withContext(Dispatchers.IO) {
        val flowId = AppLogger.generateFlowId("logout")
        val flowStartMs = System.currentTimeMillis()

        sessionMutex.withLock {
            val tracked = pageTracker.currentState()
            val trackedPath = tracked?.pageUrl?.toHttpUrlOrNull()?.encodedPath
            val hasMenuFallback = !currentMenu?.viewState.isNullOrBlank()

            AppLogger.log(
                LogEntry(
                    timestamp = "",
                    level = LogLevel.INFO,
                    component = "AuthRepository",
                    operation = "LogoutInitiated",
                    flowId = flowId,
                    message = "Initiating universal server-side logout (trackedPath=${trackedPath ?: "NONE"}, trackedSource=${tracked?.source?.name ?: "NONE"}, hasLegacyMenuState=$hasMenuFallback)"
                )
            )

            try {
                // Attempt 1: Build and POST logout dynamically from latest tracked MasterPageState (direct or delta-patched)
                if (tracked != null) {
                    val primaryStrategy = if (tracked.source == com.example.data.network.MasterPageSource.DELTA_PATCHED) {
                        LogoutStrategy.DELTA_PATCHED
                    } else {
                        LogoutStrategy.DIRECT_PAGE_STATE
                    }
                    val built = LogoutFormParser.buildLogoutPayload(
                        pageUrl = tracked.pageUrl,
                        html = tracked.html,
                        deltaHiddenFields = tracked.deltaHiddenFields,
                        deltaUpdatePanels = tracked.deltaUpdatePanels
                    )
                    if (built != null) {
                        val ageMs = (System.currentTimeMillis() - tracked.updatedAtMs).coerceAtLeast(0L)
                        AppLogger.log(
                            LogEntry(
                                timestamp = "",
                                level = LogLevel.INFO,
                                component = "AuthRepository",
                                operation = "LogoutStrategySelected",
                                flowId = flowId,
                                message = "Selected strategy=${primaryStrategy.name} on path=${trackedPath ?: tracked.pageUrl} (stateAgeMs=$ageMs, deltaFields=${tracked.deltaHiddenFields.keys.joinToString().ifBlank { "none" }})"
                            )
                        )
                        logBuiltLogoutPayload(flowId = flowId, built = built, isCachedState = true)

                        val primaryResult = executeLogoutPost(
                            built = built,
                            flowId = flowId,
                            operationName = "LogoutPost"
                        )
                        if (primaryResult.confirmed) {
                            pageTracker.clear("logout_confirmed")
                            val totalDur = System.currentTimeMillis() - flowStartMs
                            AppLogger.log(
                                LogEntry(
                                    timestamp = "",
                                    level = LogLevel.INFO,
                                    component = "AuthRepository",
                                    operation = "ServerLogoutSuccess",
                                    flowId = flowId,
                                    durationMs = totalDur,
                                    http = com.example.data.logger.HttpDiagnostics(
                                        method = "POST",
                                        url = built.postUrl,
                                        statusCode = primaryResult.statusCode,
                                        contentLength = primaryResult.discardedBodyBytes.takeIf { it >= 0L },
                                        redirectLocation = primaryResult.location,
                                        isNoRedirect = true
                                    ),
                                    message = "Server confirmed logout via ${primaryStrategy.name} from ${trackedPath ?: built.postUrl} (HTTP ${primaryResult.statusCode} -> ${primaryResult.location}, discardedBodyBytes=${primaryResult.discardedBodyBytes})"
                                )
                            )
                            return@withLock LogoutOutcome(
                                serverConfirmed = true,
                                alreadyLoggedOut = false,
                                strategy = primaryStrategy,
                                sourcePagePath = trackedPath,
                                httpStatus = primaryResult.statusCode
                            )
                        }

                        AppLogger.logWarn(
                            component = "AuthRepository",
                            operation = "LogoutFallbackTriggered",
                            message = "Primary logout POST from ${trackedPath ?: built.postUrl} returned HTTP ${primaryResult.statusCode} (Location='${primaryResult.location}'); executing single-hop Default.aspx fallback",
                            flowId = flowId,
                            errorCategory = "LOGOUT_NON_302_PRIMARY"
                        )
                    } else {
                        AppLogger.logWarn(
                            component = "AuthRepository",
                            operation = "LogoutFallbackTriggered",
                            message = "Tracked page at ${trackedPath ?: "unknown"} lacked valid Master logout form; executing single-hop Default.aspx fallback",
                            flowId = flowId,
                            errorCategory = "LOGOUT_INVALID_TRACKED_DOM"
                        )
                    }
                } else {
                    AppLogger.logInfo(
                        component = "AuthRepository",
                        operation = "LogoutFallbackTriggered",
                        message = "No tracked MasterPageState in memory; executing lightweight GET /Master/Default.aspx fallback",
                        flowId = flowId
                    )
                }

                // Attempt 2: Single-hop fallback via lightweight GET /Master/Default.aspx (~13.5 KB)
                executeDefaultFallbackLogout(flowId = flowId, flowStartMs = flowStartMs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val totalDur = System.currentTimeMillis() - flowStartMs
                AppLogger.logError(
                    tag = "AuthRepository",
                    message = "Server logout failed with network/runtime exception (${e.javaClass.simpleName}): ${e.message}",
                    throwable = e,
                    flowId = flowId,
                    operation = "ServerLogoutError",
                    errorCategory = "LOGOUT_NETWORK_ERROR"
                )
                LogoutOutcome(
                    serverConfirmed = false,
                    alreadyLoggedOut = false,
                    strategy = LogoutStrategy.LOCAL_ONLY,
                    sourcePagePath = trackedPath,
                    errorMessage = e.message ?: "خطا در ارتباط با سرور هنگام خروج"
                ).also {
                    AppLogger.logDebug(
                        component = "AuthRepository",
                        operation = "LogoutLocalFallbackNotice",
                        message = "Proceeding to unconditional local session cleanup after error (${totalDur}ms)",
                        flowId = flowId
                    )
                }
            }
        }
    }

    private data class LogoutPostResponse(
        val statusCode: Int,
        val location: String,
        val discardedBodyBytes: Long,
        val confirmed: Boolean
    )

    private fun executeLogoutPost(
        built: LogoutFormParser.BuiltLogoutPayload,
        flowId: String,
        operationName: String
    ): LogoutPostResponse {
        val request = Request.Builder()
            .url(built.postUrl)
            .post(built.formBody)
            .header("Origin", built.originUrl)
            .header("Referer", built.refererUrl)
            .tag(LogContext::class.java, LogContext(flowId, operationName))
            .build()

        var statusCode = 0
        var location = ""
        var discardedBodyBytes = -1L

        CullinanHttpClient.executeCallNoRedirect(request).use { response ->
            statusCode = response.code
            location = response.header("Location") ?: ""
            discardedBodyBytes = response.body?.contentLength() ?: -1L
            // Privacy invariant: NEVER read response.body when status is 3xx (contains 12KB-114KB source PII HTML)
        }

        val confirmed = statusCode in 300..399 && location.contains("Login.aspx", ignoreCase = true)
        return LogoutPostResponse(
            statusCode = statusCode,
            location = location,
            discardedBodyBytes = discardedBodyBytes,
            confirmed = confirmed
        )
    }

    private fun executeDefaultFallbackLogout(flowId: String, flowStartMs: Long): LogoutOutcome {
        val defaultUrl = CullinanHttpClient.DEFAULT_PAGE_URL
        val getReq = Request.Builder()
            .url(defaultUrl)
            .get()
            .header("Referer", CullinanHttpClient.BASE_URL)
            .tag(LogContext::class.java, LogContext(flowId, "LogoutFallbackGet"))
            .build()

        var getCode = 0
        var finalUrl = defaultUrl
        var fallbackHtml = ""
        var isGetSuccessful = false

        CullinanHttpClient.executeCall(getReq).use { getResp ->
            getCode = getResp.code
            finalUrl = getResp.request.url.toString()
            isGetSuccessful = getResp.isSuccessful
            if (finalUrl.contains("Login.aspx", ignoreCase = true)) {
                // Already redirected to Login.aspx: session is already closed on server; do not read body
            } else if (isGetSuccessful) {
                fallbackHtml = getResp.body?.string() ?: ""
            }
        }

        val finalPath = finalUrl.toHttpUrlOrNull()?.encodedPath

        if (finalUrl.contains("Login.aspx", ignoreCase = true)) {
            pageTracker.clear("already_logged_out_redirect")
            val totalDur = System.currentTimeMillis() - flowStartMs
            AppLogger.log(
                LogEntry(
                    timestamp = "",
                    level = LogLevel.INFO,
                    component = "AuthRepository",
                    operation = "LogoutAlreadyLoggedOut",
                    flowId = flowId,
                    durationMs = totalDur,
                    http = com.example.data.logger.HttpDiagnostics(
                        method = "GET",
                        url = defaultUrl,
                        statusCode = getCode,
                        finalUrl = finalUrl
                    ),
                    message = "Fallback GET /Master/Default.aspx redirected to Login.aspx ($finalPath); server session already closed (AlreadyLoggedOut)"
                )
            )
            return LogoutOutcome(
                serverConfirmed = true,
                alreadyLoggedOut = true,
                strategy = LogoutStrategy.ALREADY_LOGGED_OUT,
                sourcePagePath = finalPath,
                httpStatus = getCode
            )
        }

        if (!isGetSuccessful) {
            AppLogger.logWarn(
                component = "AuthRepository",
                operation = "LogoutFallbackGetFailed",
                message = "Fallback GET /Master/Default.aspx failed with HTTP $getCode (finalPath=$finalPath)",
                flowId = flowId,
                errorCategory = "LOGOUT_FALLBACK_GET_HTTP_$getCode"
            )
            return LogoutOutcome(
                serverConfirmed = false,
                strategy = LogoutStrategy.DEFAULT_FALLBACK,
                sourcePagePath = finalPath,
                httpStatus = getCode,
                errorMessage = "دریافت صفحه بازگشت خروج با خطا مواجه شد (کد: $getCode)"
            )
        }

        val builtFallback = LogoutFormParser.buildLogoutPayload(pageUrl = finalUrl, html = fallbackHtml)
        if (builtFallback == null) {
            val isLoginDom = fallbackHtml.contains("txtUsernamePlain", ignoreCase = true) ||
                fallbackHtml.contains("publicKeytxt", ignoreCase = true)
            if (isLoginDom) {
                pageTracker.clear("already_logged_out_dom")
                val totalDur = System.currentTimeMillis() - flowStartMs
                AppLogger.log(
                    LogEntry(
                        timestamp = "",
                        level = LogLevel.INFO,
                        component = "AuthRepository",
                        operation = "LogoutAlreadyLoggedOut",
                        flowId = flowId,
                        durationMs = totalDur,
                        message = "Fallback GET returned Login DOM at $finalPath; server session already closed (AlreadyLoggedOut)"
                    )
                )
                return LogoutOutcome(
                    serverConfirmed = true,
                    alreadyLoggedOut = true,
                    strategy = LogoutStrategy.ALREADY_LOGGED_OUT,
                    sourcePagePath = finalPath,
                    httpStatus = getCode
                )
            }

            AppLogger.logWarn(
                component = "AuthRepository",
                operation = "LogoutFallbackNoForm",
                message = "Fallback page at $finalPath (len=${fallbackHtml.length}) did not contain valid Master logout form",
                flowId = flowId,
                errorCategory = "LOGOUT_FALLBACK_MISSING_FORM"
            )
            return LogoutOutcome(
                serverConfirmed = false,
                strategy = LogoutStrategy.DEFAULT_FALLBACK,
                sourcePagePath = finalPath,
                httpStatus = getCode,
                errorMessage = "ساختار فرم خروج در صفحه بازگشت یافت نشد."
            )
        }

        AppLogger.logDebug(
            component = "AuthRepository",
            operation = "LogoutFallbackPageLoaded",
            message = "Loaded fallback Master page path=$finalPath (len=${fallbackHtml.length}, gen=${builtFallback.viewStateGenerator ?: "N/A"}, target=${builtFallback.eventTarget})",
            flowId = flowId
        )
        logBuiltLogoutPayload(flowId = flowId, built = builtFallback, isCachedState = false)

        val fallbackPostResult = executeLogoutPost(
            built = builtFallback,
            flowId = flowId,
            operationName = "LogoutFallbackPost"
        )
        val totalDur = System.currentTimeMillis() - flowStartMs

        return if (fallbackPostResult.confirmed) {
            pageTracker.clear("logout_fallback_confirmed")
            AppLogger.log(
                LogEntry(
                    timestamp = "",
                    level = LogLevel.INFO,
                    component = "AuthRepository",
                    operation = "ServerLogoutSuccess",
                    flowId = flowId,
                    durationMs = totalDur,
                    http = com.example.data.logger.HttpDiagnostics(
                        method = "POST",
                        url = builtFallback.postUrl,
                        statusCode = fallbackPostResult.statusCode,
                        contentLength = fallbackPostResult.discardedBodyBytes.takeIf { it >= 0L },
                        redirectLocation = fallbackPostResult.location,
                        isNoRedirect = true
                    ),
                    message = "Server confirmed logout via DEFAULT_FALLBACK from $finalPath (HTTP ${fallbackPostResult.statusCode} -> ${fallbackPostResult.location}, discardedBodyBytes=${fallbackPostResult.discardedBodyBytes})"
                )
            )
            LogoutOutcome(
                serverConfirmed = true,
                alreadyLoggedOut = false,
                strategy = LogoutStrategy.DEFAULT_FALLBACK,
                sourcePagePath = finalPath,
                httpStatus = fallbackPostResult.statusCode
            )
        } else {
            AppLogger.log(
                LogEntry(
                    timestamp = "",
                    level = LogLevel.WARN,
                    component = "AuthRepository",
                    operation = "ServerLogoutWarning",
                    flowId = flowId,
                    durationMs = totalDur,
                    errorCategory = "LOGOUT_NON_302",
                    http = com.example.data.logger.HttpDiagnostics(
                        method = "POST",
                        url = builtFallback.postUrl,
                        statusCode = fallbackPostResult.statusCode,
                        redirectLocation = fallbackPostResult.location,
                        isNoRedirect = true
                    ),
                    message = "Fallback logout POST from $finalPath returned non-redirect status ${fallbackPostResult.statusCode} (Location='${fallbackPostResult.location}')"
                )
            )
            LogoutOutcome(
                serverConfirmed = false,
                strategy = LogoutStrategy.DEFAULT_FALLBACK,
                sourcePagePath = finalPath,
                httpStatus = fallbackPostResult.statusCode,
                errorMessage = "سرور ریدایرکت خروج صادر نکرد (کد: ${fallbackPostResult.statusCode})"
            )
        }
    }

    private fun logBuiltLogoutPayload(
        flowId: String,
        built: LogoutFormParser.BuiltLogoutPayload,
        isCachedState: Boolean
    ) {
        val actionPath = built.postUrl.toHttpUrlOrNull()?.encodedPath ?: built.postUrl
        val refererPath = built.refererUrl.toHttpUrlOrNull()?.encodedPath ?: built.refererUrl
        AppLogger.log(
            LogEntry(
                timestamp = "",
                level = LogLevel.DEBUG,
                component = "AuthRepository",
                operation = "LogoutFormBuilt",
                flowId = flowId,
                aspnet = com.example.data.logger.AspNetDiagnostics(
                    viewStateLength = built.viewStateLength,
                    viewStateFingerprint = built.viewStateFingerprint,
                    viewStateChunksCount = built.viewStateChunksCount,
                    viewStateGenerator = built.viewStateGenerator,
                    hasEventValidation = built.hasEventValidation,
                    eventValidationLength = built.eventValidationLength,
                    eventTarget = built.eventTarget,
                    formFieldCount = built.fieldCount,
                    isCachedState = isCachedState
                ),
                message = "Built logout POST payload (refererPath=$refererPath, actionPath=$actionPath, formId=${built.formId}, fields=${built.fieldCount}, bytes=${built.payloadByteSize}, excluded=${built.excludedControlsCount}, excludedImagesOrButtons=${built.excludedImageOrButtonCount}, defaultTarget=${built.usedDefaultTarget}, keys=[${built.formKeys.joinToString(", ")}])"
            )
        )
    }

    private suspend fun fetchCaptchaBytesInternal(flowId: String? = null): ByteArray? = withContext(Dispatchers.IO) {
        val effFlowId = flowId ?: AppLogger.generateFlowId("cap")
        try {
            val url = "${CullinanHttpClient.CAPTCHA_URL}?r=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Referer", CullinanHttpClient.LOGIN_URL)
                .tag(LogContext::class.java, LogContext(effFlowId, "FetchCaptcha"))
                .build()

            CullinanHttpClient.executeCall(request).use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        AppLogger.logWarn(
                            component = "AuthRepository",
                            operation = "FetchCaptchaEmpty",
                            message = "Captcha HTTP ${response.code} returned empty byte array",
                            flowId = effFlowId,
                            errorCategory = "CAPTCHA_EMPTY_BODY"
                        )
                    } else {
                        AppLogger.logDebug(
                            component = "AuthRepository",
                            operation = "FetchCaptchaSuccess",
                            message = "Fetched captcha image (${bytes.size} bytes)",
                            flowId = effFlowId
                        )
                    }
                    bytes
                } else {
                    AppLogger.logWarn(
                        component = "AuthRepository",
                        operation = "FetchCaptchaHttpError",
                        message = "Captcha request failed with HTTP ${response.code}",
                        flowId = effFlowId,
                        errorCategory = "CAPTCHA_HTTP_${response.code}"
                    )
                    null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.logError(
                tag = "AuthRepository",
                message = "Captcha fetch exception: ${e.message}",
                throwable = e,
                flowId = effFlowId,
                operation = "FetchCaptchaException",
                errorCategory = "CAPTCHA_NETWORK_ERROR"
            )
            null
        }
    }
}
