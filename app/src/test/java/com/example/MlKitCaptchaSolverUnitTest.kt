package com.example

import com.example.data.captcha.preprocess.NoiseRemovalMode
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.preprocess.ThresholdMode
import com.example.data.captcha.solver.CaptchaDigitValidator
import com.example.data.captcha.solver.CaptchaFrame
import com.example.data.captcha.solver.CaptchaResult
import com.example.data.captcha.solver.CaptchaSolverConfig
import com.example.data.captcha.solver.DigitValidationOutcome
import com.example.data.captcha.solver.MlKitCaptchaSolver
import com.example.data.captcha.solver.OcrElement
import com.example.data.captcha.solver.OcrOutput
import com.example.data.captcha.solver.OcrSession
import com.example.data.captcha.solver.OcrSolveStage
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * تست‌های واحد خالص JVM برای منطق اعتبارسنجی، تلاش‌های ترکیبی، تایم‌اوت و مدیریت منابع حل‌گر کپچا با موتور جعلی (Fake OCR).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MlKitCaptchaSolverUnitTest {

    private class FakeFrame : CaptchaFrame {
        var released = false
        override fun release() {
            released = true
        }
    }

    private class FakeOcrSession(
        private val outputs: List<OcrOutput>,
        private val delayPerCallMs: Long = 0L
    ) : OcrSession {
        var calls = 0
        var closed = false

        override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
            if (delayPerCallMs > 0L) {
                delay(delayPerCallMs)
            }
            val index = calls.coerceAtMost(outputs.lastIndex.coerceAtLeast(0))
            calls++
            return outputs[index]
        }

        override fun close() {
            closed = true
        }
    }

    private val dummyImageBytes = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)

    @Test
    fun validator_acceptsFourLatinDigits_andNormalizesPersianArabicAndStripsSeparators() {
        val latin = CaptchaDigitValidator.validate(
            OcrOutput(
                fullText = "4829",
                elements = listOf(OcrElement("4829", 0.91f))
            )
        )
        assertEquals(DigitValidationOutcome.Valid("4829"), latin)

        val persianAndArabicWithSpaces = CaptchaDigitValidator.validate(
            OcrOutput(
                fullText = "۴ ۸-٢٩\n",
                elements = listOf(
                    OcrElement("۴۸", 0.80f),
                    OcrElement("٢٩", 0.75f)
                )
            )
        )
        assertEquals(DigitValidationOutcome.Valid("4829"), persianAndArabicWithSpaces)

        val unavailableConfidence = CaptchaDigitValidator.validate(
            OcrOutput(
                fullText = "1234",
                elements = listOf(OcrElement("1234", null))
            )
        )
        assertEquals(DigitValidationOutcome.Valid("1234"), unavailableConfidence)
    }

    @Test
    fun validator_rejectsThreeFiveLettersAndLowConfidence() {
        val threeDigits = CaptchaDigitValidator.validate(
            OcrOutput("482", listOf(OcrElement("482", 0.95f)))
        )
        assertTrue(threeDigits is DigitValidationOutcome.Invalid)

        val fiveDigits = CaptchaDigitValidator.validate(
            OcrOutput("48291", listOf(OcrElement("48291", 0.95f)))
        )
        assertTrue(fiveDigits is DigitValidationOutcome.Invalid)

        val lettersOnly = CaptchaDigitValidator.validate(
            OcrOutput("ABCD", listOf(OcrElement("ABCD", 0.95f)))
        )
        assertTrue(lettersOnly is DigitValidationOutcome.Invalid)

        val lowConfidence = CaptchaDigitValidator.validate(
            OcrOutput("4829", listOf(OcrElement("4829", 0.64f))),
            minElementConfidence = 0.65f
        )
        assertTrue(lowConfidence is DigitValidationOutcome.Invalid)
    }

    @Test
    fun solver_returnsSuccessImmediatelyOnFirstAttemptWithoutRunningFallbacks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val createdFrames = mutableListOf<FakeFrame>()
        val fakeSession = FakeOcrSession(
            listOf(OcrOutput("9153", listOf(OcrElement("9153", 0.88f))))
        )

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame().also { createdFrames.add(it) } },
            sessionFactory = { fakeSession }
        )

        val result = solver.solve(dummyImageBytes)

        assertEquals(CaptchaResult.Success("9153"), result)
        assertFalse("Success.toString() must redact digits", result.toString().contains("9153"))
        assertEquals("Fast-path must invoke OCR only once", 1, fakeSession.calls)
        assertTrue("OcrSession must be closed", fakeSession.closed)
        assertEquals(1, createdFrames.size)
        assertTrue("Frame must be released", createdFrames.all { it.released })
    }

    @Test
    fun solver_retriesWithFallbackConfigsAndResolvesSingleValidCandidate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val createdFrames = mutableListOf<FakeFrame>()
        val fakeSession = FakeOcrSession(
            listOf(
                OcrOutput("48", listOf(OcrElement("48", 0.90f))),     // Attempt 1 fails (2 digits)
                OcrOutput("۴۸۲۹", listOf(OcrElement("۴۸۲۹", 0.82f))), // Attempt 2 succeeds
                OcrOutput("482", listOf(OcrElement("482", 0.90f)))    // Attempt 3 fails (3 digits)
            )
        )

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame().also { createdFrames.add(it) } },
            sessionFactory = { fakeSession }
        )

        val result = solver.solve(dummyImageBytes)

        assertEquals(CaptchaResult.Success("4829"), result)
        assertEquals(3, fakeSession.calls)
        assertTrue(fakeSession.closed)
        assertTrue(createdFrames.all { it.released })
    }

    @Test
    fun solver_returnsNotConfidentWhenFallbackAttemptsProduceConflictingFourDigitResults() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val createdFrames = mutableListOf<FakeFrame>()
        val fakeSession = FakeOcrSession(
            listOf(
                OcrOutput("ab", listOf(OcrElement("ab", 0.90f))),     // Attempt 1 fails (letters)
                OcrOutput("4829", listOf(OcrElement("4829", 0.85f))), // Attempt 2 says 4829
                OcrOutput("4820", listOf(OcrElement("4820", 0.85f)))  // Attempt 3 says 4820 -> conflict!
            )
        )

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame().also { createdFrames.add(it) } },
            sessionFactory = { fakeSession }
        )

        val result = solver.solve(dummyImageBytes)

        assertTrue("Conflicting 4-digit candidates must yield NotConfident, got $result", result is CaptchaResult.NotConfident)
        assertEquals(3, fakeSession.calls)
        assertTrue(fakeSession.closed)
        assertTrue(createdFrames.all { it.released })
    }

    @Test
    fun solver_respectsFixedMaxAttemptsWhenAllOutputsInvalid() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preprocessCalls = AtomicInteger(0)
        val fakeSession = FakeOcrSession(
            listOf(
                OcrOutput("123", listOf(OcrElement("123", 0.9f))),
                OcrOutput("12345", listOf(OcrElement("12345", 0.9f))),
                OcrOutput("letters", listOf(OcrElement("letters", 0.9f)))
            )
        )

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ ->
                preprocessCalls.incrementAndGet()
                FakeFrame()
            },
            sessionFactory = { fakeSession }
        )

        val result = solver.solve(dummyImageBytes)
        assertTrue(result is CaptchaResult.NotConfident)
        assertEquals(3, preprocessCalls.get())
        assertEquals(3, fakeSession.calls)
        assertTrue(fakeSession.closed)
    }

    @Test
    fun solver_returnsErrorOnTimeoutAndReleasesResourcesWithoutLeaking() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val createdFrames = mutableListOf<FakeFrame>()
        val fakeSession = FakeOcrSession(
            outputs = listOf(OcrOutput("1234", listOf(OcrElement("1234", 0.99f)))),
            delayPerCallMs = 5_000L
        )

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(timeoutMs = 2_500L),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame().also { createdFrames.add(it) } },
            sessionFactory = { fakeSession }
        )

        val result = solver.solve(dummyImageBytes)

        assertTrue("Timeout must produce CaptchaResult.Error, got $result", result is CaptchaResult.Error)
        assertTrue("OcrSession must still be closed after timeout", fakeSession.closed)
        assertTrue("Allocated frames must be released after timeout", createdFrames.all { it.released })
    }

    @Test
    fun solver_propagatesExternalCancellationAndClosesSessionWithoutLeaking() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val createdFrames = mutableListOf<FakeFrame>()
        val fakeSession = FakeOcrSession(
            outputs = listOf(OcrOutput("1234", listOf(OcrElement("1234", 0.99f)))),
            delayPerCallMs = 1_000L
        )
        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(timeoutMs = 2_500L),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame().also { createdFrames.add(it) } },
            sessionFactory = { fakeSession }
        )

        var caughtCancellation = false
        val job = launch {
            try {
                solver.solve(dummyImageBytes)
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }

        advanceTimeBy(200L)
        runCurrent()
        job.cancel()
        runCurrent()
        job.join()

        assertTrue(caughtCancellation)
        assertTrue("OcrSession must close when caller cancels coroutine", fakeSession.closed)
        assertTrue("Frame must release when caller cancels coroutine", createdFrames.all { it.released })
    }

    @Test
    fun solver_handlesEmptyAndUnprocessableBytesCleanly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var sessionCreated = false
        val fakeSession = FakeOcrSession(listOf(OcrOutput("1234")))

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(
                fallbackConfigs = listOf(PreprocessConfig(), PreprocessConfig())
            ),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> null },
            sessionFactory = {
                sessionCreated = true
                fakeSession
            }
        )

        val emptyRes = solver.solve(ByteArray(0))
        assertTrue(emptyRes is CaptchaResult.Error)
        assertFalse("Empty bytes should fail fast before opening OCR session", sessionCreated)

        val corruptRes = solver.solve(dummyImageBytes)
        assertTrue(corruptRes is CaptchaResult.Error)
        assertTrue("Session opened for corrupt bytes must still be closed", fakeSession.closed)
    }

    @Test
    fun solveWithDiagnostics_reportsSessionInitFailureWithoutCallingPreprocessorOrSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var preprocessCalled = false

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ ->
                preprocessCalled = true
                FakeFrame()
            },
            sessionFactory = {
                throw IllegalStateException(
                    "ComponentRegistrar missing constructor",
                    NoSuchMethodException("TextRegistrar.<init> []")
                )
            }
        )

        val outcome = solver.solveWithDiagnostics(dummyImageBytes)

        assertTrue(outcome.result is CaptchaResult.Error)
        assertFalse(preprocessCalled)
        assertEquals(OcrSolveStage.SESSION_INIT, outcome.diagnostics.stage)
        assertEquals(0, outcome.diagnostics.attemptCount)
        assertEquals("java.lang.IllegalStateException", outcome.diagnostics.exceptionType)
        assertTrue(outcome.diagnostics.causeChain?.contains("NoSuchMethodException") == true)
        assertTrue(outcome.diagnostics.causeChain?.contains("TextRegistrar") == true)
    }

    @Test
    fun solveWithDiagnostics_capturesMlKitExceptionCodeAndStageAtRecognizeAndClosesResources() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val createdFrames = mutableListOf<FakeFrame>()
        val failingSession = object : OcrSession {
            var closed = false
            override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
                throw MlKitException("Waiting for the text optional module", MlKitException.UNAVAILABLE)
            }
            override fun close() {
                closed = true
            }
        }

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame().also { createdFrames.add(it) } },
            sessionFactory = { failingSession }
        )

        val outcome = solver.solveWithDiagnostics(dummyImageBytes)

        assertTrue(outcome.result is CaptchaResult.Error)
        assertEquals(OcrSolveStage.RECOGNIZE, outcome.diagnostics.stage)
        assertEquals(1, outcome.diagnostics.attemptCount)
        assertEquals("com.google.mlkit.common.MlKitException", outcome.diagnostics.exceptionType)
        assertEquals(MlKitException.UNAVAILABLE, outcome.diagnostics.mlKitErrorCode)
        assertTrue(outcome.diagnostics.causeChain?.contains("mlKitCode=${MlKitException.UNAVAILABLE}") == true)
        assertTrue(failingSession.closed)
        assertTrue(createdFrames.all { it.released })
    }

    @Test
    fun solveWithDiagnostics_reportsPreprocessExceptionAndTimeoutAndValidationCountsSafely() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeSession = FakeOcrSession(
            listOf(
                OcrOutput("91", listOf(OcrElement("91", 0.90f))),
                OcrOutput("918", listOf(OcrElement("918", 0.85f))),
                OcrOutput("91827", listOf(OcrElement("91827", 0.92f)))
            )
        )

        val validationSolver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame() },
            sessionFactory = { fakeSession }
        )
        val validationOutcome = validationSolver.solveWithDiagnostics(dummyImageBytes)
        assertTrue(validationOutcome.result is CaptchaResult.NotConfident)
        assertEquals(OcrSolveStage.VALIDATE, validationOutcome.diagnostics.stage)
        assertEquals(3, validationOutcome.diagnostics.attemptCount)
        assertEquals("DIGIT_COUNT_MISMATCH", validationOutcome.diagnostics.lastRejectionCode)
        assertEquals(1, validationOutcome.diagnostics.elementCount)
        assertEquals(5, validationOutcome.diagnostics.extractedDigitCount)
        val diagString = validationOutcome.diagnostics.toString()
        assertFalse("Diagnostics must never contain OCR digits", diagString.contains("91827"))

        val preprocessCrashSolver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> throw IllegalArgumentException("decode failure") },
            sessionFactory = { FakeOcrSession(emptyList()) }
        )
        val preprocessOutcome = preprocessCrashSolver.solveWithDiagnostics(dummyImageBytes)
        assertTrue(preprocessOutcome.result is CaptchaResult.Error)
        assertEquals(OcrSolveStage.PREPROCESS, preprocessOutcome.diagnostics.stage)
        assertEquals(1, preprocessOutcome.diagnostics.attemptCount)

        val timeoutSession = FakeOcrSession(listOf(OcrOutput("1234")), delayPerCallMs = 5_000L)
        val timeoutSolver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(timeoutMs = 2_500L),
            dispatcher = dispatcher,
            framePreprocessor = { _, _ -> FakeFrame() },
            sessionFactory = { timeoutSession }
        )
        val timeoutOutcome = timeoutSolver.solveWithDiagnostics(dummyImageBytes)
        assertEquals(OcrSolveStage.TIMEOUT, timeoutOutcome.diagnostics.stage)
        assertTrue(timeoutOutcome.diagnostics.causeChain?.contains("RECOGNIZE") == true)
    }

    @Test
    fun solverConfig_usesDecodedColorPrimaryAndComplementaryNonDenoisedFallbacks() {
        val cfg = CaptchaSolverConfig()
        assertTrue(cfg.primaryConfig.useDecodedColorFrame)
        assertEquals(ThresholdMode.NONE, cfg.primaryConfig.thresholdMode)
        assertEquals(NoiseRemovalMode.NONE, cfg.primaryConfig.noiseRemovalMode)
        assertFalse(cfg.primaryConfig.invertOutput)
        assertEquals(1, cfg.primaryConfig.scaleFactor)
        assertEquals(0, cfg.primaryConfig.borderPaddingPx)

        assertEquals(2, cfg.fallbackConfigs.size)
        val fb1 = cfg.fallbackConfigs[0]
        assertFalse(fb1.useDecodedColorFrame)
        assertEquals(ThresholdMode.NONE, fb1.thresholdMode)
        assertEquals(NoiseRemovalMode.NONE, fb1.noiseRemovalMode)
        assertFalse(fb1.invertOutput)

        val fb2 = cfg.fallbackConfigs[1]
        assertFalse(fb2.useDecodedColorFrame)
        assertEquals(ThresholdMode.OTSU, fb2.thresholdMode)
        assertEquals(NoiseRemovalMode.NONE, fb2.noiseRemovalMode)
        assertTrue(fb2.invertOutput)
    }
}

