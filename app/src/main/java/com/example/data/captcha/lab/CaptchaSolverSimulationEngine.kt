package com.example.data.captcha.lab

import com.example.data.captcha.preprocess.CaptchaPreprocessor
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.solver.AndroidBitmapFrame
import com.example.data.captcha.solver.CaptchaDigitValidator
import com.example.data.captcha.solver.CaptchaFrame
import com.example.data.captcha.solver.CaptchaResult
import com.example.data.captcha.solver.CaptchaSolverConfig
import com.example.data.captcha.solver.MlKitCaptchaSolver
import com.example.data.captcha.solver.MlKitOcrSession
import com.example.data.captcha.solver.OcrOutput
import com.example.data.captcha.solver.OcrSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * وضعیت پیشرفت اجرای شبیه‌سازی حل‌گر جدید روی آرشیو کپچا.
 */
data class SolverSimulationProgressState(
    val isRunning: Boolean = false,
    val scopeLabel: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val currentCaptchaId: String? = null
)

/**
 * آمار تجمیعی عملکرد حل‌گر (قبلی یا شبیه‌سازی‌شده) روی یک مجموعه رکورد آرشیو.
 */
data class SolverSimulationMetricsSnapshot(
    val totalEvaluated: Int = 0,
    val primarySuccessCount: Int = 0,
    val fallbackSuccessCount: Int = 0,
    val totalSuccessCount: Int = 0,
    val noTextCount: Int = 0,
    val rejectedOrErrorCount: Int = 0,
    val successRatePercent: Int = 0,
    val averageDurationMs: Long = 0L,
    val labeledEvaluatedCount: Int = 0,
    val exactMatchCount: Int = 0,
    val exactMatchRatePercent: Int? = null
)

/**
 * گزارش مقایسه غیرمخرب عملکرد حل‌گر قبلی (آرشیو ثبت‌شده) در برابر حل‌گر جدید روی بایت‌های خام.
 */
data class CaptchaSolverSimulationReport(
    val ranAtEpochMs: Long,
    val scopeLabel: String,
    val evaluatedCount: Int,
    val skippedMissingRawCount: Int,
    val cancelled: Boolean,
    val previous: SolverSimulationMetricsSnapshot,
    val simulated: SolverSimulationMetricsSnapshot
)

/**
 * موتور شبیه‌سازی حل‌گر جدید کپچا روی بایت‌های خام بایگانی‌شده بدون تغییر در فایل‌های آرشیو.
 * یک نشست ML Kit مشترک را در کل اجرا باز نگه می‌دارد تا سرعت ارزیابی ۱۰۰ کپچا بالا باشد.
 */
class CaptchaSolverSimulationEngine internal constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val sessionFactory: () -> OcrSession = { MlKitOcrSession() },
    private val solverConfig: CaptchaSolverConfig = CaptchaSolverConfig(),
    private val framePreprocessor: (ByteArray, PreprocessConfig) -> CaptchaFrame? = { bytes, cfg ->
        CaptchaPreprocessor.preprocess(bytes, cfg)?.let { AndroidBitmapFrame(it) }
    }
) {
    constructor() : this(
        dispatcher = Dispatchers.Default,
        sessionFactory = { MlKitOcrSession() },
        solverConfig = CaptchaSolverConfig(),
        framePreprocessor = { bytes, cfg ->
            CaptchaPreprocessor.preprocess(bytes, cfg)?.let { AndroidBitmapFrame(it) }
        }
    )

    suspend fun simulateBatch(
        repository: CaptchaLabRepository,
        records: List<CaptchaLabRecord>,
        scopeLabel: String,
        onProgress: suspend (completed: Int, total: Int, currentCaptchaId: String?) -> Unit = { _, _, _ -> }
    ): CaptchaSolverSimulationReport = withContext(dispatcher) {
        val total = records.size
        var skippedMissingRaw = 0
        val evaluatedPrevious = mutableListOf<CaptchaLabRecord>()

        var simPrimary = 0
        var simFallback = 0
        var simNoText = 0
        var simRejectedOrError = 0
        var simDurationSumMs = 0L
        var simLabeled = 0
        var simExactMatch = 0
        var wasCancelled = false

        val sharedSession = sessionFactory()
        val nonClosingSession = object : OcrSession {
            override suspend fun recognize(frame: CaptchaFrame): OcrOutput = sharedSession.recognize(frame)
            override fun close() = Unit // Lifecycle owned cleanly by simulateBatch finally block
        }

        val solver = MlKitCaptchaSolver(
            solverConfig = solverConfig,
            dispatcher = dispatcher,
            framePreprocessor = framePreprocessor,
            sessionFactory = { nonClosingSession },
            labObserver = NoOpCaptchaLabObserver,
            tracedFramePreprocessor = null
        )

        try {
            for ((idx, record) in records.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(idx, total, record.captchaId)

                val rawFile = repository.resolveFile(record.rawRelativePath)
                val rawBytes = rawFile?.let { file ->
                    runCatching { file.readBytes() }.getOrNull()
                }

                if (rawBytes == null || rawBytes.isEmpty()) {
                    skippedMissingRaw++
                    onProgress(idx + 1, total, record.captchaId)
                    continue
                }

                val outcome = solver.solveWithDiagnostics(rawBytes)
                evaluatedPrevious.add(record)
                simDurationSumMs += outcome.diagnostics.elapsedMs

                val validExpected = record.expectedDigits
                    ?.takeIf { it.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT }
                if (validExpected != null) {
                    simLabeled++
                }

                when (val res = outcome.result) {
                    is CaptchaResult.Success -> {
                        if (outcome.diagnostics.attemptCount <= 1) {
                            simPrimary++
                        } else {
                            simFallback++
                        }
                        if (validExpected != null && res.digits == validExpected) {
                            simExactMatch++
                        }
                    }
                    is CaptchaResult.NotConfident -> {
                        if (outcome.diagnostics.lastRejectionCode == "NO_TEXT_DETECTED") {
                            simNoText++
                        } else {
                            simRejectedOrError++
                        }
                    }
                    is CaptchaResult.Error -> {
                        simRejectedOrError++
                    }
                }

                onProgress(idx + 1, total, record.captchaId)
            }
        } catch (_: CancellationException) {
            wasCancelled = true
        } finally {
            runCatching { sharedSession.close() }
        }

        val evaluatedCount = evaluatedPrevious.size
        val prevSnapshot = computePreviousSnapshot(evaluatedPrevious)
        val simSuccess = simPrimary + simFallback
        val simSnapshot = SolverSimulationMetricsSnapshot(
            totalEvaluated = evaluatedCount,
            primarySuccessCount = simPrimary,
            fallbackSuccessCount = simFallback,
            totalSuccessCount = simSuccess,
            noTextCount = simNoText,
            rejectedOrErrorCount = simRejectedOrError,
            successRatePercent = if (evaluatedCount == 0) 0 else ((simSuccess * 100.0) / evaluatedCount).roundToInt(),
            averageDurationMs = if (evaluatedCount == 0) 0L else (simDurationSumMs / evaluatedCount),
            labeledEvaluatedCount = simLabeled,
            exactMatchCount = simExactMatch,
            exactMatchRatePercent = if (simLabeled == 0) null else ((simExactMatch * 100.0) / simLabeled).roundToInt()
        )

        CaptchaSolverSimulationReport(
            ranAtEpochMs = System.currentTimeMillis(),
            scopeLabel = scopeLabel,
            evaluatedCount = evaluatedCount,
            skippedMissingRawCount = skippedMissingRaw,
            cancelled = wasCancelled,
            previous = prevSnapshot,
            simulated = simSnapshot
        )
    }

    companion object {
        fun computePreviousSnapshot(records: List<CaptchaLabRecord>): SolverSimulationMetricsSnapshot {
            val total = records.size
            if (total == 0) return SolverSimulationMetricsSnapshot()

            val primary = records.count { it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_PRIMARY }
            val fallback = records.count { it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_FALLBACK }
            val totalSuccess = primary + fallback
            val noText = records.count { it.outcomeCategory == CaptchaLabOutcomeCategory.NO_TEXT_DETECTED }
            val rejectedOrErr = total - totalSuccess - noText
            val avgMs = records.sumOf { it.totalDurationMs } / total

            val labeled = records.filter {
                it.expectedDigits?.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT
            }
            val exactMatches = labeled.count { rec ->
                rec.finalDigits == rec.expectedDigits
            }

            return SolverSimulationMetricsSnapshot(
                totalEvaluated = total,
                primarySuccessCount = primary,
                fallbackSuccessCount = fallback,
                totalSuccessCount = totalSuccess,
                noTextCount = noText,
                rejectedOrErrorCount = rejectedOrErr,
                successRatePercent = ((totalSuccess * 100.0) / total).roundToInt(),
                averageDurationMs = avgMs,
                labeledEvaluatedCount = labeled.size,
                exactMatchCount = exactMatches,
                exactMatchRatePercent = if (labeled.isEmpty()) null else ((exactMatches * 100.0) / labeled.size).roundToInt()
            )
        }
    }
}
