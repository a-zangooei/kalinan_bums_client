package com.example

import com.example.data.captcha.lab.CaptchaLabConstants
import com.example.data.captcha.lab.CaptchaLabFilter
import com.example.data.captcha.lab.CaptchaLabJson
import com.example.data.captcha.lab.CaptchaLabObserver
import com.example.data.captcha.lab.CaptchaLabOutcomeCategory
import com.example.data.captcha.lab.CaptchaLabRecord
import com.example.data.captcha.lab.CaptchaLabRepository
import com.example.data.captcha.lab.CaptchaLabSort
import com.example.data.captcha.lab.CaptchaLabZipExporter
import com.example.data.captcha.lab.CaptchaStageKind
import com.example.data.captcha.lab.CaptchaStageStatus
import com.example.data.captcha.lab.CapturedCaptchaSession
import com.example.data.captcha.lab.CapturedStageBuffer
import com.example.data.captcha.lab.DecodedArgbSnapshot
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
}
