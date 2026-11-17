package com.example.data.captcha.lab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.captcha.solver.AndroidBitmapFrame
import com.example.data.captcha.solver.CaptchaDigitValidator
import com.example.data.captcha.solver.CaptchaFrame
import com.example.data.captcha.solver.DigitValidationOutcome
import com.example.data.captcha.solver.MlKitOcrSession
import com.example.data.captcha.solver.OcrSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * قاب تصویر همراه با ابعاد واقعی برای ارزیابی دقیق مراحل در آزمایشگاه کپچا.
 */
internal data class DecodedBenchmarkFrame(
    val frame: CaptchaFrame,
    val width: Int?,
    val height: Int?
)

/**
 * موتور اجرای دستی و انبوه OCR روی تصویر خام + ۸ مرحله پیش‌پردازش و تحلیل سازگاری مراحل با مدل ML Kit.
 */
class CaptchaStageBenchmarkEngine internal constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val sessionFactory: () -> OcrSession = { MlKitOcrSession() },
    private val fileFrameDecoder: (File) -> DecodedBenchmarkFrame? = DefaultFileFrameDecoder
) {
    constructor() : this(
        dispatcher = Dispatchers.Default,
        sessionFactory = { MlKitOcrSession() },
        fileFrameDecoder = DefaultFileFrameDecoder
    )

    suspend fun benchmarkRecord(
        repository: CaptchaLabRepository,
        record: CaptchaLabRecord,
        attemptNumber: Int? = null
    ): CaptchaStageBenchmarkResult = withContext(dispatcher) {
        val session = sessionFactory()
        try {
            benchmarkWithSession(
                repository = repository,
                record = record,
                session = session,
                requestedAttemptNumber = attemptNumber
            )
        } finally {
            runCatching { session.close() }
        }
    }

    suspend fun benchmarkBatch(
        repository: CaptchaLabRepository,
        records: List<CaptchaLabRecord>,
        onProgress: suspend (completed: Int, total: Int, currentCaptchaId: String?, updatedRecord: CaptchaLabRecord?) -> Unit
    ) = withContext(dispatcher) {
        if (records.isEmpty()) return@withContext
        val session = sessionFactory()
        try {
            val total = records.size
            records.forEachIndexed { idx, record ->
                currentCoroutineContext().ensureActive()
                onProgress(idx, total, record.captchaId, null)
                currentCoroutineContext().ensureActive()
                val benchmark = benchmarkWithSession(
                    repository = repository,
                    record = record,
                    session = session,
                    requestedAttemptNumber = null
                )
                val updated = repository.updateStageBenchmark(record.captchaId, benchmark)
                onProgress(idx + 1, total, record.captchaId, updated)
            }
        } finally {
            runCatching { session.close() }
        }
    }

    private suspend fun benchmarkWithSession(
        repository: CaptchaLabRepository,
        record: CaptchaLabRecord,
        session: OcrSession,
        requestedAttemptNumber: Int?
    ): CaptchaStageBenchmarkResult {
        val startNs = System.nanoTime()
        val attempt = requestedAttemptNumber?.let { num ->
            record.attempts.firstOrNull { it.attemptNumber == num }
        } ?: record.attempts.firstOrNull()

        val selectedAttemptNumber = attempt?.attemptNumber ?: 1
        val expectedDigits = record.expectedDigits?.takeIf { it.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT }
        val targetResults = mutableListOf<CaptchaStageOcrTargetResult>()

        // 1. Raw image target
        targetResults.add(
            runSingleTarget(
                targetKey = CaptchaLabConstants.TARGET_KEY_RAW,
                targetTitleFa = TITLE_RAW_TARGET,
                file = repository.resolveFile(record.rawRelativePath),
                stageStatus = if (record.rawRelativePath != null) CaptchaStageStatus.COMPLETED else CaptchaStageStatus.NOT_RUN,
                skipReason = "تصویر خام سرور در آرشیو یافت نشد",
                fallbackWidth = record.rawWidth,
                fallbackHeight = record.rawHeight,
                expectedDigits = expectedDigits,
                session = session
            )
        )

        // 2. All 8 preprocessing stages in canonical pipeline order
        CaptchaStageKind.entries.forEach { stageKind ->
            currentCoroutineContext().ensureActive()
            val stageArtifact = attempt?.stages?.firstOrNull { it.stage == stageKind }
            val stageFile = repository.resolveFile(stageArtifact?.relativePath)
            val skipNote = when {
                attempt == null -> "تلاشی برای این کپچا ثبت نشده است"
                stageArtifact == null -> "داده این مرحله ثبت نشده است"
                stageArtifact.status != CaptchaStageStatus.COMPLETED ->
                    stageArtifact.note ?: "وضعیت مرحله: ${stageArtifact.status.labelFa}"
                stageFile == null -> "فایل تصویر این مرحله روی دیسک موجود نیست"
                else -> null
            }

            targetResults.add(
                runSingleTarget(
                    targetKey = stageKind.name,
                    targetTitleFa = stageKind.titleFa,
                    file = stageFile,
                    stageStatus = stageArtifact?.status ?: CaptchaStageStatus.NOT_RUN,
                    skipReason = skipNote,
                    fallbackWidth = stageArtifact?.width,
                    fallbackHeight = stageArtifact?.height,
                    expectedDigits = expectedDigits,
                    session = session
                )
            )
        }

        val totalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
        val bestKey = selectBestTargetKey(targetResults, expectedDigits)
        val nowMs = System.currentTimeMillis()
        return CaptchaStageBenchmarkResult(
            ranAtEpochMs = nowMs,
            ranAtUtcIso = CaptchaLabRepository.formatUtcIso(nowMs),
            attemptNumber = selectedAttemptNumber,
            targets = targetResults,
            bestTargetKey = bestKey,
            totalDurationMs = totalMs
        )
    }

    private suspend fun runSingleTarget(
        targetKey: String,
        targetTitleFa: String,
        file: File?,
        stageStatus: CaptchaStageStatus,
        skipReason: String?,
        fallbackWidth: Int?,
        fallbackHeight: Int?,
        expectedDigits: String?,
        session: OcrSession
    ): CaptchaStageOcrTargetResult {
        if (stageStatus != CaptchaStageStatus.COMPLETED || file == null || !file.exists()) {
            return CaptchaStageOcrTargetResult(
                targetKey = targetKey,
                targetTitleFa = targetTitleFa,
                status = CaptchaBenchmarkTargetStatus.SKIPPED,
                width = fallbackWidth,
                height = fallbackHeight,
                errorMessage = skipReason ?: "فایل مرحله در دسترس نیست"
            )
        }

        val decoded = try {
            fileFrameDecoder(file)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return CaptchaStageOcrTargetResult(
                targetKey = targetKey,
                targetTitleFa = targetTitleFa,
                status = CaptchaBenchmarkTargetStatus.FAILED,
                width = fallbackWidth,
                height = fallbackHeight,
                errorMessage = "${t.javaClass.simpleName}: ${t.message ?: "خطا در رمزگشایی تصویر"}"
            )
        }

        if (decoded == null) {
            return CaptchaStageOcrTargetResult(
                targetKey = targetKey,
                targetTitleFa = targetTitleFa,
                status = CaptchaBenchmarkTargetStatus.FAILED,
                width = fallbackWidth,
                height = fallbackHeight,
                errorMessage = "امکان Decode فایل تصویر برای OCR وجود نداشت"
            )
        }

        val ocrStartNs = System.nanoTime()
        return try {
            val output = session.recognize(decoded.frame)
            val ocrMs = ((System.nanoTime() - ocrStartNs) / 1_000_000L).coerceAtLeast(0L)
            val rawCombined = output.fullText.ifBlank {
                output.elements.joinToString(" ") { it.text }
            }
            val normalizedDigits = CaptchaDigitValidator.extractNormalizedDigits(rawCombined).ifEmpty { null }
            val validation = CaptchaDigitValidator.validate(output)
            val validationPassed = validation is DigitValidationOutcome.Valid
            val rejectionCode = (validation as? DigitValidationOutcome.Invalid)?.rejectionCode
            val confValues = output.elements.mapNotNull { c ->
                c.confidence?.takeIf { !it.isNaN() && it >= 0f }
            }
            val avgConf = if (confValues.isNotEmpty()) {
                confValues.sum() / confValues.size.toFloat()
            } else {
                null
            }
            val matchesExpected = expectedDigits?.let { normalizedDigits == it }

            CaptchaStageOcrTargetResult(
                targetKey = targetKey,
                targetTitleFa = targetTitleFa,
                status = CaptchaBenchmarkTargetStatus.COMPLETED,
                width = decoded.width ?: fallbackWidth,
                height = decoded.height ?: fallbackHeight,
                rawOcrFullText = output.fullText,
                elements = output.elements.map { CaptchaLabElementRecord(it.text, it.confidence) },
                elementCount = output.elements.size,
                normalizedDigits = normalizedDigits,
                validationPassed = validationPassed,
                rejectionCode = rejectionCode,
                matchesExpectedDigits = matchesExpected,
                averageConfidence = avgConf,
                ocrDurationMs = ocrMs,
                errorMessage = null
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val ocrMs = ((System.nanoTime() - ocrStartNs) / 1_000_000L).coerceAtLeast(0L)
            CaptchaStageOcrTargetResult(
                targetKey = targetKey,
                targetTitleFa = targetTitleFa,
                status = CaptchaBenchmarkTargetStatus.FAILED,
                width = decoded.width ?: fallbackWidth,
                height = decoded.height ?: fallbackHeight,
                ocrDurationMs = ocrMs,
                errorMessage = "${t.javaClass.simpleName}: ${t.message ?: "خطای ML Kit"}"
            )
        } finally {
            runCatching { decoded.frame.release() }
        }
    }

    companion object {
        const val TITLE_RAW_TARGET = "تصویر خام سرور (Raw)"

        val canonicalTargetOrder: List<Pair<String, String>> = listOf(
            CaptchaLabConstants.TARGET_KEY_RAW to TITLE_RAW_TARGET
        ) + CaptchaStageKind.entries.map { it.name to it.titleFa }

        private object DefaultFileFrameDecoder : (File) -> DecodedBenchmarkFrame? {
            override fun invoke(file: File): DecodedBenchmarkFrame? {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = false
                    inScaled = false
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                return DecodedBenchmarkFrame(
                    frame = AndroidBitmapFrame(bmp),
                    width = bmp.width,
                    height = bmp.height
                )
            }
        }

        internal fun selectBestTargetKey(
            targets: List<CaptchaStageOcrTargetResult>,
            expectedDigits: String?
        ): String? {
            val completed = targets.filter { it.status == CaptchaBenchmarkTargetStatus.COMPLETED }
            if (completed.isEmpty()) return null
            return completed.maxWithOrNull { a, b ->
                compareTargetsSingleRecord(a, b, expectedDigits)
            }?.targetKey
        }

        private fun compareTargetsSingleRecord(
            a: CaptchaStageOcrTargetResult,
            b: CaptchaStageOcrTargetResult,
            expectedDigits: String?
        ): Int {
            if (expectedDigits != null) {
                val matchA = if (a.matchesExpectedDigits == true) 1 else 0
                val matchB = if (b.matchesExpectedDigits == true) 1 else 0
                if (matchA != matchB) return matchA.compareTo(matchB)
            }

            val validA = if (a.validationPassed) 1 else 0
            val validB = if (b.validationPassed) 1 else 0
            if (validA != validB) return validA.compareTo(validB)

            val fourDigitsA = if (a.normalizedDigits?.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT) 1 else 0
            val fourDigitsB = if (b.normalizedDigits?.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT) 1 else 0
            if (fourDigitsA != fourDigitsB) return fourDigitsA.compareTo(fourDigitsB)

            val notEmptyA = if (a.rejectionCode != "NO_TEXT_DETECTED" && !a.rawOcrFullText.isNullOrBlank()) 1 else 0
            val notEmptyB = if (b.rejectionCode != "NO_TEXT_DETECTED" && !b.rawOcrFullText.isNullOrBlank()) 1 else 0
            if (notEmptyA != notEmptyB) return notEmptyA.compareTo(notEmptyB)

            val confA = a.averageConfidence ?: -1f
            val confB = b.averageConfidence ?: -1f
            if (confA != confB) return confA.compareTo(confB)

            // Higher pipeline step tends to be cleaner if tied on everything else, or faster OCR
            val durA = a.ocrDurationMs ?: Long.MAX_VALUE
            val durB = b.ocrDurationMs ?: Long.MAX_VALUE
            return durB.compareTo(durA)
        }

        /**
         * بازمحاسبه وضعیت انطباق با برچسب دستی و کلید بهترین مرحله پس از تغییر `expectedDigits`.
         */
        fun recalculateWithExpectedDigits(
            benchmark: CaptchaStageBenchmarkResult,
            expectedDigits: String?
        ): CaptchaStageBenchmarkResult {
            val cleanExpected = expectedDigits?.takeIf { it.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT }
            val updatedTargets = benchmark.targets.map { t ->
                if (t.status == CaptchaBenchmarkTargetStatus.COMPLETED) {
                    t.copy(matchesExpectedDigits = cleanExpected?.let { t.normalizedDigits == it })
                } else {
                    t.copy(matchesExpectedDigits = null)
                }
            }
            return benchmark.copy(
                targets = updatedTargets,
                bestTargetKey = selectBestTargetKey(updatedTargets, cleanExpected)
            )
        }

        /**
         * تحلیل جامع سازگاری مراحل ۹گانه با مدل ML Kit در سراسر رکوردهای بنچمارک‌شده آرشیو.
         */
        fun analyzeCompatibility(records: List<CaptchaLabRecord>): StageCompatibilitySummary {
            val benchmarked = records.filter { it.stageBenchmark != null }
            if (benchmarked.isEmpty()) {
                return StageCompatibilitySummary()
            }
            val labeledCount = benchmarked.count { !it.expectedDigits.isNullOrBlank() }

            val rawMetrics = canonicalTargetOrder.map { (key, titleFa) ->
                val entries = benchmarked.mapNotNull { rec ->
                    val target = rec.stageBenchmark?.targets?.firstOrNull {
                        it.targetKey == key && it.status == CaptchaBenchmarkTargetStatus.COMPLETED
                    } ?: return@mapNotNull null
                    rec to target
                }

                val evaluatedCount = entries.size
                if (evaluatedCount == 0) {
                    return@map UnrankedMetric(
                        targetKey = key,
                        targetTitleFa = titleFa,
                        evaluatedCount = 0,
                        valid4DigitCount = 0,
                        valid4DigitRatePercent = 0,
                        labeledEvaluatedCount = 0,
                        exactMatchCount = 0,
                        exactMatchRatePercent = null,
                        averageConfidence = null,
                        confidenceSampleCount = 0,
                        noTextCount = 0,
                        noTextRatePercent = 0,
                        averageOcrDurationMs = 0L,
                        compatibilityScore = 0.0
                    )
                }

                val valid4DigitCount = entries.count { (_, t) -> t.validationPassed }
                val valid4DigitRate = (valid4DigitCount * 100.0) / evaluatedCount

                val labeledEntries = entries.filter { (rec, _) ->
                    rec.expectedDigits?.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT
                }
                val labeledEvalCount = labeledEntries.size
                val exactMatchCount = labeledEntries.count { (rec, t) ->
                    t.normalizedDigits == rec.expectedDigits
                }
                val exactMatchRate: Double? = if (labeledEvalCount > 0) {
                    (exactMatchCount * 100.0) / labeledEvalCount
                } else {
                    null
                }

                val confs = entries.mapNotNull { (_, t) -> t.averageConfidence }
                val avgConf = if (confs.isNotEmpty()) confs.sum() / confs.size.toFloat() else null

                val noTextCount = entries.count { (_, t) ->
                    t.rejectionCode == "NO_TEXT_DETECTED" ||
                        (t.rawOcrFullText.isNullOrBlank() && (t.elementCount ?: 0) == 0)
                }
                val noTextRate = (noTextCount * 100.0) / evaluatedCount

                val durations = entries.mapNotNull { (_, t) -> t.ocrDurationMs }
                val avgMs = if (durations.isNotEmpty()) (durations.sum() / durations.size) else 0L

                // Composite score (0..100):
                // Auto score: 55% valid 4-digit rate + 25% genuine avg confidence (neutral 50 if unavailable) + 20% non-empty rate
                val confComponent = (avgConf?.times(100.0)) ?: 50.0
                val autoScore = 0.55 * valid4DigitRate + 0.25 * confComponent + 0.20 * (100.0 - noTextRate)

                // If ground-truth manual labels exist, give exact-match accuracy proportional priority up to 60%
                val finalScore = if (exactMatchRate != null) {
                    val labelCoverage = (labeledEvalCount.toDouble() / evaluatedCount.toDouble()).coerceIn(0.0, 1.0)
                    val exactWeight = 0.60 * labelCoverage
                    exactWeight * exactMatchRate + (1.0 - exactWeight) * autoScore
                } else {
                    autoScore
                }

                UnrankedMetric(
                    targetKey = key,
                    targetTitleFa = titleFa,
                    evaluatedCount = evaluatedCount,
                    valid4DigitCount = valid4DigitCount,
                    valid4DigitRatePercent = valid4DigitRate.roundToInt(),
                    labeledEvaluatedCount = labeledEvalCount,
                    exactMatchCount = exactMatchCount,
                    exactMatchRatePercent = exactMatchRate?.roundToInt(),
                    averageConfidence = avgConf,
                    confidenceSampleCount = confs.size,
                    noTextCount = noTextCount,
                    noTextRatePercent = noTextRate.roundToInt(),
                    averageOcrDurationMs = avgMs,
                    compatibilityScore = (finalScore * 10.0).roundToInt() / 10.0
                )
            }

            val sorted = rawMetrics.sortedWith(
                compareByDescending<UnrankedMetric> { it.evaluatedCount > 0 }
                    .thenByDescending { it.compatibilityScore }
                    .thenByDescending { it.exactMatchRatePercent ?: -1 }
                    .thenByDescending { it.valid4DigitRatePercent }
                    .thenByDescending { it.averageConfidence ?: -1f }
                    .thenBy { it.noTextRatePercent }
                    .thenBy { it.averageOcrDurationMs }
            )

            val bestKey = sorted.firstOrNull { it.evaluatedCount > 0 }?.targetKey
            val ranked = sorted.mapIndexed { idx, m ->
                StageCompatibilityMetric(
                    rank = idx + 1,
                    targetKey = m.targetKey,
                    targetTitleFa = m.targetTitleFa,
                    evaluatedCount = m.evaluatedCount,
                    valid4DigitCount = m.valid4DigitCount,
                    valid4DigitRatePercent = m.valid4DigitRatePercent,
                    labeledEvaluatedCount = m.labeledEvaluatedCount,
                    exactMatchCount = m.exactMatchCount,
                    exactMatchRatePercent = m.exactMatchRatePercent,
                    averageConfidence = m.averageConfidence,
                    confidenceSampleCount = m.confidenceSampleCount,
                    noTextCount = m.noTextCount,
                    noTextRatePercent = m.noTextRatePercent,
                    averageOcrDurationMs = m.averageOcrDurationMs,
                    compatibilityScore = m.compatibilityScore,
                    isRecommendedBest = m.targetKey == bestKey && m.evaluatedCount > 0
                )
            }

            val bestMetric = ranked.firstOrNull { it.isRecommendedBest }
            return StageCompatibilitySummary(
                benchmarkedCaptchasCount = benchmarked.size,
                labeledCaptchasCount = labeledCount,
                bestStageKey = bestMetric?.targetKey,
                bestStageTitleFa = bestMetric?.targetTitleFa,
                metrics = ranked
            )
        }

        private data class UnrankedMetric(
            val targetKey: String,
            val targetTitleFa: String,
            val evaluatedCount: Int,
            val valid4DigitCount: Int,
            val valid4DigitRatePercent: Int,
            val labeledEvaluatedCount: Int,
            val exactMatchCount: Int,
            val exactMatchRatePercent: Int?,
            val averageConfidence: Float?,
            val confidenceSampleCount: Int,
            val noTextCount: Int,
            val noTextRatePercent: Int,
            val averageOcrDurationMs: Long,
            val compatibilityScore: Double
        )
    }
}
