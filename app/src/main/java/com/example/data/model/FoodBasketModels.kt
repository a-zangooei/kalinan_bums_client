package com.example.data.model

import androidx.compose.runtime.Immutable

/**
 * Modular sub-mode inside the Food Basket tab.
 * Currently only SALE is active; BUY can plug into the same screen/ViewModel in the future.
 */
enum class BasketSubMode(val titlePersian: String) {
    SALE("فروش به سبد غذایی")
}

enum class BasketReserveType(
    val formValue: String,
    val titlePersian: String
) {
    WEEKLY("0", "رزرو هفتگی"),
    DAILY_SALE("1", "روزفروش");

    companion object {
        fun fromFormValue(value: String?): BasketReserveType {
            return entries.firstOrNull { it.formValue == value?.trim() } ?: WEEKLY
        }
    }
}

enum class BasketMealType(
    val formValue: String,
    val titlePersian: String,
    val mealType: MealType
) {
    BREAKFAST("1", "صبحانه", MealType.BREAKFAST),
    LUNCH("2", "ناهار", MealType.LUNCH),
    DINNER("3", "شام", MealType.DINNER);

    companion object {
        fun fromFormValue(value: String?): BasketMealType {
            return entries.firstOrNull { it.formValue == value?.trim() } ?: BREAKFAST
        }

        fun fromMealType(mealType: MealType): BasketMealType {
            return entries.first { it.mealType == mealType }
        }
    }
}

enum class BasketActionButton(
    val controlId: String,
    val controlName: String,
    val defaultButtonValue: String
) {
    ACCEPT(
        controlId = "cphMain_btnAccept",
        controlName = "ctl00\$cphMain\$btnAccept",
        defaultButtonValue = "فروش به سبد غذا"
    ),
    CANCEL(
        controlId = "cphMain_btnCancel",
        controlName = "ctl00\$cphMain\$btnCancel",
        defaultButtonValue = "حذف از سبد غذا"
    )
}

enum class BasketStatus(val titlePersian: String) {
    RESERVED("این غذا رزرو شده است"),
    IN_BASKET("این غذا در سبد غذایی برای فروش قرار دارد"),
    NO_RESERVATION("برای این تاریخ و وعده رزروی یافت نشد"),
    UNKNOWN("وضعیت نامشخص")
}

@Immutable
data class BasketSlot(
    val jalaliDate: String,
    val mealType: BasketMealType,
    val reserveType: BasketReserveType = BasketReserveType.WEEKLY
)

@Immutable
data class QuickReservedSlot(
    val dayName: String,
    val jalaliDate: String,
    val mealType: BasketMealType,
    val foodName: String,
    val diningHall: String,
    val priceFormatted: String,
    val count: Int,
    val isLockedInWeeklyMenu: Boolean
)

@Immutable
data class BasketItemState(
    val slot: BasketSlot,
    val reservationId: String,
    val status: BasketStatus,
    val actionButton: BasketActionButton,
    val buttonLabel: String,
    val serverStatusLabel: String,
    val foodName: String,
    val selfName: String,
    val count: Int,
    val reservePrice: String,
    val salePrice: String
)

@Immutable
data class BasketPageSession(
    val formAction: String = "./FoodBasketSale.aspx",
    val defaultDateJalali: String = "",
    val selectedReserveType: BasketReserveType = BasketReserveType.WEEKLY,
    val selectedMealType: BasketMealType = BasketMealType.BREAKFAST,
    val webFormsState: WebFormsState = WebFormsState(
        viewState = "",
        viewStateGenerator = "92079A9C",
        eventValidation = ""
    ),
    val hasResultPanel: Boolean = false,
    val currentReservationId: String? = null,
    val alertMessage: String? = null
)

@Immutable
data class BasketReloadResult(
    val slot: BasketSlot,
    val item: BasketItemState?,
    val updatedSession: BasketPageSession,
    val alertMessage: String? = null,
    val toastType: String? = null,
    val toastMessage: String? = null
)

@Immutable
data class BasketActionOutcome(
    val action: BasketActionButton,
    val isSuccess: Boolean,
    val toastType: String,
    val toastTitle: String,
    val toastMessage: String,
    val updatedItem: BasketItemState?,
    val updatedSession: BasketPageSession,
    val alertMessage: String? = null
)

class BasketUncertainTimeoutException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

object FoodBasketDateUtils {

    private val JALALI_SERVER_PATTERN = Regex(
        """^((0[1-9]|[12][0-9]|3[01])[- /.](0[1-9]|1[012])[- /.](1)\d\d\d|(1)\d\d\d[- /.](0[1-9]|1[012])[- /.](0[1-9]|[12][0-9]|3[01]))$"""
    )

    fun toEnglishDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalizeJalaliDate(input: String): String {
        val ascii = toEnglishDigits(input).trim()
        val matchYmd = Regex("""^(1\d{3})[-/.](\d{1,2})[-/.](\d{1,2})$""").matchEntire(ascii)
        if (matchYmd != null) {
            val y = matchYmd.groupValues[1]
            val m = matchYmd.groupValues[2].padStart(2, '0')
            val d = matchYmd.groupValues[3].padStart(2, '0')
            return "$y/$m/$d"
        }
        return ascii
    }

    fun isValidJalaliDate(input: String): Boolean {
        val normalized = normalizeJalaliDate(input)
        return JALALI_SERVER_PATTERN.matches(normalized)
    }
}
