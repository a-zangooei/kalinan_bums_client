package com.example.data.captcha.lab

import android.content.Context
import android.graphics.Bitmap
import com.example.BuildConfig
import com.example.data.captcha.preprocess.GrayscaleImage
import com.example.data.captcha.solver.CaptchaDigitValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * رابط انتزاعی تبدیل تصویر به فایل PNG برای تفکیک منطق ذخیره از اندروید در تست‌های واحد.
 */
fun interface CaptchaPngEncoder {
    fun writePng(width: Int, height: Int, argbPixels: IntArray, destFile: File): Boolean
}

object AndroidBitmapPngEncoder : CaptchaPngEncoder {
    override fun writePng(width: Int, height: Int, argbPixels: IntArray, destFile: File): Boolean {
        if (width <= 0 || height <= 0 || argbPixels.size != width * height) return false
        var bmp: Bitmap? = null
        return try {
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(argbPixels, 0, width, 0, 0, width, height)
            destFile.parentFile?.mkdirs()
            val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
            FileOutputStream(tmpFile).use { fos ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    return false
                }
                fos.flush()
            }
            if (destFile.exists()) destFile.delete()
            tmpFile.renameTo(destFile)
        } catch (_: Throwable) {
            false
        } finally {
            bmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }
}

/**
 * مخزن محلی آزمایشگاه کپچا با ذخیره‌سازی ناهم‌زمان فایل‌های تصویری و فهرست JSON/JSONL (بدون Base64)
 * و نگهداری خودکار حداکثر ۱۰۰ کپچای اخیر.
 */
class CaptchaLabRepository internal constructor(
    val rootDir: File,
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val buildType: String,
    private val maxRetained: Int = CaptchaLabConstants.MAX_RETAINED_CAPTCHAS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pngEncoder: CaptchaPngEncoder = AndroidBitmapPngEncoder
) {
    constructor(context: Context) : this(
        rootDir = File(context.applicationContext.filesDir, "captcha_lab"),
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private val clearGeneration = AtomicLong(0L)

    val captchasDir: File get() = File(rootDir, "captchas")
    val indexFile: File get() = File(rootDir, "index.jsonl")
    val summaryFile: File get() = File(rootDir, "summary.json")

    private val _records = MutableStateFlow<List<CaptchaLabRecord>>(emptyList())
    val records: StateFlow<List<CaptchaLabRecord>> = _records.asStateFlow()

    @Volatile
    private var loadedFromDisk = false

    fun loadExistingAsync() {
        scope.launch {
            ensureLoaded()
        }
    }

    suspend fun ensureLoaded() {
        if (loadedFromDisk) return
        writeMutex.withLock {
            if (loadedFromDisk) return
            val loaded = readAllRecordsFromDisk()
            _records.value = loaded
            loadedFromDisk = true
        }
    }

    fun enqueueSave(session: CapturedCaptchaSession, stillActiveCheck: () -> Boolean) {
        val genAtEnqueue = clearGeneration.get()
        scope.launch {
            if (!stillActiveCheck()) return@launch
            saveSessionInternal(session, genAtEnqueue, stillActiveCheck)
        }
    }

    internal suspend fun saveSessionNow(session: CapturedCaptchaSession): CaptchaLabRecord? {
        return saveSessionInternal(session, clearGeneration.get()) { true }
    }

    private suspend fun saveSessionInternal(
        session: CapturedCaptchaSession,
        expectedClearGen: Long,
        stillActiveCheck: () -> Boolean
    ): CaptchaLabRecord? = writeMutex.withLock {
        try {
            if (!stillActiveCheck() || clearGeneration.get() != expectedClearGen) {
                return null
            }
            if (!loadedFromDisk) {
                _records.value = readAllRecordsFromDisk()
                loadedFromDisk = true
            }
            if (!stillActiveCheck() || clearGeneration.get() != expectedClearGen) {
                return null
            }

            val safeId = sanitizeId(session.captchaId)
            val captchaFolder = File(captchasDir, safeId).apply { mkdirs() }

            // 1. Save raw bytes in original server format (preserving .gif / .png / .jpg)
            val rawRelPath: String? = if (session.rawBytes.isNotEmpty()) {
                val rawFileName = "raw.${session.rawInspection.rawExtension}"
                val rawFile = File(captchaFolder, rawFileName)
                writeBytesAtomically(rawFile, session.rawBytes)
                "captchas/$safeId/$rawFileName"
            } else {
                null
            }

            // 2. Save each attempt's stage PNG images separately
            val attemptRecords = session.attempts.map { attempt ->
                val routeSlug = if (attempt.routeType == "PRIMARY") "primary" else "fallback_${attempt.attemptNumber - 1}"
                val attemptFolderName = "attempt_${attempt.attemptNumber}_$routeSlug"
                val attemptFolder = File(captchaFolder, attemptFolderName).apply { mkdirs() }

                val stageArtifacts = attempt.stages.map { stageBuf ->
                    val relPath = persistStageBuffer(
                        attemptFolder = attemptFolder,
                        relativePrefix = "captchas/$safeId/$attemptFolderName",
                        stageBuf = stageBuf
                    )
                    CaptchaLabStageArtifact(
                        stage = stageBuf.stage,
                        status = stageBuf.status,
                        width = stageBuf.width,
                        height = stageBuf.height,
                        relativePath = relPath,
                        note = stageBuf.note
                    )
                }

                CaptchaLabAttemptRecord(
                    attemptNumber = attempt.attemptNumber,
                    routeType = attempt.routeType,
                    preprocessConfig = attempt.preprocessConfig,
                    decodeStatus = attempt.decodeStatus,
                    stages = stageArtifacts,
                    mlKitInputWidth = attempt.mlKitInputWidth,
                    mlKitInputHeight = attempt.mlKitInputHeight,
                    rawOcrFullText = attempt.rawOcrFullText,
                    elements = attempt.elements.map { CaptchaLabElementRecord(it.text, it.confidence) },
                    elementCount = attempt.elementCount,
                    normalizedDigits = attempt.normalizedDigits,
                    validationPassed = attempt.validationPassed,
                    rejectionCode = attempt.rejectionCode,
                    failureReason = attempt.failureReason,
                    exceptionType = attempt.exceptionType,
                    mlKitErrorCode = attempt.mlKitErrorCode,
                    preprocessDurationMs = attempt.preprocessDurationMs,
                    ocrDurationMs = attempt.ocrDurationMs,
                    totalDurationMs = attempt.totalDurationMs
                )
            }

            val updatedGifInfo = session.rawInspection.gifInfo.copy(
                decodedFrameIndex = session.decodedFrameIndex
            )

            val record = CaptchaLabRecord(
                captchaId = safeId,
                capturedAtEpochMs = session.capturedAtEpochMs,
                capturedAtUtcIso = formatUtcIso(session.capturedAtEpochMs),
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                buildType = buildType,
                rawFormat = session.rawInspection.rawFormat,
                mimeType = session.rawInspection.mimeType,
                rawExtension = session.rawInspection.rawExtension,
                rawByteSize = session.rawBytes.size,
                rawWidth = session.rawWidth ?: session.rawInspection.headerWidth,
                rawHeight = session.rawHeight ?: session.rawInspection.headerHeight,
                rawRelativePath = rawRelPath,
                gifInfo = updatedGifInfo,
                decodeStatus = session.decodeStatus,
                outcomeCategory = session.outcomeCategory,
                finalStatus = session.finalStatus,
                finalDigits = session.finalDigits,
                failureReason = session.failureReason,
                lastRejectionCode = session.lastRejectionCode,
                exceptionType = session.exceptionType,
                mlKitErrorCode = session.mlKitErrorCode,
                totalDurationMs = session.totalDurationMs,
                attempts = attemptRecords
            )

            val metadataFile = File(captchaFolder, "metadata.json")
            writeTextAtomically(metadataFile, CaptchaLabJson.recordToJson(record).toString(2))

            // Insert newest-first and evict oldest beyond maxRetained
            val nextList = (_records.value.filterNot { it.captchaId == safeId } + record)
                .sortedByDescending { it.capturedAtEpochMs }
                .toMutableList()

            while (nextList.size > maxRetained) {
                val evicted = nextList.removeAt(nextList.lastIndex)
                File(captchasDir, sanitizeId(evicted.captchaId)).deleteRecursively()
            }

            _records.value = nextList
            rewriteIndexAndSummary(nextList)
            record
        } catch (_: Throwable) {
            // Isolate storage errors completely so OCR/login flow is never interrupted
            null
        }
    }

    fun deleteRecordAsync(captchaId: String) {
        scope.launch { deleteRecord(captchaId) }
    }

    suspend fun updateStageBenchmark(
        captchaId: String,
        benchmark: CaptchaStageBenchmarkResult
    ): CaptchaLabRecord? = writeMutex.withLock {
        if (!loadedFromDisk) {
            _records.value = readAllRecordsFromDisk()
            loadedFromDisk = true
        }
        val safeId = sanitizeId(captchaId)
        val existing = _records.value.firstOrNull { it.captchaId == safeId } ?: return@withLock null
        val recalculated = CaptchaStageBenchmarkEngine.recalculateWithExpectedDigits(
            benchmark = benchmark,
            expectedDigits = existing.expectedDigits
        )
        val updatedRecord = existing.copy(stageBenchmark = recalculated)
        persistUpdatedRecordLocked(updatedRecord)
        updatedRecord
    }

    suspend fun updateExpectedDigits(
        captchaId: String,
        expectedDigits: String?
    ): CaptchaLabRecord? = writeMutex.withLock {
        if (!loadedFromDisk) {
            _records.value = readAllRecordsFromDisk()
            loadedFromDisk = true
        }
        val safeId = sanitizeId(captchaId)
        val existing = _records.value.firstOrNull { it.captchaId == safeId } ?: return@withLock null
        val normalizedExpected = expectedDigits
            ?.let { CaptchaDigitValidator.extractNormalizedDigits(it) }
            ?.takeIf { it.length == CaptchaDigitValidator.REQUIRED_DIGIT_COUNT }
        val updatedBench = existing.stageBenchmark?.let { bench ->
            CaptchaStageBenchmarkEngine.recalculateWithExpectedDigits(bench, normalizedExpected)
        }
        val updatedRecord = existing.copy(
            expectedDigits = normalizedExpected,
            stageBenchmark = updatedBench
        )
        persistUpdatedRecordLocked(updatedRecord)
        updatedRecord
    }

    private fun persistUpdatedRecordLocked(updatedRecord: CaptchaLabRecord) {
        val safeId = sanitizeId(updatedRecord.captchaId)
        val metadataFile = File(File(captchasDir, safeId).apply { mkdirs() }, "metadata.json")
        writeTextAtomically(metadataFile, CaptchaLabJson.recordToJson(updatedRecord).toString(2))
        val nextList = _records.value.map { rec ->
            if (rec.captchaId == safeId) updatedRecord else rec
        }
        _records.value = nextList
        rewriteIndexAndSummary(nextList)
    }

    suspend fun deleteRecord(captchaId: String) = writeMutex.withLock {
        if (!loadedFromDisk) {
            _records.value = readAllRecordsFromDisk()
            loadedFromDisk = true
        }
        val safeId = sanitizeId(captchaId)
        File(captchasDir, safeId).deleteRecursively()
        val updated = _records.value.filterNot { it.captchaId == safeId }
        _records.value = updated
        rewriteIndexAndSummary(updated)
    }

    fun clearAllAsync() {
        clearGeneration.incrementAndGet()
        scope.launch { clearAll() }
    }

    suspend fun clearAll() {
        clearGeneration.incrementAndGet()
        writeMutex.withLock {
            runCatching {
                captchasDir.deleteRecursively()
                if (indexFile.exists()) indexFile.delete()
                if (summaryFile.exists()) summaryFile.delete()
            }
            _records.value = emptyList()
            loadedFromDisk = true
        }
    }

    fun resolveFile(relativePath: String?): File? {
        if (relativePath.isNullOrBlank()) return null
        val normalized = relativePath.trimStart('/')
        if (normalized.contains("..")) return null
        val target = File(rootDir, normalized)
        return target.takeIf { it.exists() && it.isFile }
    }

    private fun persistStageBuffer(
        attemptFolder: File,
        relativePrefix: String,
        stageBuf: CapturedStageBuffer
    ): String? {
        if (stageBuf.status != CaptchaStageStatus.COMPLETED) return null
        val fileName = "${stageBuf.stage.fileBaseName}.png"
        val destFile = File(attemptFolder, fileName)

        val ok = when {
            stageBuf.argbSnapshot != null -> {
                val snap = stageBuf.argbSnapshot
                pngEncoder.writePng(snap.width, snap.height, snap.argbPixels, destFile)
            }
            stageBuf.grayscaleImage != null -> {
                val gray = stageBuf.grayscaleImage
                val argb = grayscaleToArgb(gray)
                pngEncoder.writePng(gray.width, gray.height, argb, destFile)
            }
            else -> false
        }
        return if (ok) "$relativePrefix/$fileName" else null
    }

    private fun grayscaleToArgb(image: GrayscaleImage): IntArray {
        val src = image.pixels
        val out = IntArray(src.size)
        for (i in src.indices) {
            val v = src[i].coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    private fun readAllRecordsFromDisk(): List<CaptchaLabRecord> {
        if (!captchasDir.exists() || !captchasDir.isDirectory) return emptyList()
        val parsed = mutableListOf<CaptchaLabRecord>()
        captchasDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val metaFile = File(dir, "metadata.json")
            if (!metaFile.exists()) return@forEach
            runCatching {
                val jsonText = metaFile.readText(Charsets.UTF_8)
                val rec = CaptchaLabJson.recordFromJson(org.json.JSONObject(jsonText))
                parsed.add(rec)
            }
        }
        val sorted = parsed.sortedByDescending { it.capturedAtEpochMs }
        if (sorted.size > maxRetained) {
            val kept = sorted.take(maxRetained)
            sorted.drop(maxRetained).forEach { old ->
                File(captchasDir, sanitizeId(old.captchaId)).deleteRecursively()
            }
            rewriteIndexAndSummary(kept)
            return kept
        }
        return sorted
    }

    private fun rewriteIndexAndSummary(records: List<CaptchaLabRecord>) {
        runCatching {
            rootDir.mkdirs()
            val jsonlBuilder = StringBuilder()
            records.forEach { rec ->
                jsonlBuilder.appendLine(CaptchaLabJson.recordToJson(rec).toString())
            }
            writeTextAtomically(indexFile, jsonlBuilder.toString())

            val stats = computeSummaryStats(records)
            val compatibility = CaptchaStageBenchmarkEngine.analyzeCompatibility(records)
            val summaryJson = CaptchaLabJson.buildSummaryJson(
                records = records,
                stats = stats,
                exportedAtUtcIso = formatUtcIso(System.currentTimeMillis()),
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                buildType = buildType,
                compatibilitySummary = compatibility
            )
            writeTextAtomically(summaryFile, summaryJson.toString(2))
        }
    }

    private fun writeTextAtomically(destFile: File, text: String) {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (destFile.exists()) destFile.delete()
        tmp.renameTo(destFile)
    }

    private fun writeBytesAtomically(destFile: File, bytes: ByteArray) {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        if (destFile.exists()) destFile.delete()
        tmp.renameTo(destFile)
    }

    private fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "captcha_${System.nanoTime()}" }

    companion object {
        fun formatUtcIso(epochMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return fmt.format(Date(epochMs))
        }

        fun computeSummaryStats(records: List<CaptchaLabRecord>): CaptchaLabSummaryStats {
            val total = records.size
            val fallbackSuccess = records.count { it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_FALLBACK }
            val success = records.count {
                it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_PRIMARY ||
                    it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_FALLBACK
            }
            val failed = total - success
            val noText = records.count { it.outcomeCategory == CaptchaLabOutcomeCategory.NO_TEXT_DETECTED }
            val rate = if (total == 0) 0 else ((success * 100.0) / total).toInt()
            return CaptchaLabSummaryStats(
                totalCount = total,
                successCount = success,
                failedCount = failed,
                noTextDetectedCount = noText,
                fallbackSuccessCount = fallbackSuccess,
                successRatePercent = rate
            )
        }

        fun filterAndSort(
            records: List<CaptchaLabRecord>,
            filter: CaptchaLabFilter,
            sort: CaptchaLabSort
        ): List<CaptchaLabRecord> {
            val filtered = when (filter) {
                CaptchaLabFilter.ALL -> records
                CaptchaLabFilter.SUCCESS -> records.filter {
                    it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_PRIMARY ||
                        it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_FALLBACK
                }
                CaptchaLabFilter.FAILED -> records.filter {
                    it.outcomeCategory != CaptchaLabOutcomeCategory.SUCCESS_PRIMARY &&
                        it.outcomeCategory != CaptchaLabOutcomeCategory.SUCCESS_FALLBACK
                }
                CaptchaLabFilter.NO_TEXT_DETECTED -> records.filter {
                    it.outcomeCategory == CaptchaLabOutcomeCategory.NO_TEXT_DETECTED
                }
                CaptchaLabFilter.HAS_TEXT_INVALID -> records.filter { rec ->
                    rec.outcomeCategory == CaptchaLabOutcomeCategory.VALIDATION_REJECTED ||
                        rec.attempts.any { !it.validationPassed && !it.rawOcrFullText.isNullOrBlank() }
                }
                CaptchaLabFilter.ERROR_PIPELINE -> records.filter {
                    it.outcomeCategory == CaptchaLabOutcomeCategory.DECODE_ERROR ||
                        it.outcomeCategory == CaptchaLabOutcomeCategory.PREPROCESS_ERROR ||
                        it.outcomeCategory == CaptchaLabOutcomeCategory.MLKIT_ERROR ||
                        it.outcomeCategory == CaptchaLabOutcomeCategory.TIMEOUT ||
                        it.outcomeCategory == CaptchaLabOutcomeCategory.EMPTY_INPUT
                }
                CaptchaLabFilter.FALLBACK_SUCCESS -> records.filter {
                    it.outcomeCategory == CaptchaLabOutcomeCategory.SUCCESS_FALLBACK
                }
            }

            return when (sort) {
                CaptchaLabSort.NEWEST_FIRST -> filtered.sortedByDescending { it.capturedAtEpochMs }
                CaptchaLabSort.OLDEST_FIRST -> filtered.sortedBy { it.capturedAtEpochMs }
                CaptchaLabSort.BY_OUTCOME -> filtered.sortedWith(
                    compareBy<CaptchaLabRecord> { it.outcomeCategory.ordinal }
                        .thenByDescending { it.capturedAtEpochMs }
                )
            }
        }
    }
}

/**
 * کنترلر سراسری نشست آزمایشگاه کپچا.
 *
 * قانون قطعی حیات:
 * - وضعیت بایگانی در هر اجرای جدید برنامه (`init`) به‌صورت خودکار به `false` (خاموش) ریست می‌شود.
 * - فقط تلاش‌هایی ثبت می‌شوند که پس از روشن‌شدن کلید بایگانی آغاز شوند.
 * - با خاموش‌کردن کلید یا خروج از حالت توسعه‌دهنده، ثبت اطلاعات فوراً متوقف می‌شود.
 */
object CaptchaLabController : CaptchaLabObserver {

    private val _isArchivingEnabled = MutableStateFlow(false)
    val isArchivingEnabled: StateFlow<Boolean> = _isArchivingEnabled.asStateFlow()

    private val _batchProgress = MutableStateFlow(BatchBenchmarkProgressState())
    val batchProgress: StateFlow<BatchBenchmarkProgressState> = _batchProgress.asStateFlow()

    private val _activeSingleBenchmarkIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSingleBenchmarkIds: StateFlow<Set<String>> = _activeSingleBenchmarkIds.asStateFlow()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val benchmarkEngine = CaptchaStageBenchmarkEngine()
    private var batchJob: Job? = null

    @Volatile
    private var repository: CaptchaLabRepository? = null

    @Volatile
    private var processInitialized: Boolean = false

    fun init(context: Context) {
        val repo = repository ?: CaptchaLabRepository(context).also { repository = it }
        if (!processInitialized) {
            _isArchivingEnabled.value = false
            processInitialized = true
            repo.loadExistingAsync()
        }
    }

    fun getRepository(context: Context): CaptchaLabRepository {
        return repository ?: CaptchaLabRepository(context).also {
            repository = it
            it.loadExistingAsync()
        }
    }

    fun getRepositoryOrNull(): CaptchaLabRepository? = repository

    fun setArchivingEnabled(enabled: Boolean) {
        _isArchivingEnabled.value = enabled
    }

    fun updateExpectedDigitsAsync(
        context: Context,
        captchaId: String,
        expectedDigits: String?,
        onComplete: ((CaptchaLabRecord?) -> Unit)? = null
    ) {
        val repo = getRepository(context)
        controllerScope.launch {
            val updated = repo.updateExpectedDigits(captchaId, expectedDigits)
            onComplete?.let { cb ->
                withContext(Dispatchers.Main) { cb(updated) }
            }
        }
    }

    fun runSingleBenchmarkAsync(
        context: Context,
        captchaId: String,
        attemptNumber: Int? = null,
        onComplete: ((CaptchaStageBenchmarkResult?) -> Unit)? = null
    ) {
        val repo = getRepository(context)
        if (_activeSingleBenchmarkIds.value.contains(captchaId)) return
        _activeSingleBenchmarkIds.value = _activeSingleBenchmarkIds.value + captchaId

        controllerScope.launch {
            var savedResult: CaptchaStageBenchmarkResult? = null
            try {
                repo.ensureLoaded()
                val record = repo.records.value.firstOrNull { it.captchaId == captchaId }
                if (record != null) {
                    val result = benchmarkEngine.benchmarkRecord(
                        repository = repo,
                        record = record,
                        attemptNumber = attemptNumber
                    )
                    repo.updateStageBenchmark(captchaId, result)
                    savedResult = result
                }
            } catch (_: Throwable) {
                // Keep UI responsive and isolated if ML Kit or decoding fails unexpectedly
            } finally {
                _activeSingleBenchmarkIds.value = _activeSingleBenchmarkIds.value - captchaId
                onComplete?.let { cb ->
                    withContext(Dispatchers.Main) { cb(savedResult) }
                }
            }
        }
    }

    fun startBatchBenchmark(
        context: Context,
        targetRecords: List<CaptchaLabRecord>,
        scopeLabel: String,
        onFinished: ((completedCount: Int, cancelled: Boolean) -> Unit)? = null
    ) {
        if (_batchProgress.value.isRunning || targetRecords.isEmpty()) return
        val repo = getRepository(context)
        batchJob?.cancel()
        _batchProgress.value = BatchBenchmarkProgressState(
            isRunning = true,
            scopeLabel = scopeLabel,
            completedCount = 0,
            totalCount = targetRecords.size,
            currentCaptchaId = targetRecords.firstOrNull()?.captchaId
        )

        batchJob = controllerScope.launch {
            var completed = 0
            var cancelled = false
            try {
                repo.ensureLoaded()
                benchmarkEngine.benchmarkBatch(repo, targetRecords) { done, total, currentId, _ ->
                    completed = done
                    _batchProgress.value = BatchBenchmarkProgressState(
                        isRunning = true,
                        scopeLabel = scopeLabel,
                        completedCount = done,
                        totalCount = total,
                        currentCaptchaId = currentId
                    )
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (_: Throwable) {
                // Partial results completed prior to error are already persisted per record
            } finally {
                _batchProgress.value = BatchBenchmarkProgressState(isRunning = false)
                onFinished?.let { cb ->
                    withContext(Dispatchers.Main) { cb(completed, cancelled) }
                }
            }
        }
    }

    fun cancelBatchBenchmark() {
        batchJob?.cancel()
        batchJob = null
    }

    override fun isArchivingEnabled(): Boolean = _isArchivingEnabled.value

    override fun onSolveCompleted(session: CapturedCaptchaSession) {
        if (!_isArchivingEnabled.value) return
        val repo = repository ?: return
        repo.enqueueSave(session) { _isArchivingEnabled.value }
    }
}
