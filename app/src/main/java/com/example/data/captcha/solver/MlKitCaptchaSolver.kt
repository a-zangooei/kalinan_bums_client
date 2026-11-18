package com.example.data.captcha.solver

import android.graphics.Bitmap
import com.example.data.captcha.lab.CaptchaLabController
import com.example.data.captcha.lab.CaptchaLabObserver
import com.example.data.captcha.lab.CaptchaLabOutcomeCategory
import com.example.data.captcha.lab.CaptchaStageKind
import com.example.data.captcha.lab.CaptchaStageStatus
import com.example.data.captcha.lab.CapturedAttemptPayload
import com.example.data.captcha.lab.CapturedCaptchaSession
import com.example.data.captcha.lab.CapturedStageBuffer
import com.example.data.captcha.lab.GifFormatInspector
import com.example.data.captcha.lab.TracedPreprocessResult
import com.example.data.captcha.preprocess.CaptchaPreprocessor
import com.example.data.captcha.preprocess.NoiseRemovalMode
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.preprocess.ThresholdMode
import com.example.data.logger.LogSanitizer
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
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
import java.util.UUID
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
    val primaryConfig: PreprocessConfig = defaultPrimaryConfig(),
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

        /**
         * تلاش اول (رتبه ۱ بنچمارک ۱۰۰ کپچا): ارسال مستقیم فریم رنگی رمزگشایی‌شده بدون باینری‌سازی و حذف نویز.
         */
        fun defaultPrimaryConfig(): PreprocessConfig = PreprocessConfig(
            useDecodedColorFrame = true,
            contrastClipPercent = 0f,
            thresholdMode = ThresholdMode.NONE,
            noiseRemovalMode = NoiseRemovalMode.NONE,
            invertOutput = false,
            scaleFactor = 1,
            borderPaddingPx = 0
        )

        /**
         * دو مسیر جایگزین مکمل در صورت عدم اطمینان تلاش اول:
         * ۱) بهبود کنتراست خاکستری بدون باینری‌سازی و بدون حذف نویز (`CONTRAST_ENHANCED`)
         * ۲) آستانه‌گذاری تطبیقی اوتسو بدون فیلتر حذف نویز (جلوگیری از خوردگی خطوط نازک ارقام)
         */
        fun defaultFallbackConfigs(): List<PreprocessConfig> = listOf(
            PreprocessConfig(
                useDecodedColorFrame = false,
                contrastClipPercent = 0.02f,
                thresholdMode = ThresholdMode.NONE,
                noiseRemovalMode = NoiseRemovalMode.NONE,
                invertOutput = false,
                scaleFactor = 1,
                borderPaddingPx = 0
            ),
            PreprocessConfig(
                useDecodedColorFrame = false,
                contrastClipPercent = 0.02f,
                thresholdMode = ThresholdMode.OTSU,
                noiseRemovalMode = NoiseRemovalMode.NONE,
                invertOutput = true,
                scaleFactor = 2,
                borderPaddingPx = 12
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
 * - اتصال اختیاری و غیرتهاجمی به [CaptchaLabObserver] در زمان فعال‌بودن آزمایشگاه کپچا.
 */
class MlKitCaptchaSolver internal constructor(
    private val solverConfig: CaptchaSolverConfig = CaptchaSolverConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val framePreprocessor: (ByteArray, PreprocessConfig) -> CaptchaFrame? = { bytes, cfg ->
        CaptchaPreprocessor.preprocess(bytes, cfg)?.let { AndroidBitmapFrame(it) }
    },
    private val sessionFactory: () -> OcrSession = { MlKitOcrSession() },
    private val labObserver: CaptchaLabObserver = CaptchaLabController,
    private val tracedFramePreprocessor: ((ByteArray, PreprocessConfig) -> TracedPreprocessResult)? = { bytes, cfg ->
        CaptchaPreprocessor.preprocessWithTrace(bytes, cfg)
    }
) : CaptchaSolver {

    constructor(solverConfig: CaptchaSolverConfig = CaptchaSolverConfig()) : this(
        solverConfig = solverConfig,
        dispatcher = Dispatchers.Default,
        framePreprocessor = { bytes, cfg ->
            CaptchaPreprocessor.preprocess(bytes, cfg)?.let { AndroidBitmapFrame(it) }
        },
        sessionFactory = { MlKitOcrSession() },
        labObserver = CaptchaLabController,
        tracedFramePreprocessor = { bytes, cfg ->
            CaptchaPreprocessor.preprocessWithTrace(bytes, cfg)
        }
    )

    override suspend fun solve(imageBytes: ByteArray): CaptchaResult =
        solveWithDiagnostics(imageBytes).result

    override suspend fun solveWithDiagnostics(imageBytes: ByteArray): CaptchaSolveOutcome {
        val startNs = System.nanoTime()
        val capturedAtEpochMs = System.currentTimeMillis()
        fun elapsedMs(): Long = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)

        // Capture is enabled only for attempts started AFTER user switched Captcha Lab ON.
        val captureActive = labObserver.isArchivingEnabled()
        val captchaId = if (captureActive) UUID.randomUUID().toString() else ""
        val rawInspection = if (captureActive) GifFormatInspector.inspect(imageBytes) else null
        val capturedAttempts = if (captureActive) mutableListOf<CapturedAttemptPayload>() else null

        if (imageBytes.isEmpty()) {
            val outcome = CaptchaSolveOutcome(
                result = CaptchaResult.Error("داده تصویر کپچا خالی است."),
                diagnostics = OcrDiagnosticReport(
                    stage = OcrSolveStage.INPUT_VALIDATION,
                    attemptCount = 0,
                    elapsedMs = elapsedMs(),
                    lastRejectionCode = "EMPTY_IMAGE_BYTES"
                )
            )
            if (captureActive && rawInspection != null && labObserver.isArchivingEnabled()) {
                labObserver.onSolveCompleted(
                    CapturedCaptchaSession(
                        captchaId = captchaId,
                        capturedAtEpochMs = capturedAtEpochMs,
                        rawBytes = imageBytes,
                        rawInspection = rawInspection,
                        decodedFrameIndex = null,
                        decodeStatus = "EMPTY_BYTES",
                        rawWidth = null,
                        rawHeight = null,
                        outcomeCategory = CaptchaLabOutcomeCategory.EMPTY_INPUT,
                        finalStatus = "EMPTY_INPUT",
                        finalDigits = null,
                        failureReason = "داده تصویر کپچا خالی است (EMPTY_IMAGE_BYTES)",
                        lastRejectionCode = "EMPTY_IMAGE_BYTES",
                        exceptionType = null,
                        mlKitErrorCode = null,
                        totalDurationMs = outcome.diagnostics.elapsedMs,
                        attempts = emptyList()
                    )
                )
            }
            return outcome
        }

        return withContext(dispatcher) {
            var session: OcrSession? = null
            var activeStage = OcrSolveStage.SESSION_INIT
            var attemptCount = 0
            var lastRejectionCode: String? = null
            var lastElementCount: Int? = null
            var lastExtractedDigitCount: Int? = null

            val tracker = StageTracker(
                onStageChange = { activeStage = it },
                onAttemptStart = { attemptCount++ }
            )

            var finalOutcome: CaptchaSolveOutcome? = null

            try {
                finalOutcome = withTimeout(solverConfig.timeoutMs) {
                    activeStage = OcrSolveStage.SESSION_INIT
                    val activeSession = sessionFactory().also { session = it }

                    // Attempt 1 (Primary): fast-path immediate Success if 4 confident digits found
                    val firstAttempt = runSingleAttempt(
                        imageBytes = imageBytes,
                        preprocessConfig = solverConfig.primaryConfig,
                        session = activeSession,
                        tracker = tracker,
                        attemptNumber = 1,
                        routeType = "PRIMARY",
                        captureActive = captureActive,
                        capturedAttempts = capturedAttempts
                    )
                    when (firstAttempt) {
                        is AttemptResult.ValidDigits -> {
                            return@withTimeout CaptchaSolveOutcome(
                                result = CaptchaResult.Success(firstAttempt.digits),
                                diagnostics = OcrDiagnosticReport(
                                    stage = OcrSolveStage.SUCCESS,
                                    attemptCount = attemptCount,
                                    elapsedMs = elapsedMs(),
                                    elementCount = firstAttempt.elementCount,
                                    extractedDigitCount = CaptchaDigitValidator.REQUIRED_DIGIT_COUNT
                                )
                            )
                        }
                        is AttemptResult.PreprocessFailed -> {
                            lastRejectionCode = "PREPROCESS_NULL"
                        }
                        is AttemptResult.Rejected -> {
                            lastRejectionCode = firstAttempt.rejectionCode
                            lastElementCount = firstAttempt.elementCount
                            lastExtractedDigitCount = firstAttempt.extractedDigitCount
                        }
                    }

                    // Attempts 2..3 (Fallback configs, fixed ceiling)
                    val fallbackConfigs = solverConfig.fallbackConfigs.take(CaptchaSolverConfig.MAX_FALLBACK_ATTEMPTS)
                    if (fallbackConfigs.isEmpty()) {
                        return@withTimeout when (firstAttempt) {
                            is AttemptResult.PreprocessFailed -> CaptchaSolveOutcome(
                                result = CaptchaResult.Error("پیش‌پردازش تصویر کپچا ناموفق بود."),
                                diagnostics = OcrDiagnosticReport(
                                    stage = OcrSolveStage.PREPROCESS,
                                    attemptCount = attemptCount,
                                    elapsedMs = elapsedMs(),
                                    lastRejectionCode = "PREPROCESS_NULL"
                                )
                            )
                            is AttemptResult.Rejected -> CaptchaSolveOutcome(
                                result = CaptchaResult.NotConfident(firstAttempt.reason),
                                diagnostics = OcrDiagnosticReport(
                                    stage = OcrSolveStage.VALIDATE,
                                    attemptCount = attemptCount,
                                    elapsedMs = elapsedMs(),
                                    lastRejectionCode = firstAttempt.rejectionCode,
                                    elementCount = firstAttempt.elementCount,
                                    extractedDigitCount = firstAttempt.extractedDigitCount
                                )
                            )
                            is AttemptResult.ValidDigits -> CaptchaSolveOutcome(
                                result = CaptchaResult.Success(firstAttempt.digits),
                                diagnostics = OcrDiagnosticReport(
                                    stage = OcrSolveStage.SUCCESS,
                                    attemptCount = attemptCount,
                                    elapsedMs = elapsedMs(),
                                    elementCount = firstAttempt.elementCount,
                                    extractedDigitCount = CaptchaDigitValidator.REQUIRED_DIGIT_COUNT
                                )
                            )
                        }
                    }

                    val fallbackValidDigits = mutableListOf<String>()
                    var lastRejectionReason: String? = (firstAttempt as? AttemptResult.Rejected)?.reason
                    var preprocessSuccessCount = if (firstAttempt is AttemptResult.PreprocessFailed) 0 else 1

                    fallbackConfigs.forEachIndexed { idx, fallbackConfig ->
                        when (
                            val fallbackResult = runSingleAttempt(
                                imageBytes = imageBytes,
                                preprocessConfig = fallbackConfig,
                                session = activeSession,
                                tracker = tracker,
                                attemptNumber = idx + 2,
                                routeType = "FALLBACK",
                                captureActive = captureActive,
                                capturedAttempts = capturedAttempts
                            )
                        ) {
                            is AttemptResult.ValidDigits -> {
                                preprocessSuccessCount++
                                fallbackValidDigits.add(fallbackResult.digits)
                                lastElementCount = fallbackResult.elementCount
                                lastExtractedDigitCount = CaptchaDigitValidator.REQUIRED_DIGIT_COUNT
                            }
                            is AttemptResult.Rejected -> {
                                preprocessSuccessCount++
                                lastRejectionReason = fallbackResult.reason
                                lastRejectionCode = fallbackResult.rejectionCode
                                lastElementCount = fallbackResult.elementCount
                                lastExtractedDigitCount = fallbackResult.extractedDigitCount
                            }
                            is AttemptResult.PreprocessFailed -> {
                                if (lastRejectionCode == null) {
                                    lastRejectionCode = "PREPROCESS_NULL"
                                }
                            }
                        }
                    }

                    if (preprocessSuccessCount == 0) {
                        return@withTimeout CaptchaSolveOutcome(
                            result = CaptchaResult.Error("تصویر کپچا قابل پیش‌پردازش نیست."),
                            diagnostics = OcrDiagnosticReport(
                                stage = OcrSolveStage.PREPROCESS,
                                attemptCount = attemptCount,
                                elapsedMs = elapsedMs(),
                                lastRejectionCode = "PREPROCESS_NULL"
                            )
                        )
                    }

                    val distinctCandidates = fallbackValidDigits.distinct()
                    when {
                        distinctCandidates.isEmpty() -> CaptchaSolveOutcome(
                            result = CaptchaResult.NotConfident(lastRejectionReason ?: "تشخیص ۴ رقم معتبر نامطمئن است."),
                            diagnostics = OcrDiagnosticReport(
                                stage = OcrSolveStage.VALIDATE,
                                attemptCount = attemptCount,
                                elapsedMs = elapsedMs(),
                                lastRejectionCode = lastRejectionCode ?: "VALIDATION_REJECTED",
                                elementCount = lastElementCount,
                                extractedDigitCount = lastExtractedDigitCount
                            )
                        )
                        distinctCandidates.size == 1 -> CaptchaSolveOutcome(
                            result = CaptchaResult.Success(distinctCandidates.first()),
                            diagnostics = OcrDiagnosticReport(
                                stage = OcrSolveStage.SUCCESS,
                                attemptCount = attemptCount,
                                elapsedMs = elapsedMs(),
                                elementCount = lastElementCount,
                                extractedDigitCount = CaptchaDigitValidator.REQUIRED_DIGIT_COUNT
                            )
                        )
                        else -> CaptchaSolveOutcome(
                            result = CaptchaResult.NotConfident("تلاش‌های پیش‌پردازش به دو نتیجه ۴ رقمی متفاوت رسیدند."),
                            diagnostics = OcrDiagnosticReport(
                                stage = OcrSolveStage.VALIDATE,
                                attemptCount = attemptCount,
                                elapsedMs = elapsedMs(),
                                lastRejectionCode = "CONFLICTING_CANDIDATES",
                                elementCount = lastElementCount,
                                extractedDigitCount = CaptchaDigitValidator.REQUIRED_DIGIT_COUNT
                            )
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                finalOutcome = CaptchaSolveOutcome(
                    result = CaptchaResult.Error("زمان مجاز برای تشخیص خودکار کپچا به پایان رسید."),
                    diagnostics = OcrDiagnosticReport(
                        stage = OcrSolveStage.TIMEOUT,
                        attemptCount = attemptCount,
                        elapsedMs = elapsedMs(),
                        exceptionType = TimeoutCancellationException::class.java.name,
                        causeChain = "Timed out after ${solverConfig.timeoutMs}ms at stage=${activeStage.name}",
                        lastRejectionCode = "TIMEOUT_AT_${activeStage.name}",
                        elementCount = lastElementCount,
                        extractedDigitCount = lastExtractedDigitCount
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                finalOutcome = CaptchaSolveOutcome(
                    result = CaptchaResult.Error("خطا در اجرای موتور تشخیص خودکار کپچا."),
                    diagnostics = OcrDiagnosticReport(
                        stage = activeStage,
                        attemptCount = attemptCount,
                        elapsedMs = elapsedMs(),
                        exceptionType = t.javaClass.name,
                        mlKitErrorCode = extractMlKitErrorCode(t),
                        causeChain = buildSanitizedCauseChain(t),
                        lastRejectionCode = "EXCEPTION_AT_${activeStage.name}",
                        elementCount = lastElementCount,
                        extractedDigitCount = lastExtractedDigitCount
                    )
                )
            } finally {
                session?.close()
            }

            val resolvedOutcome = finalOutcome!!
            if (captureActive && rawInspection != null && capturedAttempts != null && labObserver.isArchivingEnabled()) {
                emitLabCapture(
                    captchaId = captchaId,
                    capturedAtEpochMs = capturedAtEpochMs,
                    rawBytes = imageBytes,
                    rawInspection = rawInspection,
                    outcome = resolvedOutcome,
                    attempts = capturedAttempts
                )
            }
            resolvedOutcome
        }
    }

    private suspend fun runSingleAttempt(
        imageBytes: ByteArray,
        preprocessConfig: PreprocessConfig,
        session: OcrSession,
        tracker: StageTracker,
        attemptNumber: Int,
        routeType: String,
        captureActive: Boolean,
        capturedAttempts: MutableList<CapturedAttemptPayload>?
    ): AttemptResult {
        val attemptStartNs = System.nanoTime()
        fun attemptElapsedMs(): Long = ((System.nanoTime() - attemptStartNs) / 1_000_000L).coerceAtLeast(0L)

        tracker.onAttemptStart()
        tracker.onStageChange(OcrSolveStage.PREPROCESS)

        val tracedResult: TracedPreprocessResult?
        val frame: CaptchaFrame?
        val preprocessMs: Long

        if (captureActive) {
            val prepStartNs = System.nanoTime()
            try {
                if (tracedFramePreprocessor != null) {
                    tracedResult = tracedFramePreprocessor.invoke(imageBytes, preprocessConfig)
                    frame = tracedResult.frame
                    preprocessMs = tracedResult.preprocessDurationMs
                } else {
                    val plainFrame = framePreprocessor(imageBytes, preprocessConfig)
                    preprocessMs = ((System.nanoTime() - prepStartNs) / 1_000_000L).coerceAtLeast(0L)
                    tracedResult = TracedPreprocessResult(
                        frame = plainFrame,
                        decodeStatus = if (plainFrame != null) "SUCCESS" else "PREPROCESS_NULL",
                        decodedWidth = null,
                        decodedHeight = null,
                        mlKitInputWidth = null,
                        mlKitInputHeight = null,
                        stages = emptyList(),
                        failureReason = if (plainFrame == null) "Preprocess returned null" else null,
                        preprocessDurationMs = preprocessMs
                    )
                    frame = plainFrame
                }
            } catch (ce: CancellationException) {
                if (ce is TimeoutCancellationException) {
                    val elapsedPrep = ((System.nanoTime() - prepStartNs) / 1_000_000L).coerceAtLeast(0L)
                    capturedAttempts?.add(
                        CapturedAttemptPayload(
                            attemptNumber = attemptNumber,
                            routeType = routeType,
                            preprocessConfig = preprocessConfig,
                            decodeStatus = "TIMEOUT",
                            stages = CaptchaStageKind.entries.map {
                                CapturedStageBuffer(it, CaptchaStageStatus.NOT_RUN, note = "Timeout during preprocess")
                            },
                            mlKitInputWidth = null,
                            mlKitInputHeight = null,
                            rawOcrFullText = null,
                            elements = emptyList(),
                            elementCount = null,
                            normalizedDigits = null,
                            validationPassed = false,
                            rejectionCode = "TIMEOUT_AT_PREPROCESS",
                            failureReason = "Timed out during preprocessing",
                            exceptionType = TimeoutCancellationException::class.java.name,
                            preprocessDurationMs = elapsedPrep,
                            ocrDurationMs = null,
                            totalDurationMs = attemptElapsedMs()
                        )
                    )
                }
                throw ce
            } catch (t: Throwable) {
                val elapsedPrep = ((System.nanoTime() - prepStartNs) / 1_000_000L).coerceAtLeast(0L)
                capturedAttempts?.add(
                    CapturedAttemptPayload(
                        attemptNumber = attemptNumber,
                        routeType = routeType,
                        preprocessConfig = preprocessConfig,
                        decodeStatus = "EXCEPTION",
                        stages = CaptchaStageKind.entries.map {
                            CapturedStageBuffer(it, CaptchaStageStatus.FAILED, note = "${t.javaClass.simpleName}: ${t.message ?: ""}")
                        },
                        mlKitInputWidth = null,
                        mlKitInputHeight = null,
                        rawOcrFullText = null,
                        elements = emptyList(),
                        elementCount = null,
                        normalizedDigits = null,
                        validationPassed = false,
                        rejectionCode = "PREPROCESS_EXCEPTION",
                        failureReason = "${t.javaClass.simpleName}: ${t.message ?: "Preprocess exception"}",
                        exceptionType = t.javaClass.name,
                        preprocessDurationMs = elapsedPrep,
                        ocrDurationMs = null,
                        totalDurationMs = attemptElapsedMs()
                    )
                )
                throw t
            }
        } else {
            val prepStartNs = System.nanoTime()
            tracedResult = null
            frame = framePreprocessor(imageBytes, preprocessConfig)
            preprocessMs = ((System.nanoTime() - prepStartNs) / 1_000_000L).coerceAtLeast(0L)
        }

        if (frame == null) {
            if (captureActive && tracedResult != null) {
                capturedAttempts?.add(
                    CapturedAttemptPayload(
                        attemptNumber = attemptNumber,
                        routeType = routeType,
                        preprocessConfig = preprocessConfig,
                        decodeStatus = tracedResult.decodeStatus,
                        stages = tracedResult.stages,
                        mlKitInputWidth = tracedResult.mlKitInputWidth,
                        mlKitInputHeight = tracedResult.mlKitInputHeight,
                        rawOcrFullText = null,
                        elements = emptyList(),
                        elementCount = null,
                        normalizedDigits = null,
                        validationPassed = false,
                        rejectionCode = tracedResult.decodeStatus,
                        failureReason = tracedResult.failureReason ?: "Preprocess returned null",
                        exceptionType = tracedResult.exceptionType,
                        preprocessDurationMs = preprocessMs,
                        ocrDurationMs = null,
                        totalDurationMs = attemptElapsedMs()
                    )
                )
            }
            return AttemptResult.PreprocessFailed
        }

        var recognizedOutput: OcrOutput? = null
        val ocrStartNs = System.nanoTime()
        var ocrDurationMs: Long? = null
        try {
            tracker.onStageChange(OcrSolveStage.RECOGNIZE)
            recognizedOutput = session.recognize(frame)
            ocrDurationMs = ((System.nanoTime() - ocrStartNs) / 1_000_000L).coerceAtLeast(0L)
        } catch (t: Throwable) {
            ocrDurationMs = ((System.nanoTime() - ocrStartNs) / 1_000_000L).coerceAtLeast(0L)
            if (captureActive && (t !is CancellationException || t is TimeoutCancellationException)) {
                capturedAttempts?.add(
                    CapturedAttemptPayload(
                        attemptNumber = attemptNumber,
                        routeType = routeType,
                        preprocessConfig = preprocessConfig,
                        decodeStatus = tracedResult?.decodeStatus ?: "SUCCESS",
                        stages = tracedResult?.stages ?: emptyList(),
                        mlKitInputWidth = tracedResult?.mlKitInputWidth,
                        mlKitInputHeight = tracedResult?.mlKitInputHeight,
                        rawOcrFullText = null,
                        elements = emptyList(),
                        elementCount = null,
                        normalizedDigits = null,
                        validationPassed = false,
                        rejectionCode = if (t is TimeoutCancellationException) "TIMEOUT_AT_RECOGNIZE" else "MLKIT_EXCEPTION",
                        failureReason = if (t is TimeoutCancellationException) {
                            "زمان تشخیص در مرحله OCR به پایان رسید."
                        } else {
                            buildSanitizedCauseChain(t)
                        },
                        exceptionType = t.javaClass.name,
                        mlKitErrorCode = extractMlKitErrorCode(t),
                        preprocessDurationMs = preprocessMs,
                        ocrDurationMs = ocrDurationMs,
                        totalDurationMs = attemptElapsedMs()
                    )
                )
            }
            throw t
        } finally {
            // Idempotent cleanup if OcrSession threw before or during recognition
            frame.release()
        }

        tracker.onStageChange(OcrSolveStage.VALIDATE)
        val nonNullOutput = recognizedOutput!!
        val validation = CaptchaDigitValidator.validate(
            output = nonNullOutput,
            minElementConfidence = solverConfig.minElementConfidence
        )

        if (captureActive) {
            val rawCombined = nonNullOutput.fullText.ifBlank {
                nonNullOutput.elements.joinToString(" ") { it.text }
            }
            val extractedDigits = CaptchaDigitValidator.extractNormalizedDigits(rawCombined)
            capturedAttempts?.add(
                CapturedAttemptPayload(
                    attemptNumber = attemptNumber,
                    routeType = routeType,
                    preprocessConfig = preprocessConfig,
                    decodeStatus = tracedResult?.decodeStatus ?: "SUCCESS",
                    stages = tracedResult?.stages ?: emptyList(),
                    mlKitInputWidth = tracedResult?.mlKitInputWidth,
                    mlKitInputHeight = tracedResult?.mlKitInputHeight,
                    rawOcrFullText = nonNullOutput.fullText,
                    elements = nonNullOutput.elements,
                    elementCount = nonNullOutput.elements.size,
                    normalizedDigits = extractedDigits.ifEmpty { null },
                    validationPassed = validation is DigitValidationOutcome.Valid,
                    rejectionCode = (validation as? DigitValidationOutcome.Invalid)?.rejectionCode,
                    failureReason = (validation as? DigitValidationOutcome.Invalid)?.let { inv ->
                        buildDetailedValidationFailureReason(inv)
                    },
                    preprocessDurationMs = preprocessMs,
                    ocrDurationMs = ocrDurationMs,
                    totalDurationMs = attemptElapsedMs()
                )
            )
        }

        return when (validation) {
            is DigitValidationOutcome.Valid -> AttemptResult.ValidDigits(
                digits = validation.digits,
                elementCount = nonNullOutput.elements.size
            )
            is DigitValidationOutcome.Invalid -> AttemptResult.Rejected(
                reason = validation.reason,
                rejectionCode = validation.rejectionCode,
                elementCount = validation.elementCount,
                extractedDigitCount = validation.extractedDigitCount
            )
        }
    }

    private fun buildDetailedValidationFailureReason(inv: DigitValidationOutcome.Invalid): String {
        return when (inv.rejectionCode) {
            "NO_TEXT_DETECTED" -> "هیچ متنی توسط ML Kit شناسایی نشد (NO_TEXT_DETECTED)"
            "DIGIT_COUNT_MISMATCH" -> {
                if (inv.extractedDigitCount < CaptchaDigitValidator.REQUIRED_DIGIT_COUNT) {
                    "کمتر از ۴ رقم شناسایی شد (${inv.extractedDigitCount} رقم)"
                } else {
                    "بیشتر از ۴ رقم شناسایی شد (${inv.extractedDigitCount} رقم)"
                }
            }
            "LOW_ELEMENT_CONFIDENCE" -> "ردشده به‌دلیل Confidence پایین در اجزای رقم"
            else -> inv.reason
        }
    }

    private fun emitLabCapture(
        captchaId: String,
        capturedAtEpochMs: Long,
        rawBytes: ByteArray,
        rawInspection: com.example.data.captcha.lab.RawImageInspection,
        outcome: CaptchaSolveOutcome,
        attempts: List<CapturedAttemptPayload>
    ) {
        val firstDecodedStage = attempts
            .flatMap { it.stages }
            .firstOrNull { it.stage == CaptchaStageKind.DECODED && it.status == CaptchaStageStatus.COMPLETED }
        val overallDecodeStatus = attempts.firstOrNull()?.decodeStatus ?: "UNKNOWN"
        val decodedFrameIndex = if (firstDecodedStage != null && rawInspection.gifInfo.isGif) 0 else null

        val category = classifyOutcomeCategory(outcome, attempts)
        val finalDigits = (outcome.result as? CaptchaResult.Success)?.digits
        val failureReason = when (val res = outcome.result) {
            is CaptchaResult.Success -> null
            is CaptchaResult.NotConfident -> {
                val attemptDetail = attempts.lastOrNull { !it.validationPassed }?.failureReason
                if (outcome.diagnostics.lastRejectionCode == "CONFLICTING_CANDIDATES") {
                    "خروجی‌های ۴ رقمی متفاوت از مسیرهای Fallback"
                } else {
                    attemptDetail ?: res.reason
                }
            }
            is CaptchaResult.Error -> {
                outcome.diagnostics.causeChain
                    ?: attempts.lastOrNull { it.failureReason != null }?.failureReason
                    ?: res.message
            }
        }

        labObserver.onSolveCompleted(
            CapturedCaptchaSession(
                captchaId = captchaId,
                capturedAtEpochMs = capturedAtEpochMs,
                rawBytes = rawBytes.copyOf(),
                rawInspection = rawInspection,
                decodedFrameIndex = decodedFrameIndex,
                decodeStatus = overallDecodeStatus,
                rawWidth = firstDecodedStage?.width ?: rawInspection.headerWidth,
                rawHeight = firstDecodedStage?.height ?: rawInspection.headerHeight,
                outcomeCategory = category,
                finalStatus = category.name,
                finalDigits = finalDigits,
                failureReason = failureReason,
                lastRejectionCode = outcome.diagnostics.lastRejectionCode,
                exceptionType = outcome.diagnostics.exceptionType,
                mlKitErrorCode = outcome.diagnostics.mlKitErrorCode,
                totalDurationMs = outcome.diagnostics.elapsedMs,
                attempts = attempts.toList()
            )
        )
    }

    private fun classifyOutcomeCategory(
        outcome: CaptchaSolveOutcome,
        attempts: List<CapturedAttemptPayload>
    ): CaptchaLabOutcomeCategory {
        val diag = outcome.diagnostics
        return when (outcome.result) {
            is CaptchaResult.Success -> {
                if (attempts.size <= 1) {
                    CaptchaLabOutcomeCategory.SUCCESS_PRIMARY
                } else {
                    CaptchaLabOutcomeCategory.SUCCESS_FALLBACK
                }
            }
            is CaptchaResult.NotConfident -> {
                val hadAnyText = attempts.any { !it.rawOcrFullText.isNullOrBlank() || (it.elementCount ?: 0) > 0 }
                if (diag.lastRejectionCode == "NO_TEXT_DETECTED" && !hadAnyText) {
                    CaptchaLabOutcomeCategory.NO_TEXT_DETECTED
                } else {
                    CaptchaLabOutcomeCategory.VALIDATION_REJECTED
                }
            }
            is CaptchaResult.Error -> {
                when {
                    diag.stage == OcrSolveStage.TIMEOUT -> CaptchaLabOutcomeCategory.TIMEOUT
                    diag.stage == OcrSolveStage.INPUT_VALIDATION -> CaptchaLabOutcomeCategory.EMPTY_INPUT
                    diag.stage == OcrSolveStage.RECOGNIZE ||
                        diag.stage == OcrSolveStage.SESSION_INIT ||
                        diag.mlKitErrorCode != null -> CaptchaLabOutcomeCategory.MLKIT_ERROR
                    else -> {
                        val decodeFailed = attempts.isNotEmpty() && attempts.all {
                            it.decodeStatus != "SUCCESS" &&
                                it.decodeStatus != "INVALID_CONFIG"
                        }
                        if (decodeFailed) {
                            CaptchaLabOutcomeCategory.DECODE_ERROR
                        } else {
                            CaptchaLabOutcomeCategory.PREPROCESS_ERROR
                        }
                    }
                }
            }
        }
    }

    private class StageTracker(
        val onStageChange: (OcrSolveStage) -> Unit,
        val onAttemptStart: () -> Unit
    )

    private sealed class AttemptResult {
        data class ValidDigits(val digits: String, val elementCount: Int) : AttemptResult()
        data class Rejected(
            val reason: String,
            val rejectionCode: String,
            val elementCount: Int,
            val extractedDigitCount: Int
        ) : AttemptResult()
        data object PreprocessFailed : AttemptResult()
    }

    companion object {
        private const val MAX_CAUSE_DEPTH = 4
        private const val MAX_CAUSE_CHAIN_LENGTH = 500

        internal fun extractMlKitErrorCode(throwable: Throwable): Int? {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (current is MlKitException) {
                    return current.errorCode
                }
                current = current.cause
                depth++
            }
            return null
        }

        internal fun buildSanitizedCauseChain(throwable: Throwable): String {
            val parts = mutableListOf<String>()
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                val safeMsg = current.message
                    ?.replace(Regex("[\\r\\n]+"), " ")
                    ?.trim()
                    ?.let { LogSanitizer.sanitizeText(it) }
                    ?.take(180)
                    ?.ifBlank { null }
                val mlCode = (current as? MlKitException)?.let { " [mlKitCode=${it.errorCode}]" } ?: ""
                val segment = buildString {
                    append(current.javaClass.name)
                    append(mlCode)
                    if (safeMsg != null) {
                        append(": ")
                        append(safeMsg)
                    }
                }
                parts.add(segment)
                current = current.cause
                depth++
            }
            return parts.joinToString(" <- ").take(MAX_CAUSE_CHAIN_LENGTH)
        }
    }
}
