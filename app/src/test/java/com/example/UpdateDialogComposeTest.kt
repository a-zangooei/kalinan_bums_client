package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.model.AppUpdateInfo
import com.example.ui.components.PersianRtlLayout
import com.example.ui.components.UpdateDialog
import com.example.ui.components.UpdateStatusDialog
import com.example.ui.screens.settings.AboutVersionSection
import com.example.ui.theme.CullinanTheme
import com.example.ui.viewmodel.UpdateStatusKind
import com.example.ui.viewmodel.UpdateUserStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDialogComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    fun updateDialog_buttonsAreStackedWithClearSpacingAndAccessibleTargets() {
        var dismissed = false
        val info = AppUpdateInfo(
            isUpdateAvailable = true,
            latestVersionName = "1.2.0",
            currentVersionName = "1.1.0",
            downloadUrl = "https://github.com/a-zangooei/kalinan_bums_client/releases",
            changelogSnippet = "• بهبود رابط کاربری صفحه ورود\n• همگام‌سازی خروج پویا",
            publishedAt = "2026-11-24T10:00:00Z"
        )

        composeRule.setContent {
            CullinanTheme {
                UpdateDialog(
                    updateInfo = info,
                    onDismiss = { dismissed = true }
                )
            }
        }

        val downloadNode = composeRule.onNodeWithTag("update_download_button")
        val laterNode = composeRule.onNodeWithTag("update_later_button")

        downloadNode.assertIsDisplayed()
        laterNode.assertIsDisplayed()
        composeRule.onNodeWithTag("update_dialog_title").assertIsDisplayed()
        composeRule.onNodeWithTag("update_version_comparison_card").assertIsDisplayed()
        composeRule.onNodeWithText("v1.1.0").assertIsDisplayed()
        composeRule.onNodeWithText("v1.2.0").assertIsDisplayed()
        composeRule.onNodeWithText("تاریخ انتشار: 2026-11-24").assertIsDisplayed()

        val downloadBounds = downloadNode.getBoundsInRoot()
        val laterBounds = laterNode.getBoundsInRoot()

        val verticalGap = laterBounds.top - downloadBounds.bottom
        assertTrue(
            "Expected stacked buttons with at least 10.dp spacing, but gap was $verticalGap",
            verticalGap >= 10.dp
        )

        val downloadHeight = downloadBounds.bottom - downloadBounds.top
        val laterHeight = laterBounds.bottom - laterBounds.top
        assertTrue("Expected download button >= 48.dp, got $downloadHeight", downloadHeight >= 48.dp)
        assertTrue("Expected later button >= 48.dp, got $laterHeight", laterHeight >= 48.dp)

        laterNode.performClick()
        assertTrue("Expected onDismiss to be called when clicking later", dismissed)
    }

    @Test
    fun updateDialog_compactViewport_keepsActionButtonsPinnedAndVisible() {
        val longChangelog = (1..20).joinToString("\n") { "• تغییر شماره $it برای بهبود عملکرد و ثبات سامانه" }
        val info = AppUpdateInfo(
            isUpdateAvailable = true,
            latestVersionName = "1.2.0",
            currentVersionName = "1.1.0",
            downloadUrl = "https://github.com/a-zangooei/kalinan_bums_client/releases",
            changelogSnippet = longChangelog,
            publishedAt = "2026-11-24T10:00:00Z"
        )

        composeRule.setContent {
            CullinanTheme {
                UpdateDialog(
                    updateInfo = info,
                    onDismiss = {}
                )
            }
        }

        val downloadNode = composeRule.onNodeWithTag("update_download_button")
        val laterNode = composeRule.onNodeWithTag("update_later_button")

        downloadNode.assertIsDisplayed()
        laterNode.assertIsDisplayed()

        val verticalGap = laterNode.getBoundsInRoot().top - downloadNode.getBoundsInRoot().bottom
        assertTrue("Expected >= 10.dp gap on compact viewport, got $verticalGap", verticalGap >= 10.dp)
    }

    @Test
    fun updateStatusDialog_displaysStatusAndDismissesOnAction() {
        var dismissed = false
        val status = UpdateUserStatus(
            title = "برنامه به‌روز است",
            message = "برنامه شما به‌روز است (نسخه 1.1.0)",
            kind = UpdateStatusKind.UP_TO_DATE
        )

        composeRule.setContent {
            CullinanTheme {
                UpdateStatusDialog(
                    status = status,
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeRule.onNodeWithTag("update_status_dialog_title").assertIsDisplayed()
        composeRule.onNodeWithTag("update_status_dialog_message").assertIsDisplayed()
        composeRule.onNodeWithTag("update_status_dismiss_button")
            .assertIsDisplayed()
            .performClick()

        assertTrue(dismissed)
    }

    @Test
    fun settingsAboutSection_showsLoadingStateAndDisablesButtonWhileChecking() {
        composeRule.setContent {
            CullinanTheme {
                PersianRtlLayout {
                    AboutVersionSection(
                        versionName = "1.1.0",
                        versionCode = 66,
                        onVersionClick = {},
                        onCheckForUpdates = {},
                        isCheckingForUpdates = true
                    )
                }
            }
        }

        composeRule.onNodeWithTag("settings_check_updates_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("در حال بررسی به‌روزرسانی...").assertIsDisplayed()
    }

    @Test
    fun settingsAboutSection_enablesButtonWhenIdle() {
        var clicked = false
        composeRule.setContent {
            CullinanTheme {
                PersianRtlLayout {
                    AboutVersionSection(
                        versionName = "1.1.0",
                        versionCode = 66,
                        onVersionClick = {},
                        onCheckForUpdates = { clicked = true },
                        isCheckingForUpdates = false
                    )
                }
            }
        }

        composeRule.onNodeWithTag("settings_check_updates_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertTrue(clicked)
    }
}
