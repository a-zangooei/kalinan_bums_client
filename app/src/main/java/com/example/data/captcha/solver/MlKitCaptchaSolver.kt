package com.example.data.captcha.solver

import android.graphics.Bitmap
import com.example.data.captcha.preprocess.CaptchaPreprocessor
import com.example.data.captcha.preprocess.NoiseRemovalMode
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.preprocess.ThresholdMode
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * قاب تصویر پیش‌پردازش‌شده که امکان آزادسازی قطعی حافظه و جداسازی از اندروید را در تست فراهم می‌کند.
 */
internal interface CaptchaFrame {
    fun release()
}

internal class AndroidBitmapFrame(val bitmap: Bitmap) : CaptchaFrame {
    override fun release() {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

/**
 * جلسه انتزاعی OCR با قابلیت آزادسازی قطعی منابع بومی (close) در پایان کار یا هنگام لغو.
 */
internal interface OcrSession : AutoCloseable {
    suspend fun recognize(frame: CaptchaFrame): OcrOutput
}

/**
 * پیاده‌سازی واقعی موتور ML Kit Text Recognition (مدل Bundled Latin) بدون ذخیره فایل و بدون لاگ.
 */
internal class MlKitOcrSession(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) : OcrSession {

    override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
        val androidFrame = frame as? AndroidBitmapFrame
            ?: throw IllegalArgumentException("Expected AndroidBitmapFrame for ML Kit OCR")
        val inputImage = InputImage.fromBitmap(androidFrame.bitmap, 0)
        val text = awaitTaskWithSafeFrameRelease(recognizer.process(inputImage), androidFrame)
        val elements = mutableListOf<OcrElement>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val rawConfidence = element.confidence
                    // ML Kit 16.0.1 exposes element.confidence (0f..1f); treat negative as unavailable
                    val safeConfidence = if (rawConfidence >= 0f) rawConfidence else null
                    elements.add(OcrElement(text = element.text, confidence = safeConfidence))
                }
            }
        }
        return OcrOutput(fullText = text.text, elements = elements)
    }

    override fun close() {
        runCatching { recognizer.close() }
    }

    private suspend fun awaitTaskWithSafeFrameRelease(
        task: Task<Text>,
        frame: AndroidBitmapFrame
    ): Text = suspendCancellableCoroutine { cont ->
        // Recycle bitmap strictly AFTER ML Kit native worker finishes reading it, even if coroutine cancels early
        task.addOnCompleteListener { completedTask ->
            frame.release()
            if (!cont.isActive) return@addOnCompleteListener
            when {
                completedTask.isCanceled -> cont.cancel()
                completedTask.isSuccessful -> cont.resume(completedTask.result)
                else -> cont.resumeWithException(
                    completedTask.exception ?: IllegalStateException("ML Kit recognition failed")
                )
            }
        }
    }
}

/**
 * پیکربندی حل‌گر ML Kit شامل آستانه اطمینان، تایم‌اوت کل و لیست ثابت پیکربندی‌های تلاش.
 */
data class CaptchaSolverConfig(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val minElementConfidence: Float = CaptchaDigitValidator.DEFAULT_MIN_ELEMENT_CONFIDENCE,
    val primaryConfig: PreprocessConfig = PreprocessConfig(),
    val fallbackConfigs: List<PreprocessConfig> = defaultFallbackConfigs()
) {
    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        require(fallbackConfigs.size <= MAX_FALLBACK_ATTEMPTS) {
            "At most $MAX_FALLBACK_ATTEMPTS fallback attempts are allowed"
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_500L
        const val MAX_FALLBACK_ATTEMPTS = 2

        fun defaultFallbackConfigs(): List<PreprocessConfig> = listOf(
            PreprocessConfig(
                thresholdMode = ThresholdMode.FIXED,
                fixedThreshold = 135,
                noiseRemovalMode = NoiseRemovalMode.MEDIAN,
                filterSize = 3,
                scaleFactor = 3
            ),
            PreprocessConfig(
                thresholdMode = ThresholdMode.OTSU,
                noiseRemovalMode = NoiseRemovalMode.MORPHOLOGICAL_OPEN,
                filterSize = 3,
                scaleFactor = 3
            )
        )
    }
}

/**
 * پیاده‌سازی [CaptchaSolver] با اتصال به پیش‌پردازش فاز ۱۰ و ML Kit Bundled Latin Text Recognition.
 *
 * ویژگی‌ها:
 * - اجرا روی [Dispatchers.Default] با تایم‌اوت کوتاه ۲۵۰۰ میلی‌ثانیه.
 * - بازگشت فوری در صورت استخراج ۴ رقم معتبر و مطمئن در تلاش اول؛
 *   در غیر این صورت بررسی حداکثر ۲ پیکربندی جایگزین و رد خروجی‌های متعارض با [CaptchaResult.NotConfident].
 * - آزادسازی قطعی Bitmapهای میانی و `TextRecognizer` حتی در لغو Coroutine یا Timeout.
 * - بدون ذخیره تصویر روی دیسک و بدون ثبت هیچ‌گونه لاگ از تصویر یا ارقام.
 */
class MlKitCaptchaSolver internal constructor(
    private val solverConfig: CaptchaSolverConfig = CaptchaSolverConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val framePreprocessor: (ByteArray, PreprocessConfig) -> CaptchaFrame? = { bytes, cfg ->
        CaptchaPreprocessor.preprocess(bytes, cfg)?.let { AndroidBitmapFrame(it) }
    },
    private val sessionFactory: () -> OcrSession = { MlKitOcrSession() }
) : CaptchaSolver {

    constructor(solverConfig: CaptchaSolverConfig = CaptchaSolverConfig()) : this(
        solverConfig = solverConfig,
        dispatcher = Dispatchers.Default,
        framePreprocessor = { bytes, cfg ->
            CaptchaPreprocessor.preprocess(bytes, cfg)?.let { AndroidBitmapFrame(it) }
        },
        sessionFactory = { MlKitOcrSession() }
    )

    override suspend fun solve(imageBytes: ByteArray): CaptchaResult {
        if (imageBytes.isEmpty()) {
            return CaptchaResult.Error("داده تصویر کپچا خالی است.")
        }

        return withContext(dispatcher) {
            var session: OcrSession? = null
            try {
                withTimeout(solverConfig.timeoutMs) {
                    val activeSession = sessionFactory().also { session = it }

                    // Attempt 1 (Primary): fast-path immediate Success if 4 confident digits found
                    val firstAttempt = runSingleAttempt(
                        imageBytes = imageBytes,
                        preprocessConfig = solverConfig.primaryConfig,
                        session = activeSession
                    )
                    when (firstAttempt) {
                        is AttemptResult.ValidDigits -> return@withTimeout CaptchaResult.Success(firstAttempt.digits)
                        is AttemptResult.PreprocessFailed -> Unit
                        is AttemptResult.Rejected -> Unit
                    }

                    // Attempts 2..3 (Fallback configs, fixed ceiling)
                    val fallbackConfigs = solverConfig.fallbackConfigs.take(CaptchaSolverConfig.MAX_FALLBACK_ATTEMPTS)
                    if (fallbackConfigs.isEmpty()) {
                        return@withTimeout when (firstAttempt) {
                            is AttemptResult.PreprocessFailed -> CaptchaResult.Error("پیش‌پردازش تصویر کپچا ناموفق بود.")
                            is AttemptResult.Rejected -> CaptchaResult.NotConfident(firstAttempt.reason)
                            is AttemptResult.ValidDigits -> CaptchaResult.Success(firstAttempt.digits)
                        }
                    }

                    val fallbackValidDigits = mutableListOf<String>()
                    var lastRejectionReason: String? = (firstAttempt as? AttemptResult.Rejected)?.reason
                    var preprocessSuccessCount = if (firstAttempt is AttemptResult.PreprocessFailed) 0 else 1

                    for (fallbackConfig in fallbackConfigs) {
                        when (val fallbackResult = runSingleAttempt(imageBytes, fallbackConfig, activeSession)) {
                            is AttemptResult.ValidDigits -> {
                                preprocessSuccessCount++
                                fallbackValidDigits.add(fallbackResult.digits)
                            }
                            is AttemptResult.Rejected -> {
                                preprocessSuccessCount++
                                lastRejectionReason = fallbackResult.reason
                            }
                            is AttemptResult.PreprocessFailed -> Unit
                        }
                    }

                    if (preprocessSuccessCount == 0) {
                        return@withTimeout CaptchaResult.Error("تصویر کپچا قابل پیش‌پردازش نیست.")
                    }

                    val distinctCandidates = fallbackValidDigits.distinct()
                    when {
                        distinctCandidates.isEmpty() ->
                            CaptchaResult.NotConfident(lastRejectionReason ?: "تشخیص ۴ رقم معتبر نامطمئن است.")
                        distinctCandidates.size == 1 ->
                            CaptchaResult.Success(distinctCandidates.first())
                        else ->
                            CaptchaResult.NotConfident("تلاش‌های پیش‌پردازش به دو نتیجه ۴ رقمی متفاوت رسیدند.")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                CaptchaResult.Error("زمان مجاز برای تشخیص خودکار کپچا به پایان رسید.")
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                CaptchaResult.Error("خطا در اجرای موتور تشخیص خودکار کپچا.")
            } finally {
                session?.close()
            }
        }
    }

    private suspend fun runSingleAttempt(
        imageBytes: ByteArray,
        preprocessConfig: PreprocessConfig,
        session: OcrSession
    ): AttemptResult {
        val frame = framePreprocessor(imageBytes, preprocessConfig) ?: return AttemptResult.PreprocessFailed
        var recognizedOutput: OcrOutput? = null
        try {
            recognizedOutput = session.recognize(frame)
        } finally {
            // Idempotent cleanup if OcrSession threw before or during recognition
            frame.release()
        }

        return when (
            val validation = CaptchaDigitValidator.validate(
                output = recognizedOutput,
                minElementConfidence = solverConfig.minElementConfidence
            )
        ) {
            is DigitValidationOutcome.Valid -> AttemptResult.ValidDigits(validation.digits)
            is DigitValidationOutcome.Invalid -> AttemptResult.Rejected(validation.reason)
        }
    }

    private sealed class AttemptResult {
        data class ValidDigits(val digits: String) : AttemptResult()
        data class Rejected(val reason: String) : AttemptResult()
        data object PreprocessFailed : AttemptResult()
    }
}
