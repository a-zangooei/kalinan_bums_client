package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AppUpdateInfo
import com.example.ui.theme.CullinanTheme
import com.example.ui.theme.StatusAvailableBlue
import com.example.ui.theme.StatusAvailableBlueBg
import com.example.ui.theme.StatusReservedGreen
import com.example.ui.theme.StatusReservedGreenBg
import com.example.ui.viewmodel.UpdateStatusKind
import com.example.ui.viewmodel.UpdateUserStatus

@Composable
fun UpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val formattedDate = formatReleaseDateBadge(updateInfo.publishedAt)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        PersianRtlLayout {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 400.dp)
                        .testTag("update_dialog_card"),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Header Icon Badge
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NewReleases,
                                    contentDescription = "نسخه جدید",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "نسخه جدید کالینان در دسترس است",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                lineHeight = 24.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_dialog_title")
                            )

                            if (formattedDate != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "تاریخ انتشار: $formattedDate",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Version Comparison Card (RTL: current on start/right -> latest on end/left)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_version_comparison_card")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "نسخه فعلی",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "v${updateInfo.currentVersionName}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = "نسخه جدید",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "v${updateInfo.latestVersionName}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Bounded Scrollable Changelog Card
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Notes,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "تغییرات و توضیحات نسخه جدید:",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 32.dp, max = 140.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = updateInfo.changelogSnippet.ifBlank {
                                                "بهبودهای عملکردی، پایداری بیشتر و رفع اشکال‌های جزئی."
                                            },
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.testTag("update_changelog_text")
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Stacked Full-Width Action Buttons with Clear Separation
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try {
                                    context.startActivity(intent)
                                    onDismiss()
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(
                                        context,
                                        "مرورگری برای باز کردن لینک به‌روزرسانی یافت نشد.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("update_download_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "دریافت و نصب نسخه جدید",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("update_later_button")
                        ) {
                            Text(
                                text = "بعداً یادآوری کن",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateStatusDialog(
    status: UpdateUserStatus,
    onDismiss: () -> Unit
) {
    val icon = when (status.kind) {
        UpdateStatusKind.UP_TO_DATE -> Icons.Default.CheckCircle
        UpdateStatusKind.NO_PUBLISHED_RELEASE -> Icons.Default.Info
        UpdateStatusKind.ERROR -> Icons.Default.ErrorOutline
    }
    val badgeBg = when (status.kind) {
        UpdateStatusKind.UP_TO_DATE -> StatusReservedGreenBg
        UpdateStatusKind.NO_PUBLISHED_RELEASE -> StatusAvailableBlueBg
        UpdateStatusKind.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val iconTint = when (status.kind) {
        UpdateStatusKind.UP_TO_DATE -> StatusReservedGreen
        UpdateStatusKind.NO_PUBLISHED_RELEASE -> StatusAvailableBlue
        UpdateStatusKind.ERROR -> MaterialTheme.colorScheme.error
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        PersianRtlLayout {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 380.dp)
                        .testTag("update_status_dialog_card"),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(badgeBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = status.title,
                                tint = iconTint,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = status.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_status_dialog_title")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = status.message,
                            fontSize = 13.sp,
                            lineHeight = 21.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_status_dialog_message")
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("update_status_dismiss_button")
                        ) {
                            Text(
                                text = "متوجه شدم",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatReleaseDateBadge(publishedAt: String): String? {
    val trimmed = publishedAt.trim()
    if (trimmed.isEmpty()) return null
    val datePart = trimmed.substringBefore('T').trim()
    return datePart.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
}

@Preview(showBackground = true, name = "UpdateDialog - Light RTL")
@Composable
private fun UpdateDialogLightPreview() {
    CullinanTheme(darkTheme = false) {
        UpdateDialog(
            updateInfo = AppUpdateInfo(
                isUpdateAvailable = true,
                latestVersionName = "1.2.0",
                currentVersionName = "1.1.0",
                downloadUrl = "https://github.com/a-zangooei/kalinan_bums_client/releases",
                changelogSnippet = "• بازطراحی صفحه ورود با چیدمان ثابت و متعادل\n• همگام‌سازی کامل خروج پویا با تمامی صفحات سامانه\n• بهبود خوانایی و پایداری ارتباط شبکه",
                publishedAt = "2026-11-24T10:00:00Z"
            ),
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true, name = "UpdateDialog - Dark RTL")
@Composable
private fun UpdateDialogDarkPreview() {
    CullinanTheme(darkTheme = true) {
        UpdateDialog(
            updateInfo = AppUpdateInfo(
                isUpdateAvailable = true,
                latestVersionName = "1.2.0-beta.1",
                currentVersionName = "1.1.0",
                downloadUrl = "https://github.com/a-zangooei/kalinan_bums_client/releases",
                changelogSnippet = "• بهبود رابط کاربری مدال به‌روزرسانی\n• رفع اشکالات جزئی",
                publishedAt = "2026-11-24T10:00:00Z"
            ),
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true, name = "UpdateStatusDialog - Up To Date")
@Composable
private fun UpdateStatusDialogPreview() {
    CullinanTheme(darkTheme = false) {
        UpdateStatusDialog(
            status = UpdateUserStatus(
                title = "برنامه به‌روز است",
                message = "برنامه شما به‌روز است (نسخه 1.1.0)",
                kind = UpdateStatusKind.UP_TO_DATE
            ),
            onDismiss = {}
        )
    }
}
