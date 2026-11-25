package com.example

import com.example.data.logger.AppLogger
import com.example.data.logger.LogSanitizer
import com.example.data.model.BasketActionButton
import com.example.data.model.BasketMealType
import com.example.data.model.BasketPageSession
import com.example.data.model.BasketReserveType
import com.example.data.model.BasketSlot
import com.example.data.model.BasketStatus
import com.example.data.model.BasketUncertainTimeoutException
import com.example.data.model.FoodBasketDateUtils
import com.example.data.model.WebFormsState
import com.example.data.network.CullinanHttpClient
import com.example.data.parser.DeltaParser
import com.example.data.parser.FoodBasketParser
import com.example.data.parser.PageClassifier
import com.example.data.repository.AuthRepository
import com.example.data.repository.FoodBasketFormBuilder
import com.example.data.repository.FoodBasketRepository
import com.example.data.repository.FoodBasketSessionExpiredException
import com.example.ui.viewmodel.FoodBasketViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLDecoder

@OptIn(ExperimentalCoroutinesApi::class)
class FoodBasketUnitTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AppLogger.clearLogs()
        mockWebServer = MockWebServer()
        mockWebServer.start()
        CullinanHttpClient.clearLocalSession()
        CullinanHttpClient.setCustomBaseUrl(mockWebServer.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        CullinanHttpClient.clearLocalSession()
        CullinanHttpClient.resetCustomBaseUrl()
        mockWebServer.shutdown()
        AppLogger.clearLogs()
        Dispatchers.resetMain()
    }

    private fun readFixture(name: String): String {
        val file = listOf(
            File("docs/05-samples-and-fixtures/$name"),
            File("../docs/05-samples-and-fixtures/$name")
        ).firstOrNull { it.exists() }
        requireNotNull(file) { "Fixture not found: $name" }
        return file.readText(Charsets.UTF_8)
    }

    private fun decodeFormPairs(body: String): List<Pair<String, String>> {
        if (body.isBlank()) return emptyList()
        return body.split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq == -1) null
            else {
                URLDecoder.decode(part.substring(0, eq), "UTF-8") to
                    URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }
        }
    }

    private fun formBodyToString(body: okhttp3.RequestBody): String {
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun seedSessionCookie() {
        val baseUrl = mockWebServer.url("/").toString().toHttpUrl()
        CullinanHttpClient.cookieJar.saveFromResponse(
            baseUrl,
            listOf(
                Cookie.Builder()
                    .name("ASP.NET_SessionId")
                    .value("FOOD_BASKET_TEST_SESSION")
                    .domain(baseUrl.host)
                    .path("/")
                    .build()
            )
        )
    }

    @Test
    fun parseInitialHtmlAndVerifyFields() {
        val html = readFixture("FoodBasketSale_page_sanitized.html")
        val classification = PageClassifier.classify(html, 200, "https://jeton.bums.ac.ir/FoodBasket/FoodBasketSale.aspx")
        assertEquals(PageClassifier.TYPE_FOOD_BASKET_SALE, classification.pageType)

        val session = FoodBasketParser.parseInitialPage(html)
        assertEquals("./FoodBasketSale.aspx", session.formAction)
        assertEquals("1405/09/03", session.defaultDateJalali)
        assertEquals(BasketReserveType.WEEKLY, session.selectedReserveType)
        assertEquals(BasketMealType.BREAKFAST, session.selectedMealType)
        assertEquals("92079A9C", session.webFormsState.viewStateGenerator)
        assertEquals(896, session.webFormsState.viewState.length)
        assertEquals("", session.webFormsState.eventValidation)
        assertFalse(session.hasResultPanel)
        assertNull(session.currentReservationId)
    }

    @Test
    fun parseAllFiveDeltasIncludingBidirectionalStaleLabelTrap() {
        val initialSession = FoodBasketParser.parseInitialPage(readFixture("FoodBasketSale_page_sanitized.html"))
        val slotToday = BasketSlot("1405/09/03", BasketMealType.LUNCH, BasketReserveType.WEEKLY)
        val slotTomorrow = BasketSlot("1405/09/04", BasketMealType.LUNCH, BasketReserveType.WEEKLY)

        // 1. Reload delta -> Reserved + btnAccept
        val reloadDelta = DeltaParser.parse(readFixture("FoodBasket_reload_delta_sanitized.txt"))
        val reloadParsed = FoodBasketParser.parseUpdatePanelDelta(reloadDelta, slotToday, initialSession)
        val reloadItem = reloadParsed.item
        assertNotNull(reloadItem)
        reloadItem!!
        assertEquals("1234567", reloadItem.reservationId)
        assertEquals(BasketStatus.RESERVED, reloadItem.status)
        assertEquals(BasketActionButton.ACCEPT, reloadItem.actionButton)
        assertEquals("نام غذای نمونه", reloadItem.foodName)
        assertEquals("سلف نمونه", reloadItem.selfName)
        assertEquals(1, reloadItem.count)
        assertEquals("180,000 ریال", reloadItem.reservePrice)
        assertEquals("180,000 ریال", reloadItem.salePrice)
        assertEquals(1176, reloadParsed.updatedSession.webFormsState.viewState.length)
        assertTrue(reloadParsed.updatedSession.hasResultPanel)

        // 2. Sell delta STALE LABEL TRAP -> lblStatus still says "این غذا رزرو شده است"
        //    but button flipped to btnCancel => parser MUST detect IN_BASKET!
        val sellDelta = DeltaParser.parse(readFixture("FoodBasket_sell_delta_sanitized.txt"))
        assertEquals("success", sellDelta.toastType)
        assertEquals("این غذا به سبد غذایی جهت فروش اضافه شد", sellDelta.toastMessage)
        val sellParsed = FoodBasketParser.parseUpdatePanelDelta(sellDelta, slotToday, reloadParsed.updatedSession)
        val sellItem = sellParsed.item!!
        assertEquals("این غذا رزرو شده است", sellItem.serverStatusLabel)
        assertEquals(BasketActionButton.CANCEL, sellItem.actionButton)
        assertEquals(BasketStatus.IN_BASKET, sellItem.status)

        // 3. Reload after sell -> fresh lblStatus + btnCancel
        val reloadAfterSellDelta = DeltaParser.parse(readFixture("FoodBasket_reload_after_sell_delta_sanitized.txt"))
        val afterSellParsed = FoodBasketParser.parseUpdatePanelDelta(reloadAfterSellDelta, slotToday, sellParsed.updatedSession)
        assertEquals(BasketStatus.IN_BASKET, afterSellParsed.item!!.status)
        assertEquals(BasketActionButton.CANCEL, afterSellParsed.item!!.actionButton)
        assertEquals("این غذا در سبد غذایی برای فروش قرار دارد", afterSellParsed.item!!.serverStatusLabel)
        assertEquals(1216, afterSellParsed.updatedSession.webFormsState.viewState.length)

        // 4. Cancel delta STALE LABEL TRAP -> lblStatus still says "این غذا در سبد غذایی برای فروش قرار دارد"
        //    but button flipped back to btnAccept => parser MUST detect RESERVED!
        val cancelDelta = DeltaParser.parse(readFixture("FoodBasket_cancel_delta_sanitized.txt"))
        assertEquals("success", cancelDelta.toastType)
        assertEquals("این غذا از سبد غذایی حذف شد", cancelDelta.toastMessage)
        val cancelParsed = FoodBasketParser.parseUpdatePanelDelta(cancelDelta, slotTomorrow, afterSellParsed.updatedSession)
        val cancelItem = cancelParsed.item!!
        assertEquals("این غذا در سبد غذایی برای فروش قرار دارد", cancelItem.serverStatusLabel)
        assertEquals(BasketActionButton.ACCEPT, cancelItem.actionButton)
        assertEquals(BasketStatus.RESERVED, cancelItem.status)
        assertEquals("1234567", cancelItem.reservationId)

        // 5. Reload after cancel -> fresh "این غذا رزرو شده است" + btnAccept
        val reloadAfterCancelDelta = DeltaParser.parse(readFixture("FoodBasket_reload_after_cancel_delta_sanitized.txt"))
        val afterCancelParsed = FoodBasketParser.parseUpdatePanelDelta(reloadAfterCancelDelta, slotTomorrow, cancelParsed.updatedSession)
        assertEquals(BasketStatus.RESERVED, afterCancelParsed.item!!.status)
        assertEquals(BasketActionButton.ACCEPT, afterCancelParsed.item!!.actionButton)
        assertEquals("این غذا رزرو شده است", afterCancelParsed.item!!.serverStatusLabel)
    }

    @Test
    fun buildPayloadsMatchCapturedFieldCountsOrdersAndCancel1755ByteLength() {
        val slot = BasketSlot("1405/09/04", BasketMealType.LUNCH, BasketReserveType.WEEKLY)
        val vs1240 = "A".repeat(1240)
        val vs896 = "B".repeat(896)

        // 1. Initial reload (11 fields, no result panel)
        val initialSession = BasketPageSession(
            webFormsState = WebFormsState(viewState = vs896, viewStateGenerator = "92079A9C", eventValidation = ""),
            hasResultPanel = false,
            currentReservationId = null
        )
        val reload11Pairs = decodeFormPairs(formBodyToString(FoodBasketFormBuilder.buildReloadBody(slot, initialSession)))
        assertEquals(11, reload11Pairs.size)
        assertEquals(
            listOf(
                "ctl00\$ScriptManager",
                "__EVENTTARGET",
                "__EVENTARGUMENT",
                "__VIEWSTATE",
                "__VIEWSTATEGENERATOR",
                "__VIEWSTATEENCRYPTED",
                "ctl00\$cphMain\$txtDate",
                "ctl00\$cphMain\$drpReserveType",
                "ctl00\$cphMain\$drpFoodType",
                "__ASYNCPOST",
                "ctl00\$cphMain\$btnFoodReload"
            ),
            reload11Pairs.map { it.first }
        )

        // 2. Subsequent reload (12 fields, DOM order with hdnReservationId)
        val sessionWithPanel = BasketPageSession(
            webFormsState = WebFormsState(viewState = vs1240, viewStateGenerator = "92079A9C", eventValidation = ""),
            hasResultPanel = true,
            currentReservationId = "1234567"
        )
        val reload12Pairs = decodeFormPairs(formBodyToString(FoodBasketFormBuilder.buildReloadBody(slot, sessionWithPanel)))
        assertEquals(12, reload12Pairs.size)
        assertEquals(
            listOf(
                "ctl00\$ScriptManager",
                "ctl00\$cphMain\$txtDate",
                "ctl00\$cphMain\$drpReserveType",
                "ctl00\$cphMain\$drpFoodType",
                "ctl00\$cphMain\$hdnReservationId",
                "__EVENTTARGET",
                "__EVENTARGUMENT",
                "__VIEWSTATE",
                "__VIEWSTATEGENERATOR",
                "__VIEWSTATEENCRYPTED",
                "__ASYNCPOST",
                "ctl00\$cphMain\$btnFoodReload"
            ),
            reload12Pairs.map { it.first }
        )

        // 3. Sell (btnAccept) 12 fields, only btnAccept submit button
        val sellRaw = formBodyToString(
            FoodBasketFormBuilder.buildActionBody(
                action = BasketActionButton.ACCEPT,
                slot = slot,
                reservationId = "1234567",
                buttonValue = "فروش به سبد غذا",
                session = sessionWithPanel
            )
        )
        val sellPairs = decodeFormPairs(sellRaw)
        assertEquals(12, sellPairs.size)
        assertEquals("ctl00\$UpdatePanel1|ctl00\$cphMain\$btnAccept", sellPairs.first { it.first == "ctl00\$ScriptManager" }.second)
        assertTrue(sellPairs.none { it.first.contains("btnFoodReload") || it.first.contains("btnCancel") })

        // 4. Cancel (btnCancel) exact 12 fields in DOM order matching captured browser submission
        val cancelRaw = formBodyToString(
            FoodBasketFormBuilder.buildActionBody(
                action = BasketActionButton.CANCEL,
                slot = slot,
                reservationId = "1234567",
                buttonValue = "حذف از سبد غذا",
                session = sessionWithPanel
            )
        )
        val cancelPairs = decodeFormPairs(cancelRaw)
        assertEquals(12, cancelPairs.size)
        assertEquals(
            listOf(
                "ctl00\$ScriptManager",
                "ctl00\$cphMain\$txtDate",
                "ctl00\$cphMain\$drpReserveType",
                "ctl00\$cphMain\$drpFoodType",
                "ctl00\$cphMain\$hdnReservationId",
                "__EVENTTARGET",
                "__EVENTARGUMENT",
                "__VIEWSTATE",
                "__VIEWSTATEGENERATOR",
                "__VIEWSTATEENCRYPTED",
                "__ASYNCPOST",
                "ctl00\$cphMain\$btnCancel"
            ),
            cancelPairs.map { it.first }
        )
        assertEquals("ctl00\$UpdatePanel1|ctl00\$cphMain\$btnCancel", cancelPairs.first { it.first == "ctl00\$ScriptManager" }.second)
        assertEquals("1234567", cancelPairs.first { it.first == "ctl00\$cphMain\$hdnReservationId" }.second)
        assertEquals("حذف از سبد غذا", cancelPairs.first { it.first == "ctl00\$cphMain\$btnCancel" }.second)
        assertTrue(cancelPairs.none { it.first.contains("btnFoodReload") || it.first.contains("btnAccept") })
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for condition after ${timeoutMs}ms")
            }
            kotlinx.coroutines.delay(15)
        }
    }

    @Test
    fun mockWebServerFullBidirectionalCycleAndLogoutFromFoodBasket() = runBlocking {
        seedSessionCookie()
        val htmlPage = readFixture("FoodBasketSale_page_sanitized.html")
        val reloadDelta = readFixture("FoodBasket_reload_delta_sanitized.txt")
        val sellDelta = readFixture("FoodBasket_sell_delta_sanitized.txt")
        val reloadAfterSellDelta = readFixture("FoodBasket_reload_after_sell_delta_sanitized.txt")
        val cancelDelta = readFixture("FoodBasket_cancel_delta_sanitized.txt")
        val reloadAfterCancelDelta = readFixture("FoodBasket_reload_after_cancel_delta_sanitized.txt")

        // Enqueue GET -> reload -> sell -> reload -> cancel -> reload -> logout(302)
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(htmlPage))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(reloadDelta))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sellDelta))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(reloadAfterSellDelta))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(cancelDelta))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(reloadAfterCancelDelta))
        mockWebServer.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/Login.aspx").setBody("<html>PII_SHOULD_DISCARD</html>"))

        val repo = FoodBasketRepository()
        val authRepo = AuthRepository()

        val initialSession = repo.openSalePage().getOrThrow()
        val slot = BasketSlot("1405/09/03", BasketMealType.LUNCH, BasketReserveType.WEEKLY)

        val r1 = repo.reloadSlot(slot, initialSession).getOrThrow()
        assertEquals(BasketStatus.RESERVED, r1.item!!.status)

        val sellOutcome = repo.executeSaleOrCancel(BasketActionButton.ACCEPT, r1.item!!, r1.updatedSession).getOrThrow()
        assertTrue(sellOutcome.isSuccess)
        assertEquals(BasketStatus.IN_BASKET, sellOutcome.updatedItem!!.status)

        val r2 = repo.reloadSlot(slot, sellOutcome.updatedSession).getOrThrow()
        assertEquals(BasketStatus.IN_BASKET, r2.item!!.status)
        assertEquals("این غذا در سبد غذایی برای فروش قرار دارد", r2.item!!.serverStatusLabel)

        val cancelOutcome = repo.executeSaleOrCancel(BasketActionButton.CANCEL, r2.item!!, r2.updatedSession).getOrThrow()
        assertTrue(cancelOutcome.isSuccess)
        assertEquals(BasketStatus.RESERVED, cancelOutcome.updatedItem!!.status)

        val r3 = repo.reloadSlot(slot, cancelOutcome.updatedSession).getOrThrow()
        assertEquals(BasketStatus.RESERVED, r3.item!!.status)
        assertEquals("این غذا رزرو شده است", r3.item!!.serverStatusLabel)

        // Universal logout straight from FoodBasketSale.aspx tracked state
        val logoutOutcome = authRepo.logoutDetailed(null)
        assertTrue(logoutOutcome.serverConfirmed)
        assertEquals("/FoodBasket/FoodBasketSale.aspx", logoutOutcome.sourcePagePath)

        // Inspect recorded requests
        val getReq = mockWebServer.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("/FoodBasket/FoodBasketSale.aspx", getReq.path)

        val reload1Req = mockWebServer.takeRequest()
        assertEquals("Delta=true", reload1Req.getHeader("X-MicrosoftAjax"))
        assertEquals("XMLHttpRequest", reload1Req.getHeader("X-Requested-With"))
        assertEquals(11, decodeFormPairs(reload1Req.body.readUtf8()).size)

        val sellReq = mockWebServer.takeRequest()
        assertEquals(12, decodeFormPairs(sellReq.body.readUtf8()).size)

        val reload2Req = mockWebServer.takeRequest()
        assertEquals(12, decodeFormPairs(reload2Req.body.readUtf8()).size)

        val cancelReq = mockWebServer.takeRequest()
        assertEquals(12, decodeFormPairs(cancelReq.body.readUtf8()).size)

        val reload3Req = mockWebServer.takeRequest()
        assertEquals(12, decodeFormPairs(reload3Req.body.readUtf8()).size)

        val logoutReq = mockWebServer.takeRequest()
        assertEquals("/FoodBasket/FoodBasketSale.aspx", logoutReq.path)
        assertNull(logoutReq.getHeader("X-MicrosoftAjax"))
        val logoutPairs = decodeFormPairs(logoutReq.body.readUtf8())
        assertEquals(9, logoutPairs.size)
        assertEquals("ctl00\$logout", logoutPairs.first { it.first == "__EVENTTARGET" }.second)
        assertEquals("1234567", logoutPairs.first { it.first == "ctl00\$cphMain\$hdnReservationId" }.second)
        assertTrue(logoutPairs.none { it.first.contains("btn") })

        // Privacy assertions on logs and snapshots
        val allLogStrings = AppLogger.recentEntries.value.joinToString("\n") { it.message + " " + (it.http?.url ?: "") }
        assertFalse("hdnReservationId value must never appear in logs", allLogStrings.contains("1234567"))
        assertFalse("Food name must never appear in logs", allLogStrings.contains("نام غذای نمونه"))
        assertFalse("Self name must never appear in logs", allLogStrings.contains("سلف نمونه"))
        assertFalse("Prices must never appear in logs", allLogStrings.contains("180,000"))
        assertTrue(
            "No FoodBasketSale disk snapshots should be saved",
            AppLogger.pageSnapshots.value.none { it.url.contains("FoodBasketSale", ignoreCase = true) }
        )
    }

    @Test
    fun missingFieldsetAndErrorToastHandledCleanly() = runBlocking {
        seedSessionCookie()
        val htmlPage = readFixture("FoodBasketSale_page_sanitized.html")
        val noResultDelta = "1|#||4|120|updatePanel|UpdatePanel1|<div id=\"cphMain_alertBox\" class=\"alertBox\"><label id=\"cphMain_alertBoxValue\">رزرو یافت نشد</label></div>|8|hiddenField|__VIEWSTATE|NEW_VS_01|8|hiddenField|__VIEWSTATEGENERATOR|92079A9C|"
        val errorToastDelta = readFixture("FoodBasket_reload_delta_sanitized.txt")
        val sellErrorDelta = "1|#||4|50|updatePanel|UpdatePanel1|<div>no change</div>|8|hiddenField|__VIEWSTATE|NEW_VS_02|91|scriptStartupBlock|ScriptContentNoTags|toastify('error', 'مهلت فروش به پایان رسیده است', 'خطا');|"

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(htmlPage))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(noResultDelta))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(errorToastDelta))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(sellErrorDelta))

        val repo = FoodBasketRepository()
        val session = repo.openSalePage().getOrThrow()
        val slot = BasketSlot("1405/09/03", BasketMealType.BREAKFAST, BasketReserveType.WEEKLY)

        val emptyRes = repo.reloadSlot(slot, session).getOrThrow()
        assertNull(emptyRes.item)
        assertFalse(emptyRes.updatedSession.hasResultPanel)
        assertEquals("رزرو یافت نشد", emptyRes.alertMessage)

        val loadedRes = repo.reloadSlot(slot.copy(mealType = BasketMealType.LUNCH), emptyRes.updatedSession).getOrThrow()
        assertNotNull(loadedRes.item)

        val failedSell = repo.executeSaleOrCancel(BasketActionButton.ACCEPT, loadedRes.item!!, loadedRes.updatedSession).getOrThrow()
        assertFalse(failedSell.isSuccess)
        assertEquals("error", failedSell.toastType)
        assertEquals("مهلت فروش به پایان رسیده است", failedSell.toastMessage)
    }

    @Test
    fun networkDisconnectMidSellTriggersTimeoutRecoveryReloadWithoutRetry() = runBlocking {
        seedSessionCookie()
        val htmlPage = readFixture("FoodBasketSale_page_sanitized.html")
        val reloadDelta = readFixture("FoodBasket_reload_delta_sanitized.txt")
        val reloadAfterSellDelta = readFixture("FoodBasket_reload_after_sell_delta_sanitized.txt")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(htmlPage))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(reloadDelta))
        // Mid-sell network disconnect -> NO automatic retry of btnAccept!
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // Recovery reload automatically inspects true server state (server actually committed the sale)
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(reloadAfterSellDelta))

        val vm = FoodBasketViewModel(FoodBasketRepository())
        vm.refreshPage()
        awaitCondition { vm.uiState.value.pageSession != null && !vm.uiState.value.isBusy }

        vm.onJalaliDateChanged("1405/09/03")
        vm.onMealTypeSelected(BasketMealType.LUNCH)
        vm.loadCurrentSlot()
        awaitCondition { vm.uiState.value.currentItem != null && !vm.uiState.value.isBusy }

        assertEquals(BasketStatus.RESERVED, vm.uiState.value.currentItem!!.status)

        vm.requestActionConfirmation(BasketActionButton.ACCEPT)
        vm.confirmPendingAction()
        awaitCondition { vm.uiState.value.currentItem?.status == BasketStatus.IN_BASKET && !vm.uiState.value.isBusy }

        // Confirm state recovered to IN_BASKET via reload without sending a 2nd btnAccept
        assertEquals(BasketStatus.IN_BASKET, vm.uiState.value.currentItem!!.status)
        assertEquals("این غذا در سبد غذایی برای فروش قرار دارد", vm.uiState.value.currentItem!!.serverStatusLabel)
        assertEquals(4, mockWebServer.requestCount)

        // Verify the 4th request was btnFoodReload, NOT a retry of btnAccept
        mockWebServer.takeRequest() // GET
        mockWebServer.takeRequest() // reload 1
        val sellReq = mockWebServer.takeRequest()
        assertTrue(decodeFormPairs(sellReq.body.readUtf8()).any { it.first == "ctl00\$cphMain\$btnAccept" })
        val recoveryReloadReq = mockWebServer.takeRequest()
        val recoveryPairs = decodeFormPairs(recoveryReloadReq.body.readUtf8())
        assertTrue(recoveryPairs.any { it.first == "ctl00\$cphMain\$btnFoodReload" })
        assertTrue(recoveryPairs.none { it.first == "ctl00\$cphMain\$btnAccept" })
    }

    @Test
    fun sessionExpiryRedirectThrowsSessionExpiredAndClearsTracker() = runBlocking {
        seedSessionCookie()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><body><input name=\"txtUsernamePlain\" /><input name=\"btnLogin\" /></body></html>")
        )

        val repo = FoodBasketRepository()
        val res = repo.openSalePage()
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is FoodBasketSessionExpiredException)
    }

    @Test
    fun sanitizerMasksAllHdnReservationIdFormsAndDateValidatorWorks() {
        val raw = "ctl00\$cphMain\$hdnReservationId=9876543&ctl00%24cphMain%24hdnReservationId%3D5551112&hdnReservationId=222333&json={\"hdnReservationId\":\"444555\"}"
        val sanitized = LogSanitizer.sanitizeText(raw)
        assertFalse(sanitized.contains("9876543"))
        assertFalse(sanitized.contains("5551112"))
        assertFalse(sanitized.contains("222333"))
        assertFalse(sanitized.contains("444555"))
        assertTrue(sanitized.contains("[REDACTED_RESERVATION_ID]"))

        assertTrue(FoodBasketDateUtils.isValidJalaliDate("۱۴۰۵/۰۹/۰۳"))
        assertEquals("1405/09/03", FoodBasketDateUtils.normalizeJalaliDate("۱۴۰۵/۹/۳"))
        assertFalse(FoodBasketDateUtils.isValidJalaliDate("99/13/45"))
    }
}
