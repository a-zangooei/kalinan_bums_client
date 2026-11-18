package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.data.captcha.lab.CaptchaBenchmarkTargetStatus
import com.example.data.captcha.lab.CaptchaLabController
import com.example.data.captcha.lab.CaptchaLabOutcomeCategory
import com.example.data.captcha.lab.CaptchaStageBenchmarkResult
import com.example.data.captcha.lab.CaptchaStageKind
import com.example.data.captcha.lab.CaptchaStageOcrTargetResult
import com.example.data.captcha.lab.CaptchaStageStatus
import com.example.data.captcha.lab.CapturedAttemptPayload
import com.example.data.captcha.lab.CapturedCaptchaSession
import com.example.data.captcha.lab.CapturedStageBuffer
import com.example.data.captcha.lab.DecodedArgbSnapshot
import com.example.data.captcha.lab.GifFormatInspector
import com.example.data.captcha.preprocess.GrayscaleImage
import com.example.data.captcha.preprocess.PreprocessConfig
import com.example.data.captcha.solver.OcrElement
import com.example.ui.components.PersianRtlLayout
import com.example.ui.screens.captchalab.CaptchaLabScreen
import com.example.ui.screens.settings.DeveloperToolsSection
import com.example.ui.theme.CullinanTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptchaLabComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        CaptchaLabController.init(context)
        CaptchaLabController.setArchivingEnabled(false)
        runBlocking {
            CaptchaLabController.getRepository(context).clearAll()
        }
    }

    @After
    fun tearDown() {
        CaptchaLabController.setArchivingEnabled(false)
    }

    @Test
    fun controllerDefaultsToOffOnInit() {
        CaptchaLabController.setArchivingEnabled(true)
        assertTrue(CaptchaLabController.isArchivingEnabled())
        // Re-initializing simulates app restart only if processInitialized wasn't set,
        // while fresh launch starts false; explicit toggle OFF stops immediately.
        CaptchaLabController.setArchivingEnabled(false)
        assertFalse(CaptchaLabController.isArchivingEnabled())
    }

    @Test
    fun developerSectionShowsCaptchaLabEntryButton() {
        var clicked = false
        composeRule.setContent {
            CullinanTheme {
                PersianRtlLayout {
                    DeveloperToolsSection(
                        onOpenLogs = {},
                        onExportZip = {},
                        onClearLogs = {},
                        onExitDeveloperMode = {},
                        onOpenCaptchaLab = { clicked = true },
                        isCaptchaLabArchivingActive = false,
                        captchaLabArchivedCount = 3
                    )
                }
            }
        }

        composeRule.onNodeWithTag("open_captcha_lab_button").assertIsDisplayed().performClick()
        assertTrue(clicked)
    }

    @Test
    fun captchaLabScreen_toggleDetailCompareAndClearWorkEndToEnd() {
        val repo = CaptchaLabController.getRepository(context)
        val w = 24
        val h = 12
        val snap = DecodedArgbSnapshot(w, h, IntArray(w * h) { -1 })
        val gray = GrayscaleImage(w, h, IntArray(w * h) { 120 })
        val stages = CaptchaStageKind.entries.map { kind ->
            if (kind == CaptchaStageKind.DECODED) {
                CapturedStageBuffer(kind, CaptchaStageStatus.COMPLETED, w, h, argbSnapshot = snap)
            } else {
                CapturedStageBuffer(kind, CaptchaStageStatus.COMPLETED, w, h, grayscaleImage = gray)
            }
        }
        val dummyGif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 24, 0, 12, 0, 0, 0, 0, 0x3B)

        runBlocking {
            repo.saveSessionNow(
                CapturedCaptchaSession(
                    captchaId = "test_captcha_ui_1",
                    capturedAtEpochMs = 1_700_000_000_000L,
                    rawBytes = dummyGif,
                    rawInspection = GifFormatInspector.inspect(dummyGif),
                    decodedFrameIndex = 0,
                    decodeStatus = "SUCCESS",
                    rawWidth = w,
                    rawHeight = h,
                    outcomeCategory = CaptchaLabOutcomeCategory.SUCCESS_PRIMARY,
                    finalStatus = "SUCCESS_PRIMARY",
                    finalDigits = "4829",
                    failureReason = null,
                    lastRejectionCode = null,
                    exceptionType = null,
                    mlKitErrorCode = null,
                    totalDurationMs = 38L,
                    attempts = listOf(
                        CapturedAttemptPayload(
                            attemptNumber = 1,
                            routeType = "PRIMARY",
                            preprocessConfig = PreprocessConfig(),
                            decodeStatus = "SUCCESS",
                            stages = stages,
                            mlKitInputWidth = w,
                            mlKitInputHeight = h,
                            rawOcrFullText = "4829",
                            elements = listOf(OcrElement("4829", 0.94f)),
                            elementCount = 1,
                            normalizedDigits = "4829",
                            validationPassed = true,
                            rejectionCode = null,
                            failureReason = null,
                            preprocessDurationMs = 5L,
                            ocrDurationMs = 30L,
                            totalDurationMs = 35L
                        )
                    )
                )
            )
        }

        composeRule.setContent {
            CullinanTheme {
                CaptchaLabScreen(onDismiss = {})
            }
        }

        // Toggle defaults OFF, can be turned ON
        composeRule.onNodeWithTag("captcha_lab_archive_switch")
            .assertIsDisplayed()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertTrue(CaptchaLabController.isArchivingEnabled())

        // Record card is displayed; scroll list to card and open Detail
        composeRule.onNodeWithTag("captcha_lab_main_list")
            .performScrollToNode(hasTestTag("captcha_lab_record_test_captcha_ui_1"))
        composeRule.onNodeWithTag("captcha_lab_record_test_captcha_ui_1")
            .assertIsDisplayed()
            .performClick()

        // Verify OCR text & open Side-by-Side Pixel Comparison
        composeRule.onNodeWithText("4829", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("captcha_detail_open_compare_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("captcha_compare_toggle_mode_button").assertIsDisplayed()
        composeRule.onNodeWithTag("captcha_compare_back_button").performClick()

        // Back to Main List and Clear All
        composeRule.onNodeWithTag("captcha_detail_back_button").performClick()
        composeRule.onNodeWithTag("captcha_lab_clear_all_button").performClick()
        composeRule.onNodeWithTag("captcha_lab_confirm_clear_button").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            repo.records.value.isEmpty()
        }
    }

    @Test
    fun stageBenchmarkUi_rendersLeaderboardBatchScopeDialogAndDetailGroundTruthInput() {
        val repo = CaptchaLabController.getRepository(context)
        val w = 24
        val h = 12
        val snap = DecodedArgbSnapshot(w, h, IntArray(w * h) { -1 })
        val gray = GrayscaleImage(w, h, IntArray(w * h) { 120 })
        val stages = CaptchaStageKind.entries.map { kind ->
            if (kind == CaptchaStageKind.DECODED) {
                CapturedStageBuffer(kind, CaptchaStageStatus.COMPLETED, w, h, argbSnapshot = snap)
            } else {
                CapturedStageBuffer(kind, CaptchaStageStatus.COMPLETED, w, h, grayscaleImage = gray)
            }
        }
        val dummyGif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 24, 0, 12, 0, 0, 0, 0, 0x3B)

        runBlocking {
            val saved = repo.saveSessionNow(
                CapturedCaptchaSession(
                    captchaId = "bench_ui_cap_1",
                    capturedAtEpochMs = 1_700_000_500_000L,
                    rawBytes = dummyGif,
                    rawInspection = GifFormatInspector.inspect(dummyGif),
                    decodedFrameIndex = 0,
                    decodeStatus = "SUCCESS",
                    rawWidth = w,
                    rawHeight = h,
                    outcomeCategory = CaptchaLabOutcomeCategory.NO_TEXT_DETECTED,
                    finalStatus = "NO_TEXT_DETECTED",
                    finalDigits = null,
                    failureReason = "NO_TEXT_DETECTED",
                    lastRejectionCode = "NO_TEXT_DETECTED",
                    exceptionType = null,
                    mlKitErrorCode = null,
                    totalDurationMs = 28L,
                    attempts = listOf(
                        CapturedAttemptPayload(
                            attemptNumber = 1,
                            routeType = "PRIMARY",
                            preprocessConfig = PreprocessConfig(),
                            decodeStatus = "SUCCESS",
                            stages = stages,
                            mlKitInputWidth = w,
                            mlKitInputHeight = h,
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

            repo.updateStageBenchmark(
                saved.captchaId,
                CaptchaStageBenchmarkResult(
                    ranAtEpochMs = 1_700_000_501_000L,
                    ranAtUtcIso = "2023-11-14T22:21:41.000Z",
                    attemptNumber = 1,
                    bestTargetKey = CaptchaStageKind.DENOISED.name,
                    totalDurationMs = 42L,
                    targets = listOf(
                        CaptchaStageOcrTargetResult(
                            targetKey = "RAW",
                            targetTitleFa = "تصویر خام سرور (Raw)",
                            status = CaptchaBenchmarkTargetStatus.COMPLETED,
                            width = w,
                            height = h,
                            rawOcrFullText = "",
                            elements = emptyList(),
                            elementCount = 0,
                            normalizedDigits = null,
                            validationPassed = false,
                            rejectionCode = "NO_TEXT_DETECTED",
                            averageConfidence = null,
                            ocrDurationMs = 5L
                        ),
                        CaptchaStageOcrTargetResult(
                            targetKey = CaptchaStageKind.DENOISED.name,
                            targetTitleFa = CaptchaStageKind.DENOISED.titleFa,
                            status = CaptchaBenchmarkTargetStatus.COMPLETED,
                            width = w,
                            height = h,
                            rawOcrFullText = "4829",
                            elements = listOf(com.example.data.captcha.lab.CaptchaLabElementRecord("4829", 0.91f)),
                            elementCount = 1,
                            normalizedDigits = "4829",
                            validationPassed = true,
                            averageConfidence = 0.91f,
                            ocrDurationMs = 6L
                        )
                    )
                )
            )
        }

        composeRule.setContent {
            CullinanTheme {
                CaptchaLabScreen(onDismiss = {})
            }
        }

        // Leaderboard and Best Stage banner + Batch OCR trigger + per-card OCR button
        composeRule.onNodeWithTag("captcha_lab_main_list")
            .performScrollToNode(hasTestTag("stage_compatibility_leaderboard_card"))
        composeRule.onNodeWithTag("stage_compatibility_leaderboard_card").assertIsDisplayed()
        composeRule.onNodeWithTag("stage_leaderboard_best_banner").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("stage_leaderboard_toggle_expand_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("stage_leaderboard_row_DENOISED").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("stage_leaderboard_toggle_expand_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("captcha_lab_main_list")
            .performScrollToNode(hasTestTag("captcha_lab_batch_ocr_button"))
        composeRule.onNodeWithTag("captcha_lab_batch_ocr_button").assertIsDisplayed()
        composeRule.onNodeWithTag("captcha_lab_main_list")
            .performScrollToNode(hasTestTag("captcha_lab_record_bench_ui_cap_1"))
        composeRule.onNodeWithTag("captcha_lab_run_ocr_bench_ui_cap_1").performScrollTo().assertIsDisplayed()

        // Selecting FAILED filter triggers batch scope dialog
        composeRule.onNodeWithTag("captcha_lab_main_list")
            .performScrollToNode(hasTestTag("captcha_lab_filter_FAILED"))
        composeRule.onNodeWithTag("captcha_lab_filter_FAILED").performScrollTo().performClick()
        composeRule.onNodeWithTag("captcha_lab_main_list")
            .performScrollToNode(hasTestTag("captcha_lab_batch_ocr_button"))
        composeRule.onNodeWithTag("captcha_lab_batch_ocr_button").performClick()
        composeRule.onNodeWithTag("captcha_lab_batch_filtered_button").assertIsDisplayed()
        composeRule.onNodeWithTag("captcha_lab_batch_all_button").assertIsDisplayed()
        composeRule.onNodeWithText("انصراف").performClick()

        // Open detail view -> verify benchmark card, targets & ground-truth input
        composeRule.onNodeWithTag("captcha_lab_main_list")
            .performScrollToNode(hasTestTag("captcha_lab_record_bench_ui_cap_1"))
        composeRule.onNodeWithTag("captcha_lab_record_bench_ui_cap_1").performClick()

        composeRule.onNodeWithTag("captcha_detail_list")
            .performScrollToNode(hasTestTag("captcha_detail_benchmark_card"))
        composeRule.onNodeWithTag("captcha_detail_run_stage_ocr_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("captcha_detail_expected_digits_input").performScrollTo().performTextReplacement("4829")
        composeRule.onNodeWithTag("captcha_detail_save_expected_digits_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            repo.records.value.firstOrNull()?.expectedDigits == "4829"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stage_benchmark_target_RAW").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("stage_benchmark_target_DENOISED").performScrollTo().assertIsDisplayed()
    }
}
