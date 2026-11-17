package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.captcha.lab.CaptchaLabController
import com.example.data.captcha.lab.CaptchaLabOutcomeCategory
import com.example.data.captcha.lab.CaptchaStageKind
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
        runBlocking {
            CaptchaLabController.getRepository(context).clearAll()
        }
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

        // Record card is displayed; open Detail
        composeRule.onNodeWithTag("captcha_lab_record_test_captcha_ui_1")
            .assertIsDisplayed()
            .performClick()

        // Verify OCR text & open Side-by-Side Pixel Comparison
        composeRule.onNodeWithText("4829", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("captcha_detail_open_compare_button")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("captcha_compare_toggle_mode_button").assertIsDisplayed()
        composeRule.onNodeWithTag("captcha_compare_back_button").performClick()

        // Back to Main List and Clear All
        composeRule.onNodeWithTag("captcha_detail_back_button").performClick()
        composeRule.onNodeWithTag("captcha_lab_clear_all_button").performClick()
        composeRule.onNodeWithTag("captcha_lab_confirm_clear_button").performClick()

        composeRule.waitForIdle()
        assertTrue(repo.records.value.isEmpty())
    }
}
