package com.example.data.captcha.solver

import android.content.Context
import com.example.data.logger.AppLogger
import com.example.data.logger.LogSanitizer
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * وضعیت ماژول Unbundled تشخیص متن (OCR) در Google Play Services.
 */
sealed interface OcrModuleState {
    data object Checking : OcrModuleState
    data object Ready : OcrModuleState
    data object NotInstalled : OcrModuleState
    data class Downloading(
        val progressPercent: Int?,
        val bytesDownloaded: Long = 0L,
        val totalBytesToDownload: Long = 0L
    ) : OcrModuleState

    data class PlayServicesUnavailable(
        val connectionResultCode: Int,
        val isUserResolvable: Boolean
    ) : OcrModuleState

    data class Failed(
        val message: String,
        val errorCode: Int? = null
    ) : OcrModuleState

    val isReady: Boolean
        get() = this is Ready

    val isBusy: Boolean
        get() = this is Checking || this is Downloading
}

/**
 * نتیجه بررسی دسترسی به Google Play Services.
 */
internal sealed interface PlayServicesAvailability {
    data object Available : PlayServicesAvailability
    data class Unavailable(
        val connectionResultCode: Int,
        val isUserResolvable: Boolean
    ) : PlayServicesAvailability
}

/**
 * نتیجه درخواست دانلود دستی ماژول OCR از طریق ModuleInstallClient.
 */
internal sealed interface OcrModuleDownloadOutcome {
    data object Completed : OcrModuleDownloadOutcome
    data object Canceled : OcrModuleDownloadOutcome
    data class Failed(
        val errorCode: Int?,
        val technicalDetail: String?
    ) : OcrModuleDownloadOutcome
}

internal data class OcrDownloadProgress(
    val progressPercent: Int?,
    val bytesDownloaded: Long,
    val totalBytesToDownload: Long
)

/**
 * رابط انتزاعی برای مدیریت ماژول اختیاری ML Kit OCR (قابل شبیه‌سازی در تست‌های واحد/Robolectric).
 */
internal interface OcrModuleClient {
    fun checkPlayServicesAvailability(): PlayServicesAvailability
    suspend fun areModulesAvailable(): Boolean
    suspend fun installModule(onProgress: (OcrDownloadProgress) -> Unit): OcrModuleDownloadOutcome
}

/**
 * پیاده‌سازی واقعی با GoogleApiAvailability و Google Play Services ModuleInstallClient.
 */
internal class PlayServicesOcrModuleClient(
    private val appContext: Context
) : OcrModuleClient {

    override fun checkPlayServicesAvailability(): PlayServicesAvailability {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = apiAvailability.isGooglePlayServicesAvailable(appContext)
        return if (resultCode == ConnectionResult.SUCCESS) {
            PlayServicesAvailability.Available
        } else {
            PlayServicesAvailability.Unavailable(
                connectionResultCode = resultCode,
                isUserResolvable = apiAvailability.isUserResolvableError(resultCode)
            )
        }
    }

    override suspend fun areModulesAvailable(): Boolean {
        val moduleInstallClient = ModuleInstall.getClient(appContext)
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val response = suspendCancellableCoroutine { cont ->
                moduleInstallClient.areModulesAvailable(textRecognizer)
                    .addOnSuccessListener { availResponse ->
                        if (cont.isActive) cont.resume(availResponse)
                    }
                    .addOnFailureListener { ex ->
                        if (cont.isActive) cont.resumeWithException(ex)
                    }
            }
            response.areModulesAvailable()
        } finally {
            runCatching { textRecognizer.close() }
        }
    }

    override suspend fun installModule(
        onProgress: (OcrDownloadProgress) -> Unit
    ): OcrModuleDownloadOutcome {
        val moduleInstallClient: ModuleInstallClient = ModuleInstall.getClient(appContext)
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        return try {
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                lateinit var listener: InstallStatusListener

                fun finishOnce(outcome: OcrModuleDownloadOutcome) {
                    if (finished.compareAndSet(false, true)) {
                        runCatching { moduleInstallClient.unregisterListener(listener) }
                        if (cont.isActive) {
                            cont.resume(outcome)
                        }
                    }
                }

                fun failOnce(throwable: Throwable) {
                    if (finished.compareAndSet(false, true)) {
                        runCatching { moduleInstallClient.unregisterListener(listener) }
                        if (cont.isActive) {
                            cont.resumeWithException(throwable)
                        }
                    }
                }

                listener = InstallStatusListener { update: ModuleInstallStatusUpdate ->
                    val progressInfo = update.progressInfo
                    val downloaded = progressInfo?.bytesDownloaded ?: 0L
                    val total = progressInfo?.totalBytesToDownload ?: 0L
                    val percent = if (total > 0L) {
                        ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                    } else {
                        null
                    }

                    when (update.installState) {
                        InstallState.STATE_PENDING,
                        InstallState.STATE_DOWNLOADING,
                        InstallState.STATE_INSTALLING,
                        InstallState.STATE_DOWNLOAD_PAUSED -> {
                            onProgress(
                                OcrDownloadProgress(
                                    progressPercent = percent,
                                    bytesDownloaded = downloaded,
                                    totalBytesToDownload = total
                                )
                            )
                        }
                        InstallState.STATE_COMPLETED -> {
                            finishOnce(OcrModuleDownloadOutcome.Completed)
                        }
                        InstallState.STATE_CANCELED -> {
                            finishOnce(OcrModuleDownloadOutcome.Canceled)
                        }
                        InstallState.STATE_FAILED -> {
                            val errCode = update.errorCode
                            val statusText = runCatching {
                                ModuleInstallStatusCodes.getStatusCodeString(errCode)
                            }.getOrNull()
                            finishOnce(
                                OcrModuleDownloadOutcome.Failed(
                                    errorCode = errCode,
                                    technicalDetail = statusText
                                )
                            )
                        }
                    }
                }

                cont.invokeOnCancellation {
                    finished.set(true)
                    runCatching { moduleInstallClient.unregisterListener(listener) }
                }

                val request = ModuleInstallRequest.newBuilder()
                    .addApi(textRecognizer)
                    .setListener(listener)
                    .build()

                moduleInstallClient.installModules(request)
                    .addOnSuccessListener { response ->
                        if (response.areModulesAlreadyInstalled()) {
                            finishOnce(OcrModuleDownloadOutcome.Completed)
                        }
                    }
                    .addOnFailureListener { ex ->
                        failOnce(ex)
                    }
            }
        } finally {
            runCatching { textRecognizer.close() }
        }
    }
}

/**
 * کنترلر سراسری وضعیت و دانلود دستی ماژول OCR گوگل‌پلی.
 *
 * اصول رفتاری:
 * - هرگز دانلود خودکار در پس‌زمینه یا هنگام ورود/باز کردن برنامه شروع نمی‌کند؛
 *   دانلود تنها با فراخوانی صریح [startManualDownload] از تنظیمات یا آزمایشگاه کپچا انجام می‌شود.
 * - پیش از فراخوانی حل‌گر در [com.example.ui.viewmodel.AuthViewModel]،
 *   با [isModuleReadyForSolve] بدون ایجاد دانلود ضمنی آماده بودن ماژول را بررسی می‌کند.
 */
object OcrModuleController {

    private val _state = MutableStateFlow<OcrModuleState>(OcrModuleState.Checking)
    val state: StateFlow<OcrModuleState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checkMutex = Mutex()
    private var activeDownloadJob: Job? = null

    @Volatile
    private var clientFactory: (Context) -> OcrModuleClient = { ctx ->
        PlayServicesOcrModuleClient(ctx.applicationContext)
    }

    fun refreshStatus(context: Context) {
        if (activeDownloadJob?.isActive == true || _state.value is OcrModuleState.Downloading) {
            return
        }
        val appCtx = context.applicationContext
        scope.launch {
            refreshStatusSuspend(appCtx)
        }
    }

    suspend fun refreshStatusSuspend(context: Context): OcrModuleState {
        if (activeDownloadJob?.isActive == true || _state.value is OcrModuleState.Downloading) {
            return _state.value
        }
        val appCtx = context.applicationContext
        return checkMutex.withLock {
            if (activeDownloadJob?.isActive == true || _state.value is OcrModuleState.Downloading) {
                return@withLock _state.value
            }
            val client = clientFactory(appCtx)
            val availability = runCatching { client.checkPlayServicesAvailability() }.getOrElse { t ->
                val failed = OcrModuleState.Failed(
                    message = mapThrowableToMessage(t),
                    errorCode = extractErrorCode(t)
                )
                _state.value = failed
                return@withLock failed
            }

            if (availability is PlayServicesAvailability.Unavailable) {
                val unavailable = OcrModuleState.PlayServicesUnavailable(
                    connectionResultCode = availability.connectionResultCode,
                    isUserResolvable = availability.isUserResolvable
                )
                _state.value = unavailable
                return@withLock unavailable
            }

            return@withLock try {
                val installed = client.areModulesAvailable()
                val next = if (installed) OcrModuleState.Ready else OcrModuleState.NotInstalled
                _state.value = next
                next
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val errorCode = extractErrorCode(t)
                AppLogger.logWarn(
                    "OcrModule",
                    "CheckAvailabilityFailed",
                    "OCR module availability check failed (code=${errorCode ?: "none"})",
                    t
                )
                val failed = OcrModuleState.Failed(
                    message = mapThrowableToMessage(t),
                    errorCode = errorCode
                )
                _state.value = failed
                failed
            }
        }
    }

    /**
     * بررسی سریع آماده بودن ماژول بدون آغاز دانلود خودکار.
     * در صورتی که ماژول نصب نباشد یا Google Play Services در دسترس نباشد `false` برمی‌گرداند.
     */
    suspend fun isModuleReadyForSolve(context: Context): Boolean {
        if (_state.value is OcrModuleState.Downloading) {
            return false
        }
        val current = refreshStatusSuspend(context)
        return current is OcrModuleState.Ready
    }

    /**
     * شروع دانلود دستی ماژول OCR از Google Play / Google Play Services در پاسخ به کلیک کاربر.
     */
    fun startManualDownload(context: Context) {
        if (activeDownloadJob?.isActive == true) {
            return
        }
        val appCtx = context.applicationContext
        activeDownloadJob = scope.launch {
            val client = clientFactory(appCtx)
            val availability = runCatching { client.checkPlayServicesAvailability() }.getOrElse { t ->
                _state.value = OcrModuleState.Failed(
                    message = mapThrowableToMessage(t),
                    errorCode = extractErrorCode(t)
                )
                return@launch
            }

            if (availability is PlayServicesAvailability.Unavailable) {
                _state.value = OcrModuleState.PlayServicesUnavailable(
                    connectionResultCode = availability.connectionResultCode,
                    isUserResolvable = availability.isUserResolvable
                )
                return@launch
            }

            AppLogger.logInfo("OcrModule", "ManualDownloadStarted", "User requested manual ML Kit OCR module download via Play Services")
            _state.value = OcrModuleState.Downloading(progressPercent = null)

            try {
                when (
                    val outcome = client.installModule { progress ->
                        _state.value = OcrModuleState.Downloading(
                            progressPercent = progress.progressPercent,
                            bytesDownloaded = progress.bytesDownloaded,
                            totalBytesToDownload = progress.totalBytesToDownload
                        )
                    }
                ) {
                    is OcrModuleDownloadOutcome.Completed -> {
                        val verifiedReady = runCatching { client.areModulesAvailable() }.getOrDefault(true)
                        if (verifiedReady) {
                            _state.value = OcrModuleState.Ready
                            AppLogger.logInfo("OcrModule", "ManualDownloadCompleted", "ML Kit OCR module is installed and ready")
                        } else {
                            _state.value = OcrModuleState.Failed(
                                message = "نصب ماژول OCR کامل نشد؛ لطفاً اتصال اینترنت و Google Play را بررسی کرده و دوباره تلاش کنید."
                            )
                        }
                    }
                    is OcrModuleDownloadOutcome.Canceled -> {
                        _state.value = OcrModuleState.Failed(
                            message = "دانلود ماژول OCR لغو شد."
                        )
                        AppLogger.logWarn("OcrModule", "ManualDownloadCanceled", "ML Kit OCR module download canceled")
                    }
                    is OcrModuleDownloadOutcome.Failed -> {
                        val mappedMsg = mapInstallErrorCodeToMessage(outcome.errorCode, outcome.technicalDetail)
                        _state.value = OcrModuleState.Failed(
                            message = mappedMsg,
                            errorCode = outcome.errorCode
                        )
                        AppLogger.logWarn(
                            "OcrModule",
                            "ManualDownloadFailed",
                            "ML Kit OCR module install failed (code=${outcome.errorCode ?: "none"}, detail=${outcome.technicalDetail ?: "none"})"
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val code = extractErrorCode(t)
                AppLogger.logWarn(
                    "OcrModule",
                    "ManualDownloadException",
                    "ML Kit OCR module install threw exception (code=${code ?: "none"})",
                    t
                )
                _state.value = OcrModuleState.Failed(
                    message = mapThrowableToMessage(t),
                    errorCode = code
                )
            }
        }
    }

    internal fun setClientFactoryForTest(factory: ((Context) -> OcrModuleClient)?) {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        clientFactory = factory ?: { ctx -> PlayServicesOcrModuleClient(ctx.applicationContext) }
    }

    internal fun setStateForTest(state: OcrModuleState) {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _state.value = state
    }

    internal fun resetForTest() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        clientFactory = { ctx -> PlayServicesOcrModuleClient(ctx.applicationContext) }
        _state.value = OcrModuleState.Checking
    }

    private fun extractErrorCode(t: Throwable): Int? = when (t) {
        is ApiException -> t.statusCode
        else -> (t.cause as? ApiException)?.statusCode
    }

    private fun mapThrowableToMessage(t: Throwable): String {
        val code = extractErrorCode(t)
        if (code != null) {
            return mapInstallErrorCodeToMessage(code, t.message)
        }
        val safeMsg = t.message?.let { LogSanitizer.sanitizeText(it) }?.take(120)
        return if (!safeMsg.isNullOrBlank()) {
            "خطا در ارتباط با Google Play برای ماژول OCR ($safeMsg)"
        } else {
            "دریافت یا بررسی ماژول OCR از Google Play ناموفق بود."
        }
    }

    private fun mapInstallErrorCodeToMessage(errorCode: Int?, technicalDetail: String?): String {
        return when (errorCode) {
            CommonStatusCodes.NETWORK_ERROR,
            CommonStatusCodes.TIMEOUT -> "خطای شبکه هنگام دریافت ماژول OCR؛ اتصال اینترنت خود را بررسی کنید."
            CommonStatusCodes.CANCELED,
            CommonStatusCodes.INTERRUPTED -> "دانلود ماژول OCR لغو شد."
            CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED -> "برای دریافت ماژول OCR لازم است Google Play Services به‌روزرسانی شود."
            CommonStatusCodes.API_NOT_CONNECTED -> "سرویس نصب ماژول Google Play در این دستگاه پاسخگو نیست."
            46003 -> "حافظه داخلی دستگاه برای دانلود و نصب ماژول OCR کافی نیست." // ModuleInstallStatusCodes.INSUFFICIENT_STORAGE
            46004 -> "دانلود از طریق شبکه موبایل محدود شده است؛ لطفاً با Wi-Fi تلاش کنید." // METERED_NETWORK_NOT_ALLOWED
            46000, 46001, 46002 -> "ماژول OCR در Google Play Store این دستگاه قابل دسترسی نیست (کد $errorCode)."
            else -> {
                val codeSuffix = errorCode?.let { " (کد $it)" } ?: ""
                val detailSuffix = technicalDetail
                    ?.takeIf { it.isNotBlank() && !it.startsWith("unknown status code", ignoreCase = true) }
                    ?.let { " [$it]" }
                    ?: ""
                "دانلود یا راه‌اندازی ماژول OCR از Google Play ناموفق بود$codeSuffix$detailSuffix."
            }
        }
    }
}
