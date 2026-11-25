package com.example.ui.screens.foodbasket

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BasketActionButton
import com.example.data.model.BasketItemState
import com.example.data.model.BasketSlot
import com.example.data.model.BasketStatus
import com.example.ui.components.PersianRtlLayout
import com.example.ui.theme.StatusAvailableBlue
import com.example.ui.theme.StatusAvailableBlueBg
import com.example.ui.theme.StatusReservedGreen
import com.example.ui.theme.StatusReservedGreenBg
import com.example.ui.theme.TealPrimary
import com.example.ui.viewmodel.PendingBasketConfirmation

@Composable
internal fun FoodBasketResultCard(
    item: BasketItemState,
    isExecutingTransaction: Boolean,
    isBusy: Boolean,
    onRequestAction: (BasketActionButton) -> Unit,
    modifier: Modifier = Modifier
) {
    val isInBasket = item.status == BasketStatus.IN_BASKET
    val statusBg = if (isInBasket) StatusAvailableBlueBg else StatusReservedGreenBg
    val statusFg = if (isInBasket) StatusAvailableBlue else StatusReservedGreen

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, statusFg.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("basket_result_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusBg,
                    modifier = Modifier.testTag("basket_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isInBasket) Icons.Default.Storefront else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = statusFg,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = item.status.titlePersian,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = statusFg
                        )
                    }
                }

                Text(
                    text = "${item.slot.jalaliDate} • ${item.slot.mealType.titlePersian}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = item.foodName.ifBlank { "نام غذا نامشخص" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("basket_result_food_name")
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${item.selfName.ifBlank { "سلف نامشخص" }} • تعداد: ${item.count} پرس • ${item.slot.reserveType.titlePersian}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PriceSummaryTile(
                    label = "قیمت رزرو غذا",
                    value = item.reservePrice.ifBlank { "-" },
                    highlighted = false,
                    modifier = Modifier.weight(1f)
                )
                PriceSummaryTile(
                    label = "قیمت فروش به سبد",
                    value = item.salePrice.ifBlank { "-" },
                    highlighted = true,
                    modifier = Modifier.weight(1f)
                )
            }

            if (item.actionButton == BasketActionButton.ACCEPT) {
                Button(
                    onClick = { onRequestAction(BasketActionButton.ACCEPT) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TealPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("basket_sell_button")
                ) {
                    if (isExecutingTransaction) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("در حال ثبت در سبد غذایی...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sell,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.buttonLabel.ifBlank { BasketActionButton.ACCEPT.defaultButtonValue },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { onRequestAction(BasketActionButton.CANCEL) },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("basket_cancel_button")
                ) {
                    if (isExecutingTransaction) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("در حال حذف از سبد غذایی...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.buttonLabel.ifBlank { BasketActionButton.CANCEL.defaultButtonValue },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceSummaryTile(
    label: String,
    value: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (highlighted) TealPrimary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) TealPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun FoodBasketEmptyStateCard(
    noReservationSlot: BasketSlot?,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .testTag(if (noReservationSlot != null) "basket_no_reservation_card" else "basket_initial_hint_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (noReservationSlot != null) Icons.Default.Info else Icons.Default.ShoppingBasket,
                contentDescription = null,
                tint = TealPrimary.copy(alpha = 0.7f),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = if (noReservationSlot != null) {
                    "برای ${noReservationSlot.mealType.titlePersian} تاریخ ${noReservationSlot.jalaliDate} رزروی یافت نشد"
                } else {
                    "استعلام وضعیت سبد غذایی"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (noReservationSlot != null) {
                    "ممکن است برای این تاریخ رزرو ثبت نشده باشد، وعده مصرف شده باشد یا مهلت فروش آن پایان یافته باشد."
                } else {
                    "یکی از وعده‌های رزروشده بالا را لمس کنید یا تاریخ، نوع رزرو و وعده را انتخاب کرده و «بارگذاری غذا» را بزنید."
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun FoodBasketConfirmDialog(
    confirmation: PendingBasketConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val item = confirmation.item
    val isSell = confirmation.action == BasketActionButton.ACCEPT

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isSell) Icons.Default.Sell else Icons.Default.DeleteOutline,
                contentDescription = null,
                tint = if (isSell) TealPrimary else MaterialTheme.colorScheme.error
            )
        },
        title = {
            PersianRtlLayout {
                Text(
                    text = if (isSell) "تأیید فروش به سبد غذایی" else "تأیید حذف از سبد غذایی",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            PersianRtlLayout {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ConfirmDetailRow("نام غذا:", item.foodName.ifBlank { "-" })
                            ConfirmDetailRow("سلف:", item.selfName.ifBlank { "-" })
                            ConfirmDetailRow("تاریخ و وعده:", "${item.slot.jalaliDate} • ${item.slot.mealType.titlePersian}")
                            ConfirmDetailRow("نوع رزرو:", item.slot.reserveType.titlePersian)
                            ConfirmDetailRow(
                                label = "قیمت فروش به سبد:",
                                value = item.salePrice.ifBlank { "-" },
                                boldValue = true
                            )
                        }
                    }

                    Text(
                        text = if (isSell) {
                            "پس از تأیید، این غذا برای خرید سایر دانشجویان در سبد غذایی عرضه می‌شود. تا پیش از خریداری‌شدن توسط دیگری می‌توانید آن را حذف کنید."
                        } else {
                            "با تأیید این مورد، عرضه این غذا در سبد غذایی متوقف شده و وضعیت آن به رزروشده عادی بازمی‌گردد."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSell) TealPrimary else MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("basket_dialog_confirm")
            ) {
                Text(
                    text = if (isSell) "تأیید و فروش به سبد" else "تأیید و حذف از سبد",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("basket_dialog_cancel")
            ) {
                Text("انصراف")
            }
        }
    )
}

@Composable
private fun ConfirmDetailRow(
    label: String,
    value: String,
    boldValue: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (boldValue) FontWeight.Bold else FontWeight.Medium,
            color = if (boldValue) TealPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}
