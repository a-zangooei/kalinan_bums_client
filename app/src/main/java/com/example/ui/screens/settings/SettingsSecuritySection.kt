package com.example.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import com.example.data.captcha.solver.OcrModuleState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.PersianRtlLayout
import com.example.ui.theme.CullinanTheme
import com.example.ui.theme.TealPrimary

@Composable
internal fun SecurityBiometricSection(
    isEncryptionAvailable: Boolean,
    canUseBiometric: Boolean,
    isBiometricEnabled: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onOpenDeviceSecuritySettings: () -> Unit = {},
    onRecoveryWipeAndDisableLock: () -> Unit = {}
) {
    SettingsSectionTitle(
        title = "امنیت و حفاظت از اطلاعات ورود",
        icon = Icons.Default.Fingerprint
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .toggleable(
                        value = isBiometricEnabled && isEncryptionAvailable,
                        enabled = isEncryptionAvailable && canUseBiometric,
                        role = Role.Switch,
                        onValueChange = onToggleBiometric
                    )
                    .padding(vertical = 4.dp)
                    .testTag("biometric_lock_switch"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "قفل بیومتریک یا رمز دستگاه برای رمز عبور ذخیره‌شده",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            !isEncryptionAvailable -> "رمزنگاری سخت‌افزاری دستگاه در دسترس نیست."
                            isBiometricEnabled && !canUseBiometric ->
                                "قفل امنیتی فعال است اما اثر انگشت یا قفل صفحه روی دستگاه تنظیم نشده؛ برای غیرفعال‌سازی، احراز هویت دستگاه را فعال یا رمز عبور ذخیره‌شده را پاک‌سازی کنید."
                            !canUseBiometric -> "در تنظیمات دستگاه، قفل صفحه یا اثر انگشت فعال نشده است."
                            else -> "پیش از پر شدن خودکار رمز عبور ذخیره‌شده و هنگام غیرفعال‌کردن این گزینه، احراز هویت دستگاه درخواست می‌شود."
                        },
                        fontSize = 10.sp,
                        color = if (isBiometricEnabled && !canUseBiometric && isEncryptionAvailable) {
                            Color(0xFFE65100)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Switch(
                    checked = isBiometricEnabled && isEncryptionAvailable,
                    enabled = isEncryptionAvailable && canUseBiometric,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = TealPrimary)
                )
            }

            AnimatedVisibility(visible = isEncryptionAvailable && isBiometricEnabled && !canUseBiometric) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenDeviceSecuritySettings,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("settings_open_security_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TealPrimary)
                        ) {
                            Text(
                                text = "تنظیمات امنیتی دستگاه",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(
                            onClick = onRecoveryWipeAndDisableLock,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("settings_wipe_biometric_recovery_button")
                        ) {
                            Text(
                                text = "پاک‌سازی رمز عبور و غیرفعال‌سازی",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AutoCaptchaSettingsSection(
    isAutoCaptchaEnabled: Boolean,
    onToggleAutoCaptcha: (Boolean) -> Unit,
    ocrModuleState: OcrModuleState = OcrModuleState.Ready,
    onDownloadOcrModule: () -> Unit = {}
) {
    val isModuleReady = ocrModuleState.isReady
    val switchChecked = isAutoCaptchaEnabled && isModuleReady

    SettingsSectionTitle(
        title = "کمک به ورود",
        icon = Icons.Default.Security
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .toggleable(
                        value = switchChecked,
                        enabled = isModuleReady,
                        role = Role.Switch,
                        onValueChange = onToggleAutoCaptcha
                    )
                    .padding(vertical = 4.dp)
                    .testTag("auto_captcha_switch"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "تشخیص خودکار کد امنیتی (روی دستگاه)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isModuleReady) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                    )
                    Text(
                        text = "تصویر فقط روی گوشی پردازش می‌شود، چیزی ارسال نمی‌شود و ممکن است گاهی اشتباه شود.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = switchChecked,
                    enabled = isModuleReady,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = TealPrimary)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            OcrModuleStatusPanel(
                ocrModuleState = ocrModuleState,
                onDownloadOcrModule = onDownloadOcrModule,
                statusBadgeTag = "settings_ocr_module_status",
                downloadButtonTag = "settings_download_ocr_button",
                progressTag = "settings_ocr_download_progress"
            )
        }
    }
}

@Composable
internal fun OcrModuleStatusPanel(
    ocrModuleState: OcrModuleState,
    onDownloadOcrModule: () -> Unit,
    statusBadgeTag: String,
    downloadButtonTag: String,
    progressTag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(statusBadgeTag),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (ocrModuleState) {
            OcrModuleState.Checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = TealPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "در حال بررسی وضعیت موتور OCR گوگل‌پلی...",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OcrModuleState.Ready -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "موتور OCR گوگل‌پلی روی دستگاه آماده است.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
            OcrModuleState.NotInstalled -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "برای کاهش حجم برنامه، موتور و مدل OCR داخل فایل نصبی نیست. برای استفاده از تشخیص خودکار، ابتدا آن را از گوگل‌پلی دریافت کنید.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onDownloadOcrModule,
                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(downloadButtonTag)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "دانلود موتور OCR از گوگل‌پلی",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            is OcrModuleState.Downloading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(progressTag),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val percent = ocrModuleState.progressPercent
                    Text(
                        text = if (percent != null) {
                            "در حال دانلود موتور OCR از گوگل‌پلی ($percent٪)..."
                        } else {
                            "در حال آماده‌سازی و دانلود موتور OCR از گوگل‌پلی..."
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TealPrimary
                    )
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            color = TealPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            color = TealPrimary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            is OcrModuleState.PlayServicesUnavailable -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFE65100).copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Google Play Services روی این دستگاه در دسترس نیست یا نیازمند به‌روزرسانی است؛ تشخیص خودکار غیرفعال می‌ماند و می‌توانید کد امنیتی را به‌صورت دستی وارد کنید.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }
            is OcrModuleState.Failed -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFC62828).copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFC62828),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${ocrModuleState.message} تشخیص خودکار غیرفعال است و ورود دستی کد برقرار می‌باشد.",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Color(0xFFC62828)
                            )
                        }
                        OutlinedButton(
                            onClick = onDownloadOcrModule,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TealPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(downloadButtonTag)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "تلاش مجدد دانلود از گوگل‌پلی",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "SettingsSecurity - Light RTL")
@Composable
private fun SettingsSecurityLightPreview() {
    CullinanTheme(darkTheme = false) {
        PersianRtlLayout {
            Column(modifier = Modifier.padding(16.dp)) {
                SecurityBiometricSection(
                    isEncryptionAvailable = true,
                    canUseBiometric = true,
                    isBiometricEnabled = true,
                    onToggleBiometric = {}
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "SettingsSecurity - Dark RTL")
@Composable
private fun SettingsSecurityDarkPreview() {
    CullinanTheme(darkTheme = true) {
        PersianRtlLayout {
            Column(modifier = Modifier.padding(16.dp)) {
                SecurityBiometricSection(
                    isEncryptionAvailable = true,
                    canUseBiometric = false,
                    isBiometricEnabled = true,
                    onToggleBiometric = {}
                )
            }
        }
    }
}
