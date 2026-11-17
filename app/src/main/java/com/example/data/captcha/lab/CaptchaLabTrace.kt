package com.example.data.captcha.lab

import com.example.data.captcha.preprocess.GrayscaleImage
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.solver.CaptchaFrame
import com.example.data.captcha.solver.OcrElement

/**
 * عکس لحظه‌ای پیکسلی ARGB فریم اول رمزگشایی‌شده برای بایگانی تصویر رنگی/اصلی `01_decoded.png`.
 */
class DecodedArgbSnapshot(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray
)

/**
 * داده یک مرحله از خط لوله پیش‌پردازش پیش از ذخیره ناهم‌زمان روی دیسک.
 */
data class CapturedStageBuffer(
    val stage: CaptchaStageKind,
    val status: CaptchaStageStatus,
    val width: Int? = null,
    val height: Int? = null,
    val argbSnapshot: DecodedArgbSnapshot? = null,
    val grayscaleImage: GrayscaleImage? = null,
    val note: String? = null
)

/**
 * نتیجه یک پیش‌پردازش همراه با تمام مراحل ثبت‌شده برای آزمایشگاه کپچا.
 */
internal data class TracedPreprocessResult(
    val frame: CaptchaFrame?,
    val decodeStatus: String,
    val decodedWidth: Int?,
    val decodedHeight: Int?,
    val mlKitInputWidth: Int?,
    val mlKitInputHeight: Int?,
    val stages: List<CapturedStageBuffer>,
    val failureReason: String?,
    val exceptionType: String? = null,
    val preprocessDurationMs: Long = 0L
)

/**
 * اطلاعات خام یک تلاش حل کپچا پیش از ذخیره‌سازی فایل‌ها روی دیسک.
 */
data class CapturedAttemptPayload(
    val attemptNumber: Int,
    val routeType: String,
    val preprocessConfig: PreprocessConfig,
    val decodeStatus: String,
    val stages: List<CapturedStageBuffer>,
    val mlKitInputWidth: Int?,
    val mlKitInputHeight: Int?,
    val rawOcrFullText: String?,
    val elements: List<OcrElement>,
    val elementCount: Int?,
    val normalizedDigits: String?,
    val validationPassed: Boolean,
    val rejectionCode: String?,
    val failureReason: String?,
    val exceptionType: String? = null,
    val mlKitErrorCode: Int? = null,
    val preprocessDurationMs: Long? = null,
    val ocrDurationMs: Long? = null,
    val totalDurationMs: Long = 0L
)

/**
 * بسته کامل یک کپچا که فقط در صورت فعال‌بودن آزمایشگاه به صف ناهم‌زمان دیسک تحویل داده می‌شود.
 */
data class CapturedCaptchaSession(
    val captchaId: String,
    val capturedAtEpochMs: Long,
    val rawBytes: ByteArray,
    val rawInspection: RawImageInspection,
    val decodedFrameIndex: Int?,
    val decodeStatus: String,
    val rawWidth: Int?,
    val rawHeight: Int?,
    val outcomeCategory: CaptchaLabOutcomeCategory,
    val finalStatus: String,
    val finalDigits: String?,
    val failureReason: String?,
    val lastRejectionCode: String?,
    val exceptionType: String?,
    val mlKitErrorCode: Int?,
    val totalDurationMs: Long,
    val attempts: List<CapturedAttemptPayload>
)

/**
 * رابط مشاهده غیرتهاجمی حل‌گر کپچا؛ در حالت غیرفعال کاملاً خنثی است.
 */
interface CaptchaLabObserver {
    fun isArchivingEnabled(): Boolean
    fun onSolveCompleted(session: CapturedCaptchaSession)
}

object NoOpCaptchaLabObserver : CaptchaLabObserver {
    override fun isArchivingEnabled(): Boolean = false
    override fun onSolveCompleted(session: CapturedCaptchaSession) = Unit
}
