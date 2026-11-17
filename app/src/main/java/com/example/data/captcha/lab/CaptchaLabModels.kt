package com.example.data.captcha.lab

import com.example.data.captcha.preprocess.NoiseRemovalMode
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.preprocess.ThresholdMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * دسته‌بندی نتیجه نهایی یک کپچای بایگانی‌شده در آزمایشگاه کپچا.
 */
enum class CaptchaLabOutcomeCategory(val labelFa: String) {
    SUCCESS_PRIMARY("موفق در تلاش اصلی"),
    SUCCESS_FALLBACK("موفق در تلاش جایگزین"),
    NO_TEXT_DETECTED("بدون متن شناسایی‌شده"),
    VALIDATION_REJECTED("ردشده در اعتبارسنجی"),
    DECODE_ERROR("خطای Decode تصویر"),
    PREPROCESS_ERROR("خطای پیش‌پردازش"),
    MLKIT_ERROR("خطای ML Kit"),
    TIMEOUT("اتمام زمان مجاز"),
    EMPTY_INPUT("ورودی خالی")
}

/**
 * مراحل تصویربرداری پیش‌پردازش از تصویر رمزگشایی‌شده تا ورودی نهایی ML Kit.
 */
enum class CaptchaStageKind(
    val fileBaseName: String,
    val titleFa: String
) {
    DECODED("01_decoded", "تصویر رمزگشایی‌شده"),
    GRAYSCALE("02_grayscale", "تبدیل خاکستری"),
    CONTRAST_ENHANCED("03_contrast_enhanced", "بهبود کنتراست"),
    THRESHOLDED("04_thresholded", "آستانه‌گذاری (Threshold)"),
    DENOISED("05_denoised", "حذف نویز"),
    INVERTED("06_inverted", "وارونه‌سازی (Invert)"),
    UPSCALED("07_upscaled", "بزرگ‌نمایی (Upscale)"),
    FINAL_PADDED("08_final_padded_mlkit_input", "نهایی حاشیه‌دار (ورودی ML Kit)")
}

enum class CaptchaStageStatus(val labelFa: String) {
    COMPLETED("اجرا شد"),
    FAILED("خطا"),
    SKIPPED("رد شد"),
    NOT_RUN("اجرا نشد")
}

data class GifInspectionInfo(
    val isGif: Boolean = false,
    val gifVersion: String? = null,
    val isAnimatedGif: Boolean = false,
    val frameCount: Int = 0,
    val logicalWidth: Int? = null,
    val logicalHeight: Int? = null,
    val decodedFrameIndex: Int? = null,
    val note: String? = null
)

data class CaptchaLabStageArtifact(
    val stage: CaptchaStageKind,
    val status: CaptchaStageStatus,
    val width: Int? = null,
    val height: Int? = null,
    val relativePath: String? = null,
    val note: String? = null
)

data class CaptchaLabElementRecord(
    val text: String,
    val confidence: Float?
)

data class CaptchaLabAttemptRecord(
    val attemptNumber: Int,
    val routeType: String,
    val preprocessConfig: PreprocessConfig,
    val decodeStatus: String,
    val stages: List<CaptchaLabStageArtifact>,
    val mlKitInputWidth: Int?,
    val mlKitInputHeight: Int?,
    val rawOcrFullText: String?,
    val elements: List<CaptchaLabElementRecord>,
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

data class CaptchaLabRecord(
    val captchaId: String,
    val capturedAtEpochMs: Long,
    val capturedAtUtcIso: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val buildType: String,
    val rawFormat: String,
    val mimeType: String,
    val rawExtension: String,
    val rawByteSize: Int,
    val rawWidth: Int?,
    val rawHeight: Int?,
    val rawRelativePath: String?,
    val gifInfo: GifInspectionInfo,
    val decodeStatus: String,
    val outcomeCategory: CaptchaLabOutcomeCategory,
    val finalStatus: String,
    val finalDigits: String?,
    val failureReason: String?,
    val lastRejectionCode: String?,
    val exceptionType: String? = null,
    val mlKitErrorCode: Int? = null,
    val totalDurationMs: Long,
    val attempts: List<CaptchaLabAttemptRecord>
)

enum class CaptchaLabFilter(val labelFa: String) {
    ALL("همه"),
    SUCCESS("موفق"),
    FAILED("ناموفق"),
    NO_TEXT_DETECTED("بدون متن"),
    HAS_TEXT_INVALID("متن نامعتبر"),
    ERROR_PIPELINE("خطا / Timeout"),
    FALLBACK_SUCCESS("موفق در Fallback")
}

enum class CaptchaLabSort(val labelFa: String) {
    NEWEST_FIRST("جدیدترین"),
    OLDEST_FIRST("قدیمی‌ترین"),
    BY_OUTCOME("بر اساس نتیجه")
}

data class CaptchaLabSummaryStats(
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val noTextDetectedCount: Int = 0,
    val fallbackSuccessCount: Int = 0,
    val successRatePercent: Int = 0
)

/**
 * تبدیل مدل‌های آزمایشگاه کپچا به JSON / JSONL و برعکس بدون ذخیره هیچ‌گونه تصویر Base64.
 */
object CaptchaLabJson {

    fun recordToJson(record: CaptchaLabRecord): JSONObject = JSONObject().apply {
        put("captchaId", record.captchaId)
        put("capturedAtEpochMs", record.capturedAtEpochMs)
        put("capturedAtUtcIso", record.capturedAtUtcIso)
        put("appVersionName", record.appVersionName)
        put("appVersionCode", record.appVersionCode)
        put("buildType", record.buildType)
        put("rawFormat", record.rawFormat)
        put("mimeType", record.mimeType)
        put("rawExtension", record.rawExtension)
        put("rawByteSize", record.rawByteSize)
        put("rawWidth", record.rawWidth ?: JSONObject.NULL)
        put("rawHeight", record.rawHeight ?: JSONObject.NULL)
        put("rawRelativePath", record.rawRelativePath ?: JSONObject.NULL)
        put("gifInfo", gifInfoToJson(record.gifInfo))
        put("decodeStatus", record.decodeStatus)
        put("outcomeCategory", record.outcomeCategory.name)
        put("finalStatus", record.finalStatus)
        put("finalDigits", record.finalDigits ?: JSONObject.NULL)
        put("failureReason", record.failureReason ?: JSONObject.NULL)
        put("lastRejectionCode", record.lastRejectionCode ?: JSONObject.NULL)
        put("exceptionType", record.exceptionType ?: JSONObject.NULL)
        put("mlKitErrorCode", record.mlKitErrorCode ?: JSONObject.NULL)
        put("totalDurationMs", record.totalDurationMs)
        put("attempts", JSONArray().apply {
            record.attempts.forEach { put(attemptToJson(it)) }
        })
    }

    fun recordFromJson(obj: JSONObject): CaptchaLabRecord {
        val attemptsArr = obj.optJSONArray("attempts") ?: JSONArray()
        val attempts = buildList {
            for (i in 0 until attemptsArr.length()) {
                val item = attemptsArr.optJSONObject(i) ?: continue
                add(attemptFromJson(item))
            }
        }
        val categoryRaw = obj.optString("outcomeCategory", CaptchaLabOutcomeCategory.PREPROCESS_ERROR.name)
        val category = runCatching { CaptchaLabOutcomeCategory.valueOf(categoryRaw) }
            .getOrDefault(CaptchaLabOutcomeCategory.PREPROCESS_ERROR)

        return CaptchaLabRecord(
            captchaId = obj.getString("captchaId"),
            capturedAtEpochMs = obj.getLong("capturedAtEpochMs"),
            capturedAtUtcIso = obj.optString("capturedAtUtcIso", ""),
            appVersionName = obj.optString("appVersionName", ""),
            appVersionCode = obj.optInt("appVersionCode", 0),
            buildType = obj.optString("buildType", "unknown"),
            rawFormat = obj.optString("rawFormat", "UNKNOWN"),
            mimeType = obj.optString("mimeType", "application/octet-stream"),
            rawExtension = obj.optString("rawExtension", "bin"),
            rawByteSize = obj.optInt("rawByteSize", 0),
            rawWidth = obj.optNullableInt("rawWidth"),
            rawHeight = obj.optNullableInt("rawHeight"),
            rawRelativePath = obj.optNullableString("rawRelativePath"),
            gifInfo = obj.optJSONObject("gifInfo")?.let(::gifInfoFromJson) ?: GifInspectionInfo(),
            decodeStatus = obj.optString("decodeStatus", "UNKNOWN"),
            outcomeCategory = category,
            finalStatus = obj.optString("finalStatus", category.name),
            finalDigits = obj.optNullableString("finalDigits"),
            failureReason = obj.optNullableString("failureReason"),
            lastRejectionCode = obj.optNullableString("lastRejectionCode"),
            exceptionType = obj.optNullableString("exceptionType"),
            mlKitErrorCode = obj.optNullableInt("mlKitErrorCode"),
            totalDurationMs = obj.optLong("totalDurationMs", 0L),
            attempts = attempts
        )
    }

    fun buildSummaryJson(
        records: List<CaptchaLabRecord>,
        stats: CaptchaLabSummaryStats,
        exportedAtUtcIso: String,
        appVersionName: String,
        appVersionCode: Int,
        buildType: String
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("exportedAtUtcIso", exportedAtUtcIso)
        put("appVersionName", appVersionName)
        put("appVersionCode", appVersionCode)
        put("buildType", buildType)
        put("maxRetainedCaptchas", CaptchaLabConstants.MAX_RETAINED_CAPTCHAS)
        put("stats", JSONObject().apply {
            put("totalCount", stats.totalCount)
            put("successCount", stats.successCount)
            put("failedCount", stats.failedCount)
            put("noTextDetectedCount", stats.noTextDetectedCount)
            put("fallbackSuccessCount", stats.fallbackSuccessCount)
            put("successRatePercent", stats.successRatePercent)
        })
        put("outcomeCounts", JSONObject().apply {
            CaptchaLabOutcomeCategory.entries.forEach { category ->
                put(category.name, records.count { it.outcomeCategory == category })
            }
        })
        put("captchaIds", JSONArray().apply {
            records.forEach { put(it.captchaId) }
        })
    }

    private fun gifInfoToJson(info: GifInspectionInfo): JSONObject = JSONObject().apply {
        put("isGif", info.isGif)
        put("gifVersion", info.gifVersion ?: JSONObject.NULL)
        put("isAnimatedGif", info.isAnimatedGif)
        put("frameCount", info.frameCount)
        put("logicalWidth", info.logicalWidth ?: JSONObject.NULL)
        put("logicalHeight", info.logicalHeight ?: JSONObject.NULL)
        put("decodedFrameIndex", info.decodedFrameIndex ?: JSONObject.NULL)
        put("note", info.note ?: JSONObject.NULL)
    }

    private fun gifInfoFromJson(obj: JSONObject): GifInspectionInfo = GifInspectionInfo(
        isGif = obj.optBoolean("isGif", false),
        gifVersion = obj.optNullableString("gifVersion"),
        isAnimatedGif = obj.optBoolean("isAnimatedGif", false),
        frameCount = obj.optInt("frameCount", 0),
        logicalWidth = obj.optNullableInt("logicalWidth"),
        logicalHeight = obj.optNullableInt("logicalHeight"),
        decodedFrameIndex = obj.optNullableInt("decodedFrameIndex"),
        note = obj.optNullableString("note")
    )

    private fun preprocessConfigToJson(cfg: PreprocessConfig): JSONObject = JSONObject().apply {
        put("thresholdMode", cfg.thresholdMode.name)
        put("fixedThreshold", cfg.fixedThreshold)
        put("noiseRemovalMode", cfg.noiseRemovalMode.name)
        put("filterSize", cfg.filterSize)
        put("scaleFactor", cfg.scaleFactor)
        put("borderPaddingPx", cfg.borderPaddingPx)
        put("invertOutput", cfg.invertOutput)
        put("contrastClipPercent", cfg.contrastClipPercent.toDouble())
        put("minDimensionPx", cfg.minDimensionPx)
        put("maxDimensionPx", cfg.maxDimensionPx)
    }

    private fun preprocessConfigFromJson(obj: JSONObject): PreprocessConfig {
        val thresholdMode = runCatching {
            ThresholdMode.valueOf(obj.optString("thresholdMode", ThresholdMode.OTSU.name))
        }.getOrDefault(ThresholdMode.OTSU)
        val noiseMode = runCatching {
            NoiseRemovalMode.valueOf(obj.optString("noiseRemovalMode", NoiseRemovalMode.MEDIAN.name))
        }.getOrDefault(NoiseRemovalMode.MEDIAN)

        return PreprocessConfig(
            thresholdMode = thresholdMode,
            fixedThreshold = obj.optInt("fixedThreshold", 140),
            noiseRemovalMode = noiseMode,
            filterSize = obj.optInt("filterSize", 3),
            scaleFactor = obj.optInt("scaleFactor", 3),
            borderPaddingPx = obj.optInt("borderPaddingPx", 16),
            invertOutput = obj.optBoolean("invertOutput", true),
            contrastClipPercent = obj.optDouble("contrastClipPercent", 0.02).toFloat(),
            minDimensionPx = obj.optInt("minDimensionPx", 8),
            maxDimensionPx = obj.optInt("maxDimensionPx", 2048)
        )
    }

    private fun stageToJson(stage: CaptchaLabStageArtifact): JSONObject = JSONObject().apply {
        put("stage", stage.stage.name)
        put("status", stage.status.name)
        put("width", stage.width ?: JSONObject.NULL)
        put("height", stage.height ?: JSONObject.NULL)
        put("relativePath", stage.relativePath ?: JSONObject.NULL)
        put("note", stage.note ?: JSONObject.NULL)
    }

    private fun stageFromJson(obj: JSONObject): CaptchaLabStageArtifact? {
        val kind = runCatching {
            CaptchaStageKind.valueOf(obj.getString("stage"))
        }.getOrNull() ?: return null
        val status = runCatching {
            CaptchaStageStatus.valueOf(obj.optString("status", CaptchaStageStatus.NOT_RUN.name))
        }.getOrDefault(CaptchaStageStatus.NOT_RUN)
        return CaptchaLabStageArtifact(
            stage = kind,
            status = status,
            width = obj.optNullableInt("width"),
            height = obj.optNullableInt("height"),
            relativePath = obj.optNullableString("relativePath"),
            note = obj.optNullableString("note")
        )
    }

    private fun attemptToJson(attempt: CaptchaLabAttemptRecord): JSONObject = JSONObject().apply {
        put("attemptNumber", attempt.attemptNumber)
        put("routeType", attempt.routeType)
        put("preprocessConfig", preprocessConfigToJson(attempt.preprocessConfig))
        put("decodeStatus", attempt.decodeStatus)
        put("stages", JSONArray().apply {
            attempt.stages.forEach { put(stageToJson(it)) }
        })
        put("mlKitInputWidth", attempt.mlKitInputWidth ?: JSONObject.NULL)
        put("mlKitInputHeight", attempt.mlKitInputHeight ?: JSONObject.NULL)
        put("rawOcrFullText", attempt.rawOcrFullText ?: JSONObject.NULL)
        put("elements", JSONArray().apply {
            attempt.elements.forEach { elem ->
                put(JSONObject().apply {
                    put("text", elem.text)
                    put("confidence", elem.confidence?.toDouble() ?: JSONObject.NULL)
                })
            }
        })
        put("elementCount", attempt.elementCount ?: JSONObject.NULL)
        put("normalizedDigits", attempt.normalizedDigits ?: JSONObject.NULL)
        put("validationPassed", attempt.validationPassed)
        put("rejectionCode", attempt.rejectionCode ?: JSONObject.NULL)
        put("failureReason", attempt.failureReason ?: JSONObject.NULL)
        put("exceptionType", attempt.exceptionType ?: JSONObject.NULL)
        put("mlKitErrorCode", attempt.mlKitErrorCode ?: JSONObject.NULL)
        put("preprocessDurationMs", attempt.preprocessDurationMs ?: JSONObject.NULL)
        put("ocrDurationMs", attempt.ocrDurationMs ?: JSONObject.NULL)
        put("totalDurationMs", attempt.totalDurationMs)
    }

    private fun attemptFromJson(obj: JSONObject): CaptchaLabAttemptRecord {
        val stagesArr = obj.optJSONArray("stages") ?: JSONArray()
        val stages = buildList {
            for (i in 0 until stagesArr.length()) {
                val stageObj = stagesArr.optJSONObject(i) ?: continue
                stageFromJson(stageObj)?.let(::add)
            }
        }
        val elementsArr = obj.optJSONArray("elements") ?: JSONArray()
        val elements = buildList {
            for (i in 0 until elementsArr.length()) {
                val eObj = elementsArr.optJSONObject(i) ?: continue
                val conf = if (eObj.isNull("confidence")) null else eObj.optDouble("confidence").toFloat()
                add(CaptchaLabElementRecord(text = eObj.optString("text", ""), confidence = conf))
            }
        }
        return CaptchaLabAttemptRecord(
            attemptNumber = obj.optInt("attemptNumber", 1),
            routeType = obj.optString("routeType", "PRIMARY"),
            preprocessConfig = obj.optJSONObject("preprocessConfig")?.let(::preprocessConfigFromJson)
                ?: PreprocessConfig(),
            decodeStatus = obj.optString("decodeStatus", "UNKNOWN"),
            stages = stages,
            mlKitInputWidth = obj.optNullableInt("mlKitInputWidth"),
            mlKitInputHeight = obj.optNullableInt("mlKitInputHeight"),
            rawOcrFullText = obj.optNullableString("rawOcrFullText"),
            elements = elements,
            elementCount = obj.optNullableInt("elementCount"),
            normalizedDigits = obj.optNullableString("normalizedDigits"),
            validationPassed = obj.optBoolean("validationPassed", false),
            rejectionCode = obj.optNullableString("rejectionCode"),
            failureReason = obj.optNullableString("failureReason"),
            exceptionType = obj.optNullableString("exceptionType"),
            mlKitErrorCode = obj.optNullableInt("mlKitErrorCode"),
            preprocessDurationMs = obj.optNullableLong("preprocessDurationMs"),
            ocrDurationMs = obj.optNullableLong("ocrDurationMs"),
            totalDurationMs = obj.optLong("totalDurationMs", 0L)
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)
}

object CaptchaLabConstants {
    const val MAX_RETAINED_CAPTCHAS = 100
}
