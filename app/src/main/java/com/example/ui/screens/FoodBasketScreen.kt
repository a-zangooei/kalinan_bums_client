package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BasketSubMode
import com.example.ui.components.PersianRtlLayout
import com.example.ui.screens.foodbasket.FoodBasketConfirmDialog
import com.example.ui.screens.foodbasket.FoodBasketEmptyStateCard
import com.example.ui.screens.foodbasket.FoodBasketQueryCard
import com.example.ui.screens.foodbasket.FoodBasketResultCard
import com.example.ui.screens.foodbasket.FoodBasketTopBar
import com.example.ui.screens.foodbasket.QuickReservedSlotsSection
import com.example.ui.theme.AmberSecondary
import com.example.ui.theme.AmberSecondaryContainer
import com.example.ui.theme.StatusReservedGreen
import com.example.ui.theme.StatusReservedGreenBg
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.FoodBasketViewModel

@Composable
fun FoodBasketScreen(
    viewModel: FoodBasketViewModel,
    onLogout: () -> Unit,
    isLoggingOut: Boolean,
    profileAvatarBytes: ByteArray? = null,
    onOpenProfile: () -> Unit,
    onOpenMessagesTab: () -> Unit,
    onOpenSurveyWebView: (String) -> Unit,
    onReloginRequired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.ensurePageLoaded()
    }

    PersianRtlLayout {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            FoodBasketTopBar(
                subMode = uiState.subMode,
                avatarBytes = profileAvatarBytes,
                onOpenProfile = onOpenProfile,
                onRefreshPage = { viewModel.refreshPage() },
                onLogout = onLogout,
                isRefreshing = uiState.isInitializingPage,
                isLoggingOut = isLoggingOut,
                isTransactionLocked = uiState.isExecutingTransaction || uiState.isRecoveringFromTimeout
            )

            if (uiState.isInitializingPage || uiState.isRecoveringFromTimeout) {
                LinearProgressIndicator(
                    color = TealPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                when (uiState.subMode) {
                    BasketSubMode.SALE -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 600.dp)
                                .testTag("basket_lazy_column")
                        ) {
                            uiState.sessionExpiredMessage?.let { expiredMsg ->
                                item(key = "session_expired") {
                                    GateActionBanner(
                                        title = "انقضای نشست کاربری",
                                        message = expiredMsg,
                                        actionText = "ورود مجدد",
                                        onAction = onReloginRequired,
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        tagName = "basket_session_expired_banner"
                                    )
                                }
                            }

                            if (uiState.hasUnreadMessagesBlocked) {
                                item(key = "unread_messages_gate") {
                                    GateActionBanner(
                                        title = "دسترسی مسدود به دلیل پیام خوانده‌نشده",
                                        message = "برای استفاده از سبد غذایی ابتدا باید پیام‌های خوانده‌نشده سامانه را مطالعه کنید.",
                                        actionText = "مشاهده پیام‌ها",
                                        onAction = onOpenMessagesTab,
                                        containerColor = AmberSecondaryContainer,
                                        contentColor = AmberSecondary,
                                        tagName = "basket_unread_gate_banner"
                                    )
                                }
                            }

                            uiState.surveyBlockInfo?.let { survey ->
                                item(key = "survey_gate") {
                                    GateActionBanner(
                                        title = "نظرسنجی اجباری سامانه",
                                        message = survey.message,
                                        actionText = "ورود به نظرسنجی",
                                        onAction = { onOpenSurveyWebView(survey.surveyUrl) },
                                        containerColor = AmberSecondaryContainer,
                                        contentColor = AmberSecondary,
                                        tagName = "basket_survey_gate_banner"
                                    )
                                }
                            }

                            uiState.feedbackBanner?.let { feedback ->
                                item(key = "feedback_banner") {
                                    val container = when {
                                        feedback.isSuccess -> StatusReservedGreenBg
                                        feedback.isWarning -> AmberSecondaryContainer
                                        else -> MaterialTheme.colorScheme.errorContainer
                                    }
                                    val foreground = when {
                                        feedback.isSuccess -> StatusReservedGreen
                                        feedback.isWarning -> AmberSecondary
                                        else -> MaterialTheme.colorScheme.onErrorContainer
                                    }
                                    val icon = when {
                                        feedback.isSuccess -> Icons.Default.CheckCircle
                                        feedback.isWarning -> Icons.Default.WarningAmber
                                        else -> Icons.Default.ErrorOutline
                                    }
                                    DismissibleFeedbackCard(
                                        message = feedback.message,
                                        icon = icon,
                                        containerColor = container,
                                        contentColor = foreground,
                                        onDismiss = { viewModel.clearFeedback() },
                                        testTag = "basket_feedback_banner",
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }

                            uiState.pageSession?.alertMessage?.takeIf { it.isNotBlank() }?.let { serverAlert ->
                                item(key = "server_alert") {
                                    DismissibleFeedbackCard(
                                        message = serverAlert,
                                        icon = Icons.Default.WarningAmber,
                                        containerColor = AmberSecondaryContainer,
                                        contentColor = AmberSecondary,
                                        onDismiss = null,
                                        testTag = "basket_server_alert_banner"
                                    )
                                }
                            }

                            uiState.errorMessage?.let { err ->
                                item(key = "error_banner") {
                                    DismissibleFeedbackCard(
                                        message = err,
                                        icon = Icons.Default.ErrorOutline,
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        onDismiss = { viewModel.clearFeedback() },
                                        testTag = "basket_error_banner"
                                    )
                                }
                            }

                            item(key = "quick_slots") {
                                QuickReservedSlotsSection(
                                    quickSlots = uiState.quickReservedSlots,
                                    selectedDate = uiState.jalaliDateInput,
                                    selectedMeal = uiState.selectedMealType,
                                    selectedReserveType = uiState.selectedReserveType,
                                    enabled = !uiState.isBusy,
                                    onSelectQuickSlot = { viewModel.selectQuickSlot(it) }
                                )
                            }

                            item(key = "query_form") {
                                FoodBasketQueryCard(
                                    jalaliDateInput = uiState.jalaliDateInput,
                                    defaultDateJalali = uiState.pageSession?.defaultDateJalali.orEmpty(),
                                    selectedReserveType = uiState.selectedReserveType,
                                    selectedMealType = uiState.selectedMealType,
                                    isReloading = uiState.isReloadingSlot,
                                    isBusy = uiState.isBusy,
                                    onDateChanged = { viewModel.onJalaliDateChanged(it) },
                                    onFillToday = { viewModel.fillTodayDate() },
                                    onReserveTypeSelected = { viewModel.onReserveTypeSelected(it) },
                                    onMealTypeSelected = { viewModel.onMealTypeSelected(it) },
                                    onReloadClick = { viewModel.loadCurrentSlot() }
                                )
                            }

                            item(key = "result_or_empty") {
                                val currentItem = uiState.currentItem
                                if (currentItem != null) {
                                    FoodBasketResultCard(
                                        item = currentItem,
                                        isExecutingTransaction = uiState.isExecutingTransaction,
                                        isBusy = uiState.isBusy,
                                        onRequestAction = { action -> viewModel.requestActionConfirmation(action) }
                                    )
                                } else if (uiState.isInitializingPage && uiState.pageSession == null) {
                                    Card(
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = TealPrimary,
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.5.dp
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = "در حال دریافت فرم سبد غذایی از سامانه...",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                } else {
                                    FoodBasketEmptyStateCard(noReservationSlot = uiState.noReservationForSlot)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.pendingConfirmation?.let { pending ->
        FoodBasketConfirmDialog(
            confirmation = pending,
            onConfirm = { viewModel.confirmPendingAction() },
            onDismiss = { viewModel.dismissConfirmation() }
        )
    }
}

@Composable
private fun DismissibleFeedbackCard(
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    onDismiss: (() -> Unit)?,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "بستن پیام",
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GateActionBanner(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    tagName: String
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tagName)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = contentColor
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = contentColor,
                    contentColor = Color.White
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(actionText, fontWeight = FontWeight.Bold)
            }
        }
    }
}
