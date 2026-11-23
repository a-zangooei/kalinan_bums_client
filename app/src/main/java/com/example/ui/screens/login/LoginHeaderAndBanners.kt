package com.example.ui.screens.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.PersianRtlLayout
import com.example.ui.theme.AccentGold
import com.example.ui.theme.CullinanTheme
import com.example.ui.theme.TealPrimary
import com.example.ui.theme.TealPrimaryDark

@Composable
internal fun LoginBrandingHeader(
    onOpenSettings: (() -> Unit)? = null
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(56.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.12f))
                .padding(4.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(TealPrimary, TealPrimaryDark)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.RestaurantMenu,
                contentDescription = "کالینان",
                tint = AccentGold,
                modifier = Modifier.size(26.dp)
            )
        }

        if (onOpenSettings != null) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = CircleShape
                    )
                    .testTag("login_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "تنظیمات برنامه",
                    tint = primaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "سامانه تغذیه کالینان",
        fontSize = 19.sp,
        fontWeight = FontWeight.ExtraBold,
        color = primaryColor
    )

    Text(
        text = "دانشگاه علوم پزشکی بیرجند",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium
    )
}

@Composable
internal fun ServerConnectionBadge(isCompatibilityModeActive: Boolean) {
    val statusColor = if (isCompatibilityModeActive) Color(0xFFE65100) else Color(0xFF2E7D32)
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCompatibilityModeActive) {
                Color(0xFFE65100).copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        ),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudQueue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "jeton.bums.ac.ir",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "•",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                text = if (isCompatibilityModeActive) "حالت سازگاری SSL" else "اتصال امن",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}

@Composable
internal fun EncryptionUnavailableBanner(visible: Boolean) {
    AnimatedVisibility(visible = visible) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("encryption_unavailable_banner"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "هشدار امنیتی",
                    tint = Color(0xFFE65100),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ذخیره‌ساز امن سخت‌افزاری (KeyStore) در این دستگاه در دسترس نیست. به منظور حفظ امنیت، قابلیت «ذخیره اطلاعات ورود» غیرفعال شده است و رمز عبور ذخیره نخواهد شد.",
                    color = Color(0xFFE65100),
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun BiometricUnavailableLockedBanner(
    visible: Boolean,
    onOpenSecuritySettings: () -> Unit,
    onClearSavedCredentials: () -> Unit
) {
    AnimatedVisibility(visible = visible) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("biometric_unavailable_banner"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "هشدار قفل امنیتی",
                        tint = Color(0xFFE65100),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "قفل بیومتریک برای اطلاعات ورود ذخیره‌شده فعال است اما اثر انگشت یا قفل صفحه در دستگاه در دسترس نیست. جهت حفظ امنیت، بازیابی خودکار مسدود گردید.",
                        color = Color(0xFFE65100),
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSecuritySettings,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("open_device_security_settings_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE65100))
                    ) {
                        Text(
                            text = "تنظیمات امنیتی دستگاه",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = onClearSavedCredentials,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("wipe_locked_credentials_button")
                    ) {
                        Text(
                            text = "پاک‌سازی رمز ذخیره‌شده",
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

@Composable
internal fun LoginErrorBanner(
    errorMessage: String?,
    onRetryConnection: () -> Unit
) {
    AnimatedVisibility(visible = errorMessage != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "اعلان خطا",
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFC62828),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onRetryConnection,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text(
                            text = "تلاش مجدد",
                            fontSize = 11.sp,
                            color = Color(0xFFC62828),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "LoginHeaderBanners - Light RTL")
@Composable
private fun LoginHeaderBannersLightPreview() {
    CullinanTheme(darkTheme = false) {
        PersianRtlLayout {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoginBrandingHeader()
                Spacer(modifier = Modifier.height(12.dp))
                ServerConnectionBadge(isCompatibilityModeActive = true)
                Spacer(modifier = Modifier.height(10.dp))
                BiometricUnavailableLockedBanner(
                    visible = true,
                    onOpenSecuritySettings = {},
                    onClearSavedCredentials = {}
                )
                EncryptionUnavailableBanner(visible = true)
                LoginErrorBanner(errorMessage = "کد امنیتی وارد شده اشتباه است", onRetryConnection = {})
            }
        }
    }
}

@Preview(showBackground = true, name = "LoginHeaderBanners - Dark RTL")
@Composable
private fun LoginHeaderBannersDarkPreview() {
    CullinanTheme(darkTheme = true) {
        PersianRtlLayout {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LoginBrandingHeader()
                Spacer(modifier = Modifier.height(12.dp))
                ServerConnectionBadge(isCompatibilityModeActive = false)
                Spacer(modifier = Modifier.height(10.dp))
                BiometricUnavailableLockedBanner(
                    visible = true,
                    onOpenSecuritySettings = {},
                    onClearSavedCredentials = {}
                )
                LoginErrorBanner(errorMessage = "خطا در ارتباط با سرور", onRetryConnection = {})
            }
        }
    }
}
