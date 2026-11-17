package com.example.data.captcha.lab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.logger.AppLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * خروجی استاندارد فایل ZIP آزمایشگاه کپچا با ساختار آماده تحلیل مستقیم توسط اسکریپت‌های پایتون (`OpenCV` / `PIL` / `pandas`).
 */
object CaptchaLabZipExporter {

    fun exportToZipFile(
        repository: CaptchaLabRepository,
        records: List<CaptchaLabRecord>,
        destZipFile: File,
        appVersionName: String = BuildConfig.VERSION_NAME,
        appVersionCode: Int = BuildConfig.VERSION_CODE,
        buildType: String = BuildConfig.BUILD_TYPE
    ): Boolean {
        return try {
            destZipFile.parentFile?.mkdirs()
            val tmpFile = File(destZipFile.parentFile, "${destZipFile.name}.tmp")
            val stats = CaptchaLabRepository.computeSummaryStats(records)
            val compatibility = CaptchaStageBenchmarkEngine.analyzeCompatibility(records)
            val exportedUtc = CaptchaLabRepository.formatUtcIso(System.currentTimeMillis())

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
                // 1. README.md
                writeZipTextEntry(zos, "README.md", buildReadmeMarkdown(stats, compatibility, exportedUtc, appVersionName, appVersionCode, buildType))

                // 2. summary.json
                val summaryJson = CaptchaLabJson.buildSummaryJson(
                    records = records,
                    stats = stats,
                    exportedAtUtcIso = exportedUtc,
                    appVersionName = appVersionName,
                    appVersionCode = appVersionCode,
                    buildType = buildType,
                    compatibilitySummary = compatibility
                )
                writeZipTextEntry(zos, "summary.json", summaryJson.toString(2))

                // 3. index.jsonl (no Base64 blobs, relative paths only)
                val indexContent = buildString {
                    records.forEach { rec ->
                        appendLine(CaptchaLabJson.recordToJson(rec).toString())
                    }
                }
                writeZipTextEntry(zos, "index.jsonl", indexContent)

                // 4. Per-captcha folders (captchas/<captchaId>/metadata.json, raw.<ext>, attempt_*/...png)
                records.forEach { rec ->
                    val folderPrefix = "captchas/${rec.captchaId}"
                    writeZipTextEntry(
                        zos,
                        "$folderPrefix/metadata.json",
                        CaptchaLabJson.recordToJson(rec).toString(2)
                    )

                    repository.resolveFile(rec.rawRelativePath)?.let { rawFile ->
                        writeZipFileEntry(zos, rec.rawRelativePath!!, rawFile)
                    }

                    rec.attempts.forEach { attempt ->
                        attempt.stages.forEach { stage ->
                            val relPath = stage.relativePath
                            if (stage.status == CaptchaStageStatus.COMPLETED && !relPath.isNullOrBlank()) {
                                repository.resolveFile(relPath)?.let { stageFile ->
                                    writeZipFileEntry(zos, relPath, stageFile)
                                }
                            }
                        }
                    }
                }
            }

            if (destZipFile.exists()) destZipFile.delete()
            tmpFile.renameTo(destZipFile)
        } catch (e: Throwable) {
            AppLogger.logWarn(
                component = "CaptchaLabZipExporter",
                operation = "ExportFailed",
                message = "Failed to export Captcha Lab ZIP: ${e.message}",
                throwable = e
            )
            false
        }
    }

    fun shareZip(context: Context, repository: CaptchaLabRepository, records: List<CaptchaLabRecord>) {
        if (records.isEmpty()) {
            Toast.makeText(context, "بایگانی کپچا خالی است.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(exportDir, "kalinan_captcha_lab_$timestamp.zip")
            val ok = exportToZipFile(repository, records, zipFile)
            if (!ok || !zipFile.exists()) {
                Toast.makeText(context, "ساخت فایل ZIP آزمایشگاه ناموفق بود.", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                zipFile
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Kalinan Captcha Lab Dataset (${records.size} captchas)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "ارسال بسته آزمایشگاه کپچا (ZIP)"))
        } catch (e: Exception) {
            Toast.makeText(context, "خطا در اشتراک‌گذاری ZIP: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun saveZipToUri(
        context: Context,
        uri: Uri,
        repository: CaptchaLabRepository,
        records: List<CaptchaLabRecord>
    ) {
        if (records.isEmpty()) {
            Toast.makeText(context, "بایگانی کپچا خالی است.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val tmpZip = File(exportDir, "kalinan_captcha_lab_save_tmp.zip")
            val ok = exportToZipFile(repository, records, tmpZip)
            if (!ok || !tmpZip.exists()) {
                Toast.makeText(context, "ایجاد فایل ZIP ناموفق بود.", Toast.LENGTH_SHORT).show()
                return
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(tmpZip).use { input ->
                    input.copyTo(out)
                }
            }
            Toast.makeText(context, "بایگانی آزمایشگاه کپچا با موفقیت ذخیره شد.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "خطا در ذخیره فایل ZIP: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeZipTextEntry(zos: ZipOutputStream, entryName: String, content: String) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun writeZipFileEntry(zos: ZipOutputStream, entryName: String, file: File) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input ->
            input.copyTo(zos)
        }
        zos.closeEntry()
    }

    internal fun buildReadmeMarkdown(
        stats: CaptchaLabSummaryStats,
        compatibility: StageCompatibilitySummary,
        exportedAtUtcIso: String,
        appVersionName: String,
        appVersionCode: Int,
        buildType: String
    ): String = """
        |# Kalinan Captcha Lab Archive
        |
        |Exported at (UTC): $exportedAtUtcIso
        |App Version: $appVersionName (code $appVersionCode, buildType=$buildType)
        |Total Captchas: ${stats.totalCount} | Success: ${stats.successCount} (${stats.successRatePercent}%) | Failed: ${stats.failedCount} | NO_TEXT_DETECTED: ${stats.noTextDetectedCount}
        |Benchmarked Captchas: ${compatibility.benchmarkedCaptchasCount} | Manually Labeled: ${compatibility.labeledCaptchasCount} | Recommended Best Stage: ${compatibility.bestStageKey ?: "N/A"}
        |
        |> Privacy Scope: This dataset contains only server captcha images, preprocessing pipeline outputs, OCR diagnostics, stage benchmark metrics, and timing metadata captured while Captcha Lab archiving was explicitly enabled. It never contains passwords, usernames, cookies, session tokens, or ViewState.
        |
        |## Directory Layout
        |
        |```
        |README.md
        |summary.json
        |index.jsonl
        |captchas/
        |  <captchaId>/
        |    metadata.json
        |    raw.<ext>                  # Exact untouched server bytes (.gif, .png, .jpg, ...)
        |    attempt_1_primary/
        |      01_decoded.png
        |      02_grayscale.png
        |      03_contrast_enhanced.png
        |      04_thresholded.png
        |      05_denoised.png
        |      06_inverted.png          # Omitted if status == SKIPPED or NOT_RUN
        |      07_upscaled.png
        |      08_final_padded_mlkit_input.png   # Exact bitmap delivered to ML Kit OCR
        |    attempt_2_fallback_1/ ...
        |    attempt_3_fallback_2/ ...
        |```
        |
        |## Field Guide (`index.jsonl`, `metadata.json` & `summary.json`)
        |
        |- `captchaId`: Unique UUID linking raw bytes + all primary/fallback attempts of one captcha solve.
        |- `capturedAtUtcIso` / `capturedAtEpochMs`: Solve start timestamp.
        |- `rawFormat` / `mimeType` / `rawExtension` / `rawWidth` / `rawHeight` / `rawRelativePath`: Original server payload details.
        |- `gifInfo`: Binary GIF inspection (`isGif`, `gifVersion`, `isAnimatedGif`, `frameCount`, `logicalWidth`, `logicalHeight`, `decodedFrameIndex`, `note`).
        |- `expectedDigits`: Optional user-entered 4-digit ground truth for computing exact-match stage accuracy (`null` if unlabeled).
        |- `stageBenchmark`: Optional 9-target benchmark (`RAW` + `DECODED`..`FINAL_PADDED`) containing `bestTargetKey`, `totalDurationMs`, and `targets[]` (`rawOcrFullText`, `elements`, `normalizedDigits`, `validationPassed`, `rejectionCode`, `matchesExpectedDigits`, `averageConfidence`, `ocrDurationMs`).
        |- `summary.json -> stageCompatibilityLeaderboard[]`: Ranked stage comparison (`rank`, `targetKey`, `valid4DigitRatePercent`, `exactMatchRatePercent`, `averageConfidence`, `noTextRatePercent`, `averageOcrDurationMs`, `compatibilityScore`, `isRecommendedBest`).
        |- `attempts[]`:
        |  - `attemptNumber`, `routeType` (`PRIMARY` or `FALLBACK`), `preprocessConfig`
        |  - `stages[]`: Each stage has `stage`, `status` (`COMPLETED`, `FAILED`, `SKIPPED`, `NOT_RUN`), `width`, `height`, `relativePath`
        |  - `mlKitInputWidth`, `mlKitInputHeight`: Exact dimensions passed to ML Kit (`08_final_padded_mlkit_input.png`)
        |  - `rawOcrFullText`: Untouched `Text.text` returned by ML Kit (or `null` if OCR did not run)
        |  - `elements[]`: List of `{text, confidence}` (`confidence` is genuine `Float` or `null` when unavailable)
        |  - `normalizedDigits`, `validationPassed`, `rejectionCode`, `failureReason`
        |  - `preprocessDurationMs`, `ocrDurationMs`, `totalDurationMs`
        |
        |## Quick Python Analysis Example
        |
        |```python
        |import json
        |from pathlib import Path
        |from PIL import Image
        |
        |root = Path(".")
        |summary = json.loads((root / "summary.json").read_text(encoding="utf-8"))
        |print("Best stage:", summary.get("recommendedBestStageKey"))
        |for row in summary.get("stageCompatibilityLeaderboard", []):
        |    print(row["rank"], row["targetKey"], row["compatibilityScore"], row["valid4DigitRatePercent"], row["exactMatchRatePercent"])
        |
        |records = [json.loads(line) for line in (root / "index.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        |for rec in records:
        |    bench = rec.get("stageBenchmark")
        |    if bench:
        |        print(rec["captchaId"], "best=", bench["bestTargetKey"], "expected=", rec.get("expectedDigits"))
        |```
        |""".trimMargin()
}
