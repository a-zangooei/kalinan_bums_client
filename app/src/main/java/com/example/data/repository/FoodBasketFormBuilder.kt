package com.example.data.repository

import com.example.data.model.BasketActionButton
import com.example.data.model.BasketPageSession
import com.example.data.model.BasketSlot
import com.example.data.model.FoodBasketDateUtils
import okhttp3.FormBody

/**
 * Builds exact ASP.NET WebForms UpdatePanel AJAX POST bodies for FoodBasketSale.aspx
 * matching captured browser field counts and DOM order (11-field reload, 12-field reload,
 * 12-field accept/sell, 12-field cancel).
 */
internal object FoodBasketFormBuilder {

    private const val SCRIPT_MANAGER_KEY = "ctl00\$ScriptManager"
    private const val UPDATE_PANEL_TARGET = "ctl00\$UpdatePanel1"
    private const val BTN_RELOAD_NAME = "ctl00\$cphMain\$btnFoodReload"
    private const val BTN_RELOAD_VALUE = "بارگذاری غذا"

    fun buildReloadBody(
        slot: BasketSlot,
        session: BasketPageSession
    ): FormBody {
        val normalizedDate = FoodBasketDateUtils.normalizeJalaliDate(slot.jalaliDate)
        val reservationId = session.currentReservationId?.takeIf { it.isNotBlank() }
        val includeHiddenId = session.hasResultPanel && reservationId != null

        return FormBody.Builder(Charsets.UTF_8).apply {
            add(SCRIPT_MANAGER_KEY, "$UPDATE_PANEL_TARGET|$BTN_RELOAD_NAME")
            if (includeHiddenId) {
                add("ctl00\$cphMain\$txtDate", normalizedDate)
                add("ctl00\$cphMain\$drpReserveType", slot.reserveType.formValue)
                add("ctl00\$cphMain\$drpFoodType", slot.mealType.formValue)
                add("ctl00\$cphMain\$hdnReservationId", reservationId!!)
                addWebFormsHiddenFields(session)
            } else {
                addWebFormsHiddenFields(session)
                add("ctl00\$cphMain\$txtDate", normalizedDate)
                add("ctl00\$cphMain\$drpReserveType", slot.reserveType.formValue)
                add("ctl00\$cphMain\$drpFoodType", slot.mealType.formValue)
            }
            add("__ASYNCPOST", "true")
            add(BTN_RELOAD_NAME, BTN_RELOAD_VALUE)
        }.build()
    }

    fun buildActionBody(
        action: BasketActionButton,
        slot: BasketSlot,
        reservationId: String,
        buttonValue: String,
        session: BasketPageSession
    ): FormBody {
        val normalizedDate = FoodBasketDateUtils.normalizeJalaliDate(slot.jalaliDate)
        val effectiveButtonValue = buttonValue.ifBlank { action.defaultButtonValue }

        return FormBody.Builder(Charsets.UTF_8).apply {
            add(SCRIPT_MANAGER_KEY, "$UPDATE_PANEL_TARGET|${action.controlName}")
            add("ctl00\$cphMain\$txtDate", normalizedDate)
            add("ctl00\$cphMain\$drpReserveType", slot.reserveType.formValue)
            add("ctl00\$cphMain\$drpFoodType", slot.mealType.formValue)
            add("ctl00\$cphMain\$hdnReservationId", reservationId)
            addWebFormsHiddenFields(session)
            add("__ASYNCPOST", "true")
            add(action.controlName, effectiveButtonValue)
        }.build()
    }

    private fun FormBody.Builder.addWebFormsHiddenFields(session: BasketPageSession) {
        add("__EVENTTARGET", "")
        add("__EVENTARGUMENT", "")
        add("__VIEWSTATE", session.webFormsState.viewState)
        add("__VIEWSTATEGENERATOR", session.webFormsState.viewStateGenerator.ifBlank { "92079A9C" })
        add("__VIEWSTATEENCRYPTED", "")
        val ev = session.webFormsState.eventValidation.orEmpty()
        if (ev.isNotBlank()) {
            add("__EVENTVALIDATION", ev)
        }
    }
}
