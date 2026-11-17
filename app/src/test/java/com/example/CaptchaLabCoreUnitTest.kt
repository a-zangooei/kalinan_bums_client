package com.example

import com.example.data.captcha.lab.CaptchaBenchmarkTargetStatus
import com.example.data.captcha.lab.CaptchaLabConstants
import com.example.data.captcha.lab.CaptchaLabFilter
import com.example.data.captcha.lab.CaptchaLabJson
import com.example.data.captcha.lab.CaptchaLabObserver
import com.example.data.captcha.lab.CaptchaLabOutcomeCategory
import com.example.data.captcha.lab.CaptchaLabRepository
import com.example.data.captcha.lab.CaptchaLabSort
import com.example.data.captcha.lab.CaptchaLabZipExporter
import com.example.data.captcha.lab.CaptchaStageBenchmarkEngine
import com.example.data.captcha.lab.CaptchaStageKind
import com.example.data.captcha.lab.CaptchaStageStatus
import com.example.data.captcha.lab.CapturedAttemptPayload
import com.example.data.captcha.lab.CapturedCaptchaSession
import com.example.data.captcha.lab.CapturedStageBuffer
import com.example.data.captcha.lab.DecodedArgbSnapshot
import com.example.data.captcha.lab.DecodedBenchmarkFrame
import com.example.data.captcha.lab.GifFormatInspector
import com.example.data.captcha.lab.TracedPreprocessResult
import com.example.data.captcha.preprocess.CaptchaPreprocessPipeline
import com.example.data.captcha.preprocess.GrayscaleImage
import com.example.data.captcha.preprocess.NoiseRemovalMode
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.preprocess.ThresholdMode
import com.example.data.captcha.solver.CaptchaFrame
import com.example.data.captcha.solver.CaptchaResult
import com.example.data.captcha.solver.CaptchaSolverConfig
import com.example.data.captcha.solver.MlKitCaptchaSolver
import com.example.data.captcha.solver.OcrElement
import com.example.data.captcha.solver.OcrOutput
import com.example.data.captcha.solver.OcrSession
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalCoroutinesApi::class)
class CaptchaLabCoreUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeFrame : CaptchaFrame {
        override fun release() = Unit
    }

    private class RecordingObserver(var enabled: Boolean) : CaptchaLabObserver {
        val captured = mutableListOf<CapturedCaptchaSession>()
        override fun isArchivingEnabled(): Boolean = enabled
        override fun onSolveCompleted(session: CapturedCaptchaSession) {
            if (enabled) captured.add(session)
        }
    }

    private fun buildSyntheticGif(frameCount: Int, w: Int = 120, h: Int = 40): ByteArray {
        val out = mutableListOf<Byte>()
        // GIF89a header
        "GIF89a".encodeToByteArray().forEach { out.add(it) }
        out.add((w and 0xFF).toByte())
        out.add(((w ushr 8) and 0xFF).toByte())
        out.add((h and 0xFF).toByte())
        out.add(((h ushr 8) and 0xFF).toByte())
        out.add(0x00) // No GCT
        out.add(0x00)
        out.add(0x00)

        repeat(frameCount) {
            out.add(0x2C) // Image descriptor
            repeat(8) { out.add(0x00) } // x,y,w,h
            out.add(0x00) // No LCT
            out.add(0x02) // LZW min code size
            out.add(0x02) // sub-block length
            out.add(0x4C)
            out.add(0x01)
            out.add(0x00) // block terminator
        }
        out.add(0x3B) // Trailer
        return out.toByteArray()
    }

    private fun fakeTracedSuccess(w: Int = 20, h: Int = 10): TracedPreprocessResult {
        val gray = GrayscaleImage(w, h, IntArray(w * h) { 128 })
        val snap = DecodedArgbSnapshot(w, h, IntArray(w * h) { -1 })
        val stages = CaptchaStageKind.entries.map { kind ->
            if (kind == CaptchaStageKind.DECODED) {
                CapturedStageBuffer(kind, CaptchaStageStatus.COMPLETED, w, h, argbSnapshot = snap)
            } else {
                CapturedStageBuffer(kind, CaptchaStageStatus.COMPLETED, w, h, grayscaleImage = gray)
            }
        }
        return TracedPreprocessResult(
            frame = FakeFrame(),
            decodeStatus = "SUCCESS",
            decodedWidth = w,
            decodedHeight = h,
            mlKitInputWidth = w,
            mlKitInputHeight = h,
            stages = stages,
            failureReason = null,
            preprocessDurationMs = 4L
        )
    }

    @Test
    fun gifInspector_detectsStaticAndAnimatedGifFramesCorrectly() {
        val singleGif = buildSyntheticGif(frameCount = 1, w = 130, h = 45)
        val singleInfo = GifFormatInspector.inspect(singleGif)
        assertEquals("GIF", singleInfo.rawFormat)
        assertEquals("image/gif", singleInfo.mimeType)
        assertTrue(singleInfo.gifInfo.isGif)
        assertFalse(singleInfo.gifInfo.isAnimatedGif)
        assertEquals(1, singleInfo.gifInfo.frameCount)
        assertEquals(130, singleInfo.gifInfo.logicalWidth)
        assertEquals(45, singleInfo.gifInfo.logicalHeight)

        val animatedGif = buildSyntheticGif(frameCount = 4, w = 140, h = 50)
        val animInfo = GifFormatInspector.inspect(animatedGif)
        assertTrue(animInfo.gifInfo.isGif)
        assertTrue(animInfo.gifInfo.isAnimatedGif)
        assertEquals(4, animInfo.gifInfo.frameCount)
    }

    @Test
    fun pipeline_processWithStagesMatchesProcessExactly() {
        val gray = GrayscaleImage(
            width = 16,
            height = 12,
            pixels = IntArray(16 * 12) { idx -> if (idx % 3 == 0) 230 else 40 }
        )

        val cfgDefault = PreprocessConfig()
        val expected1 = CaptchaPreprocessPipeline.process(gray, cfgDefault)
        val traced1 = CaptchaPreprocessPipeline.processWithStages(gray, cfgDefault)
        assertEquals(expected1, traced1.finalPadded)
        assertEquals(CaptchaStageStatus.COMPLETED.let { true }, traced1.inverted != null)
        assertFalse(traced1.invertSkipped)

        val cfgNoInvert = PreprocessConfig(
            thresholdMode = ThresholdMode.FIXED,
            fixedThreshold = 135,
            noiseRemovalMode = NoiseRemovalMode.MORPHOLOGICAL_OPEN,
            invertOutput = false
        )
        val expected2 = CaptchaPreprocessPipeline.process(gray, cfgNoInvert)
        val traced2 = CaptchaPreprocessPipeline.processWithStages(gray, cfgNoInvert)
        assertEquals(expected2, traced2.finalPadded)
        assertTrue(traced2.invertSkipped)
        assertNull(traced2.inverted)
    }

    @Test
    fun solver_respectsActivationState_andCapturesAllOutcomesAndNullConfidence() = runTest {
        val observer = RecordingObserver(enabled = false)
        val gifBytes = buildSyntheticGif(frameCount = 2)

        val solverOff = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = StandardTestDispatcher(testScheduler),
            framePreprocessor = { _, _ -> FakeFrame() },
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame) = OcrOutput("1234", listOf(OcrElement("1234", 0.9f)))
                    override fun close() = Unit
                }
            },
            labObserver = observer,
            tracedFramePreprocessor = { _, _ -> fakeTracedSuccess() }
        )

        // When OFF -> zero recordings
        val offResult = solverOff.solve(gifBytes)
        assertTrue(offResult is CaptchaResult.Success)
        assertEquals(0, observer.captured.size)

        // When ON -> records primary success & preserves null confidence
        observer.enabled = true
        val solverNullConf = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = StandardTestDispatcher(testScheduler),
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame) =
                        OcrOutput("۴ ۸ ۲ ۹", listOf(OcrElement("۴۸۲۹", null)))
                    override fun close() = Unit
                }
            },
            labObserver = observer,
            tracedFramePreprocessor = { _, _ -> fakeTracedSuccess() }
        )
        val resPrimary = solverNullConf.solve(gifBytes)
        assertEquals(CaptchaResult.Success("4829"), resPrimary)
        assertEquals(1, observer.captured.size)
        val capPrimary = observer.captured.last()
        assertEquals(CaptchaLabOutcomeCategory.SUCCESS_PRIMARY, capPrimary.outcomeCategory)
        assertEquals("۴ ۸ ۲ ۹", capPrimary.attempts.single().rawOcrFullText)
        assertNull(capPrimary.attempts.single().elements.single().confidence)

        // NO_TEXT_DETECTED across all 3 attempts
        val solverNoText = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = StandardTestDispatcher(testScheduler),
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame) = OcrOutput("", emptyList())
                    override fun close() = Unit
                }
            },
            labObserver = observer,
            tracedFramePreprocessor = { _, _ -> fakeTracedSuccess() }
        )
        solverNoText.solve(gifBytes)
        val capNoText = observer.captured.last()
        assertEquals(CaptchaLabOutcomeCategory.NO_TEXT_DETECTED, capNoText.outcomeCategory)
        assertEquals(3, capNoText.attempts.size)

        // Conflicting fallback candidates (<4 then 1234 vs 5678)
        val solverConflict = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = StandardTestDispatcher(testScheduler),
            sessionFactory = {
                object : OcrSession {
                    var call = 0
                    override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
                        return when (call++) {
                            0 -> OcrOutput("12", listOf(OcrElement("12", 0.9f)))
                            1 -> OcrOutput("1234", listOf(OcrElement("1234", 0.9f)))
                            else -> OcrOutput("5678", listOf(OcrElement("5678", 0.9f)))
                        }
                    }
                    override fun close() = Unit
                }
            },
            labObserver = observer,
            tracedFramePreprocessor = { _, _ -> fakeTracedSuccess() }
        )
        solverConflict.solve(gifBytes)
        val capConflict = observer.captured.last()
        assertEquals(CaptchaLabOutcomeCategory.VALIDATION_REJECTED, capConflict.outcomeCategory)
        assertEquals("CONFLICTING_CANDIDATES", capConflict.lastRejectionCode)
        assertEquals(3, capConflict.attempts.size)

        // ML Kit exception captured cleanly
        val solverMlKitErr = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = StandardTestDispatcher(testScheduler),
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
                        throw MlKitException("Engine failure", MlKitException.INTERNAL)
                    }
                    override fun close() = Unit
                }
            },
            labObserver = observer,
            tracedFramePreprocessor = { _, _ -> fakeTracedSuccess() }
        )
        solverMlKitErr.solve(gifBytes)
        val capErr = observer.captured.last()
        assertEquals(CaptchaLabOutcomeCategory.MLKIT_ERROR, capErr.outcomeCategory)
        assertEquals(MlKitException.INTERNAL, capErr.mlKitErrorCode)
        assertEquals(1, capErr.attempts.size)

        // Empty bytes captured cleanly
        solverNoText.solve(ByteArray(0))
        assertEquals(CaptchaLabOutcomeCategory.EMPTY_INPUT, observer.captured.last().outcomeCategory)
    }

    @Test
    fun solver_capturesTimeoutAttempt() = runTest {
        val observer = RecordingObserver(enabled = true)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(timeoutMs = 500L),
            dispatcher = testDispatcher,
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
                        delay(2_000L)
                        return OcrOutput("1234")
                    }
                    override fun close() = Unit
                }
            },
            labObserver = observer,
            tracedFramePreprocessor = { _, _ -> fakeTracedSuccess() }
        )

        var done = false
        val job = launch(testDispatcher) {
            solver.solve(buildSyntheticGif(1))
            done = true
        }
        advanceTimeBy(600L)
        runCurrent()
        assertTrue(done)
        job.join()

        val cap = observer.captured.single()
        assertEquals(CaptchaLabOutcomeCategory.TIMEOUT, cap.outcomeCategory)
        assertEquals(1, cap.attempts.size)
        assertEquals("TIMEOUT_AT_RECOGNIZE", cap.attempts.single().rejectionCode)
    }

    @Test
    fun repository_retainsLast100Captchas_writesNoBase64_andExportsValidPythonZip() = runTest {
        val rootDir = tempFolder.newFolder("captcha_lab_test")
        val repo = CaptchaLabRepository(
            rootDir = rootDir,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            buildType = "release",
            maxRetained = CaptchaLabConstants.MAX_RETAINED_CAPTCHAS,
            ioDispatcher = Dispatchers.Unconfined,
            pngEncoder = { _, _, _, dest ->
                dest.parentFile?.mkdirs()
                dest.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                true
            }
        )

        val observer = RecordingObserver(enabled = true)
        val totalToInsert = 105
        for (i in 1..totalToInsert) {
            val traced = fakeTracedSuccess()
            val session = CapturedCaptchaSession(
                captchaId = "captcha_${i.toString().padStart(3, '0')}",
                capturedAtEpochMs = 1_700_000_000_000L + i * 1000L,
                rawBytes = buildSyntheticGif(if (i % 2 == 0) 3 else 1),
                rawInspection = GifFormatInspector.inspect(buildSyntheticGif(if (i % 2 == 0) 3 else 1)),
                decodedFrameIndex = 0,
                decodeStatus = "SUCCESS",
                rawWidth = 120,
                rawHeight = 40,
                outcomeCategory = if (i % 5 == 0) CaptchaLabOutcomeCategory.NO_TEXT_DETECTED else CaptchaLabOutcomeCategory.SUCCESS_PRIMARY,
                finalStatus = if (i % 5 == 0) "NO_TEXT_DETECTED" else "SUCCESS_PRIMARY",
                finalDigits = if (i % 5 == 0) null else "4829",
                failureReason = if (i % 5 == 0) "NO_TEXT_DETECTED" else null,
                lastRejectionCode = if (i % 5 == 0) "NO_TEXT_DETECTED" else null,
                exceptionType = null,
                mlKitErrorCode = null,
                totalDurationMs = 45L,
                attempts = listOf(
                    com.example.data.captcha.lab.CapturedAttemptPayload(
                        attemptNumber = 1,
                        routeType = "PRIMARY",
                        preprocessConfig = PreprocessConfig(),
                        decodeStatus = "SUCCESS",
                        stages = traced.stages,
                        mlKitInputWidth = 20,
                        mlKitInputHeight = 10,
                        rawOcrFullText = if (i % 5 == 0) "" else "4829",
                        elements = if (i % 5 == 0) emptyList() else listOf(OcrElement("4829", 0.92f)),
                        elementCount = if (i % 5 == 0) 0 else 1,
                        normalizedDigits = if (i % 5 == 0) null else "4829",
                        validationPassed = i % 5 != 0,
                        rejectionCode = if (i % 5 == 0) "NO_TEXT_DETECTED" else null,
                        failureReason = if (i % 5 == 0) "NO_TEXT_DETECTED" else null,
                        preprocessDurationMs = 4L,
                        ocrDurationMs = 30L,
                        totalDurationMs = 34L
                    )
                )
            )
            observer.onSolveCompleted(session)
            repo.saveSessionNow(session)
        }

        // Verify exact 100-record retention & oldest 5 deleted from disk
        val all = repo.records.value
        assertEquals(100, all.size)
        assertFalse(File(repo.captchasDir, "captcha_001").exists())
        assertFalse(File(repo.captchasDir, "captcha_005").exists())
        assertTrue(File(repo.captchasDir, "captcha_006").exists())
        assertTrue(File(repo.captchasDir, "captcha_105").exists())

        // Verify index.jsonl contains 100 lines and zero Base64 blobs
        val indexLines = repo.indexFile.readLines().filter { it.isNotBlank() }
        assertEquals(100, indexLines.size)
        assertFalse(indexLines.first().contains("data:image"))
        assertFalse(indexLines.first().contains("base64"))

        val newestJson = JSONObject(indexLines.first())
        val roundTripped = CaptchaLabJson.recordFromJson(newestJson)
        assertEquals("captcha_105", roundTripped.captchaId)
        assertEquals("release", roundTripped.buildType)
        assertEquals(8, roundTripped.attempts.single().stages.size)

        // Verify filters
        val noTextOnly = CaptchaLabRepository.filterAndSort(all, CaptchaLabFilter.NO_TEXT_DETECTED, CaptchaLabSort.NEWEST_FIRST)
        assertEquals(20, noTextOnly.size)

        // Verify ZIP Export structure for Python
        val destZip = tempFolder.newFile("captcha_export.zip")
        val exported = CaptchaLabZipExporter.exportToZipFile(repo, all, destZip)
        assertTrue(exported)
        ZipFile(destZip).use { zf ->
            assertNotNull(zf.getEntry("README.md"))
            assertNotNull(zf.getEntry("summary.json"))
            assertNotNull(zf.getEntry("index.jsonl"))
            assertNotNull(zf.getEntry("captchas/captcha_105/metadata.json"))
            assertNotNull(zf.getEntry("captchas/captcha_105/raw.gif"))
            assertNotNull(zf.getEntry("captchas/captcha_105/attempt_1_primary/08_final_padded_mlkit_input.png"))
        }

        // Single delete + clear all
        repo.deleteRecord("captcha_105")
        assertEquals(99, repo.records.value.size)
        assertFalse(File(repo.captchasDir, "captcha_105").exists())

        repo.clearAll()
        assertEquals(0, repo.records.value.size)
        assertFalse(repo.indexFile.exists())
    }

    @Test
    fun stageBenchmarkEngine_evaluatesRawAndAll8Stages_handlesSkippedFailureAndReleasesFrames() = runTest {
        val rootDir = tempFolder.newFolder("bench_single_test")
        val repo = CaptchaLabRepository(
            rootDir = rootDir,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            buildType = "debug",
            ioDispatcher = Dispatchers.Unconfined,
            pngEncoder = { _, _, _, dest ->
                dest.parentFile?.mkdirs()
                dest.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                true
            }
        )

        val stages = CaptchaStageKind.entries.map { kind ->
            if (kind == CaptchaStageKind.INVERTED) {
                CapturedStageBuffer(
                    stage = kind,
                    status = CaptchaStageStatus.SKIPPED,
                    note = "Invert disabled in config"
                )
            } else {
                CapturedStageBuffer(
                    stage = kind,
                    status = CaptchaStageStatus.COMPLETED,
                    width = 36,
                    height = 18,
                    grayscaleImage = GrayscaleImage(36, 18, IntArray(36 * 18) { 128 })
                )
            }
        }

        val gifBytes = buildSyntheticGif(1)
        val saved = repo.saveSessionNow(
            CapturedCaptchaSession(
                captchaId = "bench_cap_1",
                capturedAtEpochMs = 1_700_000_100_000L,
                rawBytes = gifBytes,
                rawInspection = GifFormatInspector.inspect(gifBytes),
                decodedFrameIndex = 0,
                decodeStatus = "SUCCESS",
                rawWidth = 120,
                rawHeight = 40,
                outcomeCategory = CaptchaLabOutcomeCategory.NO_TEXT_DETECTED,
                finalStatus = "NO_TEXT_DETECTED",
                finalDigits = null,
                failureReason = "NO_TEXT_DETECTED",
                lastRejectionCode = "NO_TEXT_DETECTED",
                exceptionType = null,
                mlKitErrorCode = null,
                totalDurationMs = 20L,
                attempts = listOf(
                    CapturedAttemptPayload(
                        attemptNumber = 1,
                        routeType = "PRIMARY",
                        preprocessConfig = PreprocessConfig(invertOutput = false),
                        decodeStatus = "SUCCESS",
                        stages = stages,
                        mlKitInputWidth = 36,
                        mlKitInputHeight = 18,
                        rawOcrFullText = "",
                        elements = emptyList(),
                        elementCount = 0,
                        normalizedDigits = null,
                        validationPassed = false,
                        rejectionCode = "NO_TEXT_DETECTED",
                        failureReason = "NO_TEXT_DETECTED"
                    )
                )
            )
        )!!

        class TaggedFrame(val tag: String) : CaptchaFrame {
            var released = false
            override fun release() {
                released = true
            }
        }

        val allocatedFrames = mutableListOf<TaggedFrame>()
        val engine = CaptchaStageBenchmarkEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
                        return when ((frame as TaggedFrame).tag) {
                            "raw.gif" -> OcrOutput("", emptyList())
                            "${CaptchaStageKind.DENOISED.fileBaseName}.png" -> error("Simulated OCR crash")
                            "${CaptchaStageKind.THRESHOLDED.fileBaseName}.png" ->
                                OcrOutput("1234", listOf(OcrElement("1234", 0.72f)))
                            "${CaptchaStageKind.FINAL_PADDED.fileBaseName}.png" ->
                                OcrOutput("1234", listOf(OcrElement("1234", 0.96f)))
                            else -> OcrOutput("12", listOf(OcrElement("12", 0.85f)))
                        }
                    }
                    override fun close() = Unit
                }
            },
            fileFrameDecoder = { file ->
                if (file.name == "${CaptchaStageKind.DECODED.fileBaseName}.png") {
                    null // Simulate decode failure on DECODED stage
                } else {
                    val f = TaggedFrame(file.name).also { allocatedFrames.add(it) }
                    DecodedBenchmarkFrame(frame = f, width = 36, height = 18)
                }
            }
        )

        val result = engine.benchmarkRecord(repo, saved)
        assertEquals(9, result.targets.size)
        assertEquals(CaptchaLabConstants.TARGET_KEY_RAW, result.targets.first().targetKey)
        assertEquals(CaptchaBenchmarkTargetStatus.COMPLETED, result.targets.first().status)
        assertNull(result.targets.first().averageConfidence)
        assertEquals("NO_TEXT_DETECTED", result.targets.first().rejectionCode)

        val decodedTarget = result.targets.first { it.targetKey == CaptchaStageKind.DECODED.name }
        assertEquals(CaptchaBenchmarkTargetStatus.FAILED, decodedTarget.status)

        val invertedTarget = result.targets.first { it.targetKey == CaptchaStageKind.INVERTED.name }
        assertEquals(CaptchaBenchmarkTargetStatus.SKIPPED, invertedTarget.status)

        val denoisedTarget = result.targets.first { it.targetKey == CaptchaStageKind.DENOISED.name }
        assertEquals(CaptchaBenchmarkTargetStatus.FAILED, denoisedTarget.status)

        assertEquals(CaptchaStageKind.FINAL_PADDED.name, result.bestTargetKey)
        assertTrue(allocatedFrames.isNotEmpty())
        assertTrue(allocatedFrames.all { it.released })
    }

    @Test
    fun stageBenchmarkEngine_batchSharesSession_andPreservesPartialResultsOnCancel() = runTest {
        val rootDir = tempFolder.newFolder("bench_batch_test")
        val repo = CaptchaLabRepository(
            rootDir = rootDir,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            buildType = "release",
            ioDispatcher = Dispatchers.Unconfined,
            pngEncoder = { _, _, _, dest ->
                dest.parentFile?.mkdirs()
                dest.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                true
            }
        )

        val gifBytes = buildSyntheticGif(1)
        val traced = fakeTracedSuccess()
        val inserted = (1..3).map { idx ->
            repo.saveSessionNow(
                CapturedCaptchaSession(
                    captchaId = "batch_cap_$idx",
                    capturedAtEpochMs = 1_700_000_200_000L + idx * 1000L,
                    rawBytes = gifBytes,
                    rawInspection = GifFormatInspector.inspect(gifBytes),
                    decodedFrameIndex = 0,
                    decodeStatus = "SUCCESS",
                    rawWidth = 20,
                    rawHeight = 10,
                    outcomeCategory = CaptchaLabOutcomeCategory.SUCCESS_PRIMARY,
                    finalStatus = "SUCCESS_PRIMARY",
                    finalDigits = "4829",
                    failureReason = null,
                    lastRejectionCode = null,
                    exceptionType = null,
                    mlKitErrorCode = null,
                    totalDurationMs = 15L,
                    attempts = listOf(
                        CapturedAttemptPayload(
                            attemptNumber = 1,
                            routeType = "PRIMARY",
                            preprocessConfig = PreprocessConfig(),
                            decodeStatus = "SUCCESS",
                            stages = traced.stages,
                            mlKitInputWidth = 20,
                            mlKitInputHeight = 10,
                            rawOcrFullText = "4829",
                            elements = listOf(OcrElement("4829", 0.9f)),
                            elementCount = 1,
                            normalizedDigits = "4829",
                            validationPassed = true,
                            rejectionCode = null,
                            failureReason = null
                        )
                    )
                )
            )!!
        }

        var factoryCalls = 0
        var sessionClosed = false
        val engine = CaptchaStageBenchmarkEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = {
                factoryCalls++
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame) =
                        OcrOutput("4829", listOf(OcrElement("4829", 0.88f)))
                    override fun close() {
                        sessionClosed = true
                    }
                }
            },
            fileFrameDecoder = { DecodedBenchmarkFrame(FakeFrame(), 20, 10) }
        )

        val progressUpdates = mutableListOf<Int>()
        lateinit var batchJob: kotlinx.coroutines.Job
        batchJob = launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            engine.benchmarkBatch(repo, inserted) { done, _, currentId, _ ->
                progressUpdates.add(done)
                if (done == 2 && currentId == "batch_cap_3") {
                    batchJob.cancel()
                }
            }
        }
        batchJob.start()
        batchJob.join()

        assertEquals(1, factoryCalls)
        assertTrue(sessionClosed)
        assertTrue(progressUpdates.contains(2))
        val after = repo.records.value.associateBy { it.captchaId }
        assertNotNull(after["batch_cap_1"]?.stageBenchmark)
        assertNotNull(after["batch_cap_2"]?.stageBenchmark)
        assertNull(after["batch_cap_3"]?.stageBenchmark)
    }

    @Test
    fun compatibilityRanking_usesAutoScoreAndExpectedDigits_andExportsInJsonAndZip() = runTest {
        val rootDir = tempFolder.newFolder("bench_compat_test")
        val repo = CaptchaLabRepository(
            rootDir = rootDir,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            buildType = "release",
            ioDispatcher = Dispatchers.Unconfined,
            pngEncoder = { _, _, _, dest ->
                dest.parentFile?.mkdirs()
                dest.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                true
            }
        )

        val gifBytes = buildSyntheticGif(1)
        val traced = fakeTracedSuccess()
        val cap1 = repo.saveSessionNow(
            CapturedCaptchaSession(
                captchaId = "compat_1",
                capturedAtEpochMs = 1_700_000_300_000L,
                rawBytes = gifBytes,
                rawInspection = GifFormatInspector.inspect(gifBytes),
                decodedFrameIndex = 0,
                decodeStatus = "SUCCESS",
                rawWidth = 20,
                rawHeight = 10,
                outcomeCategory = CaptchaLabOutcomeCategory.NO_TEXT_DETECTED,
                finalStatus = "NO_TEXT_DETECTED",
                finalDigits = null,
                failureReason = "NO_TEXT_DETECTED",
                lastRejectionCode = "NO_TEXT_DETECTED",
                exceptionType = null,
                mlKitErrorCode = null,
                totalDurationMs = 12L,
                attempts = listOf(
                    CapturedAttemptPayload(
                        attemptNumber = 1,
                        routeType = "PRIMARY",
                        preprocessConfig = PreprocessConfig(),
                        decodeStatus = "SUCCESS",
                        stages = traced.stages,
                        mlKitInputWidth = 20,
                        mlKitInputHeight = 10,
                        rawOcrFullText = "",
                        elements = emptyList(),
                        elementCount = 0,
                        normalizedDigits = null,
                        validationPassed = false,
                        rejectionCode = "NO_TEXT_DETECTED",
                        failureReason = "NO_TEXT_DETECTED"
                    )
                )
            )
        )!!
        val cap2 = repo.saveSessionNow(
            CapturedCaptchaSession(
                captchaId = "compat_2",
                capturedAtEpochMs = 1_700_000_305_000L,
                rawBytes = gifBytes,
                rawInspection = GifFormatInspector.inspect(gifBytes),
                decodedFrameIndex = 0,
                decodeStatus = "SUCCESS",
                rawWidth = 20,
                rawHeight = 10,
                outcomeCategory = CaptchaLabOutcomeCategory.NO_TEXT_DETECTED,
                finalStatus = "NO_TEXT_DETECTED",
                finalDigits = null,
                failureReason = "NO_TEXT_DETECTED",
                lastRejectionCode = "NO_TEXT_DETECTED",
                exceptionType = null,
                mlKitErrorCode = null,
                totalDurationMs = 12L,
                attempts = listOf(
                    CapturedAttemptPayload(
                        attemptNumber = 1,
                        routeType = "PRIMARY",
                        preprocessConfig = PreprocessConfig(),
                        decodeStatus = "SUCCESS",
                        stages = traced.stages,
                        mlKitInputWidth = 20,
                        mlKitInputHeight = 10,
                        rawOcrFullText = "",
                        elements = emptyList(),
                        elementCount = 0,
                        normalizedDigits = null,
                        validationPassed = false,
                        rejectionCode = "NO_TEXT_DETECTED",
                        failureReason = "NO_TEXT_DETECTED"
                    )
                )
            )
        )!!

        class TagFrame(val fileName: String) : CaptchaFrame {
            override fun release() = Unit
        }

        // UPSCALED hallucinates 9999 with high confidence (0.98); DENOISED reads true digits (4829 / 1234) at 0.75
        var currentCap = 1
        val engine = CaptchaStageBenchmarkEngine(
            dispatcher = Dispatchers.Unconfined,
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
                        return when ((frame as TagFrame).fileName) {
                            "raw.gif" -> OcrOutput("", emptyList())
                            "${CaptchaStageKind.UPSCALED.fileBaseName}.png" ->
                                OcrOutput("9999", listOf(OcrElement("9999", 0.98f)))
                            "${CaptchaStageKind.DENOISED.fileBaseName}.png" -> {
                                val d = if (currentCap == 1) "4829" else "1234"
                                OcrOutput(d, listOf(OcrElement(d, 0.75f)))
                            }
                            else -> OcrOutput("12", listOf(OcrElement("12", 0.50f)))
                        }
                    }
                    override fun close() = Unit
                }
            },
            fileFrameDecoder = { file -> DecodedBenchmarkFrame(TagFrame(file.name), 20, 10) }
        )

        currentCap = 1
        repo.updateStageBenchmark(cap1.captchaId, engine.benchmarkRecord(repo, cap1))
        currentCap = 2
        repo.updateStageBenchmark(cap2.captchaId, engine.benchmarkRecord(repo, cap2))

        // Auto ranking without manual ground truth favors UPSCALED due to higher confidence (0.98 > 0.75)
        val autoSummary = CaptchaStageBenchmarkEngine.analyzeCompatibility(repo.records.value)
        assertEquals(2, autoSummary.benchmarkedCaptchasCount)
        assertEquals(0, autoSummary.labeledCaptchasCount)
        assertEquals(CaptchaStageKind.UPSCALED.name, autoSummary.bestStageKey)

        // Add manual ground truth labels ("4829" and "1234") -> exact match promotes DENOISED to #1
        repo.updateExpectedDigits(cap1.captchaId, "۴۸۲۹")
        repo.updateExpectedDigits(cap2.captchaId, "1234")

        val labeledSummary = CaptchaStageBenchmarkEngine.analyzeCompatibility(repo.records.value)
        assertEquals(2, labeledSummary.labeledCaptchasCount)
        assertEquals(CaptchaStageKind.DENOISED.name, labeledSummary.bestStageKey)
        val denoisedMetric = labeledSummary.metrics.first { it.targetKey == CaptchaStageKind.DENOISED.name }
        assertEquals(100, denoisedMetric.exactMatchRatePercent)
        val upscaledMetric = labeledSummary.metrics.first { it.targetKey == CaptchaStageKind.UPSCALED.name }
        assertEquals(0, upscaledMetric.exactMatchRatePercent)

        // Verify JSON & ZIP summary export includes stageBenchmark, expectedDigits & leaderboard
        val roundTripped = CaptchaLabJson.recordFromJson(
            JSONObject(File(File(repo.captchasDir, cap1.captchaId), "metadata.json").readText())
        )
        assertEquals("4829", roundTripped.expectedDigits)
        assertEquals(CaptchaStageKind.DENOISED.name, roundTripped.stageBenchmark?.bestTargetKey)

        val destZip = tempFolder.newFile("compat_export.zip")
        assertTrue(CaptchaLabZipExporter.exportToZipFile(repo, repo.records.value, destZip))
        ZipFile(destZip).use { zf ->
            val summaryText = zf.getInputStream(zf.getEntry("summary.json")).bufferedReader().readText()
            val summaryJson = JSONObject(summaryText)
            assertEquals(2, summaryJson.getInt("schemaVersion"))
            assertEquals(2, summaryJson.getInt("benchmarkedCaptchasCount"))
            assertEquals(2, summaryJson.getInt("labeledCaptchasCount"))
            assertEquals(CaptchaStageKind.DENOISED.name, summaryJson.getString("recommendedBestStageKey"))
            assertEquals(9, summaryJson.getJSONArray("stageCompatibilityLeaderboard").length())
        }
    }
}
