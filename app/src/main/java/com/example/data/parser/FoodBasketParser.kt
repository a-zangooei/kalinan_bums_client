package com.example.data.parser

import com.example.data.model.BasketActionButton
import com.example.data.model.BasketItemState
import com.example.data.model.BasketMealType
import com.example.data.model.BasketPageSession
import com.example.data.model.BasketReloadResult
import com.example.data.model.BasketReserveType
import com.example.data.model.BasketSlot
import com.example.data.model.BasketStatus
import com.example.data.model.FoodBasketDateUtils
import com.example.data.model.WebFormsState
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parser for FoodBasketSale.aspx initial HTML and UpdatePanel1 Delta fragments.
 *
 * CRITICAL PROTOCOL RULE:
 * Server Delta responses for btnAccept and btnCancel intentionally/buggily leave
 * #cphMain_lblStatus stale. Status MUST ALWAYS be inferred from the active submit button
 * (btnAccept -> RESERVED, btnCancel -> IN_BASKET), never from lblStatus text.
 */
object FoodBasketParser {

    fun parseInitialPage(html: String): BasketPageSession {
        val doc = Jsoup.parse(html)
        val formAction = doc.selectFirst("form#MainForm")
            ?.attr("action")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "./FoodBasketSale.aspx"

        val viewState = doc.selectFirst("input#__VIEWSTATE, input[name=__VIEWSTATE]")?.`val`().orEmpty()
        val viewStateGenerator = doc.selectFirst("input#__VIEWSTATEGENERATOR, input[name=__VIEWSTATEGENERATOR]")
            ?.`val`()
            ?.takeIf { it.isNotBlank() }
            ?: "92079A9C"
        val eventValidation = doc.selectFirst("input#__EVENTVALIDATION, input[name=__EVENTVALIDATION]")?.`val`().orEmpty()

        val defaultDate = doc.selectFirst("#cphMain_txtDate, input[name='ctl00\$cphMain\$txtDate']")
            ?.`val`()
            ?.let { FoodBasketDateUtils.normalizeJalaliDate(it) }
            .orEmpty()

        val reserveValue = doc.selectFirst("#cphMain_drpReserveType option[selected]")?.attr("value")
            ?: doc.selectFirst("#cphMain_drpReserveType option")?.attr("value")
        val mealValue = doc.selectFirst("#cphMain_drpFoodType option[selected]")?.attr("value")
            ?: doc.selectFirst("#cphMain_drpFoodType option")?.attr("value")

        val resultFieldset = doc.selectFirst("#cphMain_fldsetResult")
        val reservationId = resultFieldset
            ?.selectFirst("#cphMain_hdnReservationId, input[name='ctl00\$cphMain\$hdnReservationId']")
            ?.`val`()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val alertMessage = extractAlertMessage(doc)

        return BasketPageSession(
            formAction = formAction,
            defaultDateJalali = defaultDate,
            selectedReserveType = BasketReserveType.fromFormValue(reserveValue),
            selectedMealType = BasketMealType.fromFormValue(mealValue),
            webFormsState = WebFormsState(
                viewState = viewState,
                viewStateGenerator = viewStateGenerator,
                eventValidation = eventValidation
            ),
            hasResultPanel = resultFieldset != null,
            currentReservationId = reservationId,
            alertMessage = alertMessage
        )
    }

    fun parseUpdatePanelDelta(
        deltaResponse: DeltaResponse,
        requestedSlot: BasketSlot,
        previousSession: BasketPageSession
    ): BasketReloadResult {
        val updatePanelHtml = extractUpdatePanelHtml(deltaResponse)
        val updatedWebFormsState = DeltaParser.extractWebFormsState(deltaResponse, previousSession.webFormsState)

        if (updatePanelHtml.isNullOrBlank()) {
            val updatedSession = previousSession.copy(
                webFormsState = updatedWebFormsState,
                formAction = deltaResponse.formAction?.takeIf { it.isNotBlank() } ?: previousSession.formAction
            )
            return BasketReloadResult(
                slot = requestedSlot,
                item = null,
                updatedSession = updatedSession,
                alertMessage = null,
                toastType = deltaResponse.toastType,
                toastMessage = deltaResponse.toastMessage
            )
        }

        val fragmentDoc = Jsoup.parseBodyFragment(updatePanelHtml)
        val alertMessage = extractAlertMessage(fragmentDoc)

        val responseDate = fragmentDoc.selectFirst("#cphMain_txtDate, input[name='ctl00\$cphMain\$txtDate']")
            ?.`val`()
            ?.let { FoodBasketDateUtils.normalizeJalaliDate(it) }
            ?.takeIf { it.isNotBlank() }
            ?: requestedSlot.jalaliDate

        val reserveValue = fragmentDoc.selectFirst("#cphMain_drpReserveType option[selected]")?.attr("value")
        val mealValue = fragmentDoc.selectFirst("#cphMain_drpFoodType option[selected]")?.attr("value")

        val resolvedSlot = BasketSlot(
            jalaliDate = responseDate,
            mealType = if (mealValue != null) BasketMealType.fromFormValue(mealValue) else requestedSlot.mealType,
            reserveType = if (reserveValue != null) BasketReserveType.fromFormValue(reserveValue) else requestedSlot.reserveType
        )

        val resultFieldset = fragmentDoc.selectFirst("#cphMain_fldsetResult")
        val item = resultFieldset?.let { parseResultFieldset(it, resolvedSlot) }

        val updatedSession = previousSession.copy(
            formAction = deltaResponse.formAction?.takeIf { it.isNotBlank() } ?: previousSession.formAction,
            defaultDateJalali = previousSession.defaultDateJalali.ifBlank { responseDate },
            selectedReserveType = resolvedSlot.reserveType,
            selectedMealType = resolvedSlot.mealType,
            webFormsState = updatedWebFormsState,
            hasResultPanel = resultFieldset != null,
            currentReservationId = item?.reservationId,
            alertMessage = alertMessage
        )

        return BasketReloadResult(
            slot = resolvedSlot,
            item = item,
            updatedSession = updatedSession,
            alertMessage = alertMessage,
            toastType = deltaResponse.toastType,
            toastMessage = deltaResponse.toastMessage
        )
    }

    internal fun parseResultFieldset(
        fieldset: Element,
        slot: BasketSlot
    ): BasketItemState? {
        val reservationId = fieldset.selectFirst(
            "#cphMain_hdnReservationId, input[name='ctl00\$cphMain\$hdnReservationId']"
        )?.`val`()?.trim().orEmpty()

        val acceptBtn = fieldset.selectFirst(
            "#cphMain_btnAccept, input[name='ctl00\$cphMain\$btnAccept']"
        )
        val cancelBtn = fieldset.selectFirst(
            "#cphMain_btnCancel, input[name='ctl00\$cphMain\$btnCancel']"
        )

        // Status MUST be derived from active button presence, NEVER from #cphMain_lblStatus text
        val (actionButton, status, buttonLabel) = when {
            cancelBtn != null -> Triple(
                BasketActionButton.CANCEL,
                BasketStatus.IN_BASKET,
                cancelBtn.`val`().trim().ifBlank { BasketActionButton.CANCEL.defaultButtonValue }
            )
            acceptBtn != null -> Triple(
                BasketActionButton.ACCEPT,
                BasketStatus.RESERVED,
                acceptBtn.`val`().trim().ifBlank { BasketActionButton.ACCEPT.defaultButtonValue }
            )
            else -> return null
        }

        if (reservationId.isBlank()) return null

        val serverStatusLabel = fieldset.selectFirst("#cphMain_lblStatus")?.text()?.trim().orEmpty()
        val foodName = fieldset.selectFirst("#cphMain_lblFood")?.text()?.trim().orEmpty()
        val selfName = fieldset.selectFirst("#cphMain_lblSelf")?.text()?.trim().orEmpty()
        val count = fieldset.selectFirst("#cphMain_lblFoodcount")?.text()?.trim()?.toIntOrNull() ?: 1
        val reservePrice = fieldset.selectFirst("#cphMain_lblReservePrice")?.text()?.trim().orEmpty()
        val salePrice = fieldset.selectFirst("#cphMain_lblBasketSalePrice")?.text()?.trim().orEmpty()

        return BasketItemState(
            slot = slot,
            reservationId = reservationId,
            status = status,
            actionButton = actionButton,
            buttonLabel = buttonLabel,
            serverStatusLabel = serverStatusLabel,
            foodName = foodName,
            selfName = selfName,
            count = count,
            reservePrice = reservePrice,
            salePrice = salePrice
        )
    }

    private fun extractUpdatePanelHtml(deltaResponse: DeltaResponse): String? {
        return deltaResponse.updatePanels["UpdatePanel1"]
            ?: deltaResponse.updatePanels["ctl00\$UpdatePanel1"]
            ?: deltaResponse.updatePanels.values.firstOrNull {
                it.contains("cphMain_txtDate") ||
                    it.contains("cphMain_btnFoodReload") ||
                    it.contains("cphMain_fldsetResult")
            }
    }

    private fun extractAlertMessage(doc: Document): String? {
        val alertBox = doc.selectFirst("#cphMain_alertBox") ?: return null
        val styleAttr = alertBox.attr("style").replace(" ", "").lowercase()
        val alertText = doc.selectFirst("#cphMain_alertBoxValue")?.text()?.trim().orEmpty()
        if (alertText.isBlank()) return null
        if (styleAttr.contains("display:none") && alertText.isBlank()) return null
        // Even if inline style is display:none, return non-blank alert text only when actually populated
        return if (styleAttr.contains("display:none")) null else alertText
    }
}
