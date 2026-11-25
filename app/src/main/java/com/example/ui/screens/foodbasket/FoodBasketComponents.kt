package com.example.ui.screens.foodbasket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BasketMealType
import com.example.data.model.BasketReserveType
import com.example.data.model.BasketSubMode
import com.example.data.model.QuickReservedSlot
import com.example.ui.screens.menu.ProfileAvatarActionButton
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AmberSecondary
import com.example.ui.theme.AmberSecondaryContainer
import com.example.ui.theme.TealPrimary
import com.example.ui.theme.TealPrimaryContainer
import com.example.ui.theme.TealPrimaryDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodBasketTopBar(
    subMode: BasketSubMode,
    avatarBytes: ByteArray?,
    onOpenProfile: () -> Unit,
    onRefreshPage: () -> Unit,
    onLogout: () -> Unit,
    isRefreshing: Boolean,
    isLoggingOut: Boolean,
    isTransactionLocked: Boolean
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(AccentGold, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ShoppingBasket,
                        contentDescription = null,
                        tint = TealPrimaryDark,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "سبد غذایی",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = subMode.titlePersian,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                }
            }
        },
        actions = {
            ProfileAvatarActionButton(
                avatarBytes = avatarBytes,
                onClick = onOpenProfile,
                iconTint = Color.White,
                testTagName = "basket_profile_button"
            )
            IconButton(
                onClick = onRefreshPage,
                enabled = !isRefreshing && !isTransactionLocked,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("basket_page_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "تازه‌سازی صفحه سبد غذایی",
                    tint = Color.White
                )
            }
            IconButton(
                onClick = onLogout,
                enabled = !isLoggingOut && !isTransactionLocked,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("basket_logout_button")
            ) {
                if (isLoggingOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ExitToApp,
                        contentDescription = "خروج از حساب کاربری",
                        tint = Color.White
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = TealPrimary,
            titleContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
}

@Composable
internal fun QuickReservedSlotsSection(
    quickSlots: List<QuickReservedSlot>,
    selectedDate: String,
    selectedMeal: BasketMealType,
    selectedReserveType: BasketReserveType,
    enabled: Boolean,
    onSelectQuickSlot: (QuickReservedSlot) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "وعده‌های رزروشده هفته جاری",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (quickSlots.isNotEmpty()) {
                Text(
                    text = "${quickSlots.size} وعده",
                    style = MaterialTheme.typography.labelMedium,
                    color = TealPrimary
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (quickSlots.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "در منوی هفتگی بارگذاری‌شده رزرو فعالی دیده نشد؛ می‌توانید تاریخ جلالی و وعده دلخواه را از فرم زیر استعلام بگیرید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(
                    items = quickSlots,
                    key = { "${it.jalaliDate}_${it.mealType.formValue}_${it.foodName}" }
                ) { slot ->
                    val isSelected = selectedReserveType == BasketReserveType.WEEKLY &&
                        selectedDate == slot.jalaliDate &&
                        selectedMeal == slot.mealType

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) TealPrimaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) TealPrimary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp),
                        modifier = Modifier
                            .width(208.dp)
                            .heightIn(min = 96.dp)
                            .clickable(enabled = enabled) { onSelectQuickSlot(slot) }
                            .testTag("basket_quick_slot_${slot.jalaliDate}_${slot.mealType.formValue}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${slot.dayName} • ${slot.jalaliDate}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) TealPrimaryDark else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) TealPrimary else TealPrimary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = slot.mealType.titlePersian,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) Color.White else TealPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Text(
                                text = slot.foodName.ifBlank { "غذای رزروشده" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = slot.diningHall.ifBlank { "سلف دانشجویی" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FoodBasketQueryCard(
    jalaliDateInput: String,
    defaultDateJalali: String,
    selectedReserveType: BasketReserveType,
    selectedMealType: BasketMealType,
    isReloading: Boolean,
    isBusy: Boolean,
    onDateChanged: (String) -> Unit,
    onFillToday: () -> Unit,
    onReserveTypeSelected: (BasketReserveType) -> Unit,
    onMealTypeSelected: (BasketMealType) -> Unit,
    onReloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth()
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
                Text(
                    text = "تعیین بازه زمانی و وعده غذایی",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (defaultDateJalali.isNotBlank()) {
                    TextButton(
                        onClick = onFillToday,
                        enabled = !isBusy,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("basket_today_button")
                    ) {
                        Text(
                            text = "امروز ($defaultDateJalali)",
                            style = MaterialTheme.typography.labelMedium,
                            color = TealPrimary
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = jalaliDateInput,
                    onValueChange = onDateChanged,
                    enabled = !isBusy,
                    singleLine = true,
                    label = { Text("تاریخ جلالی (yyyy/MM/dd)") },
                    placeholder = { Text("1405/09/03") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = "تاریخ"
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { if (!isBusy) onReloadClick() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("basket_date_input")
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "نوع رزرو",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasketReserveType.entries.forEach { reserveType ->
                        val selected = selectedReserveType == reserveType
                        FilterChip(
                            selected = selected,
                            onClick = { onReserveTypeSelected(reserveType) },
                            enabled = !isBusy,
                            label = {
                                Text(
                                    text = reserveType.titlePersian,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TealPrimaryContainer,
                                selectedLabelColor = TealPrimaryDark
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("basket_reserve_type_${reserveType.formValue}")
                        )
                    }
                }
                if (selectedReserveType == BasketReserveType.DAILY_SALE) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AmberSecondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "نکته: پشتیبانی سرور برای فروش رزروهای «روزفروش» به سبد غذایی هنوز روی حساب واقعی آزمایش نشده است.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "وعده غذایی",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BasketMealType.entries.forEach { meal ->
                        val selected = selectedMealType == meal
                        FilterChip(
                            selected = selected,
                            onClick = { onMealTypeSelected(meal) },
                            enabled = !isBusy,
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Restaurant,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            label = {
                                Text(
                                    text = meal.titlePersian,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TealPrimaryContainer,
                                selectedLabelColor = TealPrimaryDark,
                                selectedLeadingIconColor = TealPrimaryDark
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("basket_meal_type_${meal.formValue}")
                        )
                    }
                }
            }

            Button(
                onClick = onReloadClick,
                enabled = !isBusy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TealPrimary,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("basket_reload_button")
            ) {
                if (isReloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("در حال بارگذاری غذا...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "بارگذاری غذا",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
