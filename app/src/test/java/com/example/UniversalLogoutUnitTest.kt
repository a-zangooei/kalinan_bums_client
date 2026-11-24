package com.example

import com.example.data.logger.AppLogger
import com.example.data.network.CullinanHttpClient
import com.example.data.network.MasterPageSource
import com.example.data.network.MasterPageTracker
import com.example.data.repository.AuthRepository
import com.example.data.repository.LogoutFormParser
import com.example.data.repository.LogoutStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class UniversalLogoutUnitTest {

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

    private fun seedSessionCookie(sessionId: String = "TEST_SESSION_ACTIVE_999") {
        val baseUrl = mockWebServer.url("/").toString().toHttpUrl()
        CullinanHttpClient.cookieJar.saveFromResponse(
            baseUrl,
            listOf(
                Cookie.Builder()
                    .name("ASP.NET_SessionId")
                    .value(sessionId)
                    .domain(baseUrl.host)
                    .path("/")
                    .build(),
                Cookie.Builder()
                    .name("keyId")
                    .value("SECRET_KEY_ID_VALUE")
                    .domain(baseUrl.host)
                    .path("/")
                    .build(),
                Cookie.Builder()
                    .name("__AntiXsrfToken")
                    .value("SECRET_XSRF_TOKEN_VALUE")
                    .domain(baseUrl.host)
                    .path("/")
                    .build()
            )
        )
    }

    @Test
    fun testLogoutFormParserAcrossSanitizedFixtures() {
        // 1. Default.aspx
        val defaultHtml = readFixture("Logout_Default_page_sanitized.html")
        val defaultPayload = LogoutFormParser.buildLogoutPayload(
            pageUrl = "https://jeton.bums.ac.ir/Master/Default.aspx",
            html = defaultHtml
        )
        assertNotNull(defaultPayload)
        defaultPayload!!
        assertEquals("https://jeton.bums.ac.ir/Master/Default.aspx", defaultPayload.postUrl)
        assertEquals("79A64AFF", defaultPayload.viewStateGenerator)
        assertEquals("ctl00\$logout", defaultPayload.eventTarget)
        assertEquals("", defaultPayload.eventArgument)
        assertFalse(defaultPayload.usedDefaultTarget)
        assertEquals(
            listOf("__EVENTTARGET", "__EVENTARGUMENT", "__VIEWSTATE", "__VIEWSTATEGENERATOR", "__VIEWSTATEENCRYPTED"),
            defaultPayload.formKeys
        )

        // 2. MyProfile.aspx
        val profileHtml = readFixture("MyProfile_page_sanitized.html")
        val profilePayload = LogoutFormParser.buildLogoutPayload(
            pageUrl = "https://jeton.bums.ac.ir/MyCullinan/MyProfile.aspx",
            html = profileHtml
        )
        assertNotNull(profilePayload)
        profilePayload!!
        assertEquals("https://jeton.bums.ac.ir/MyCullinan/MyProfile.aspx", profilePayload.postUrl)
        assertEquals("B57DACA3", profilePayload.viewStateGenerator)
        assertEquals("ctl00\$logout", profilePayload.eventTarget)
        assertEquals(
            listOf("__EVENTTARGET", "__EVENTARGUMENT", "__VIEWSTATE", "__VIEWSTATEGENERATOR", "__VIEWSTATEENCRYPTED"),
            profilePayload.formKeys
        )

        // 3. Survey_Answer.aspx
        val surveyHtml = readFixture("Survey_page_sanitized.html")
        val surveyPayload = LogoutFormParser.buildLogoutPayload(
            pageUrl = "https://jeton.bums.ac.ir/Survey/Survey_Answer.aspx",
            html = surveyHtml
        )
        assertNotNull(surveyPayload)
        surveyPayload!!
        assertEquals("https://jeton.bums.ac.ir/Survey/Survey_Answer.aspx", surveyPayload.postUrl)
        assertEquals("4D6A1972", surveyPayload.viewStateGenerator)
        assertEquals("ctl00\$logout", surveyPayload.eventTarget)
    }

    @Test
    fun testLogoutFormParserExcludesImageSubmitDisabledAndSupportsFuturePages() {
        val futurePageHtml = """
            <!DOCTYPE html>
            <html lang="fa-ir">
            <body>
                <form method="post" action="./CreditTransfer.aspx" id="MainForm">
                    <input type="hidden" name="__EVENTTARGET" value="" />
                    <input type="hidden" name="__EVENTARGUMENT" value="" />
                    <input type="hidden" name="__VIEWSTATE" value="FUTURE_VS_TOKEN" />
                    <input type="hidden" name="__VIEWSTATEGENERATOR" value="AABBCC11" />
                    <input type="hidden" name="__VIEWSTATEENCRYPTED" value="" />
                    <input type="hidden" name="__EVENTVALIDATION" value="EV_FUTURE_99" />
                    <input type="hidden" name="ctl00${'$'}cphMain${'$'}hdnId" value="" />
                    <input type="checkbox" name="ctl00${'$'}chkAgree" value="on" checked="checked" />
                    <input type="checkbox" name="ctl00${'$'}chkSkip" value="on" />
                    <select name="ctl00${'$'}drpTarget">
                        <option value="1">Option 1</option>
                        <option value="2" selected="selected">Option 2</option>
                    </select>
                    <input type="image" name="ctl00${'$'}cphMain${'$'}imgbtnMessageText" src="icon.png" />
                    <input type="submit" name="ctl00${'$'}btnSave" value="Save" />
                    <input type="text" name="ctl00${'$'}disabledField" value="ignored" disabled="disabled" />
                    <a id="logout" href="javascript:__doPostBack('ctl00${'$'}customLogoutBtn','exit')">خروج</a>
                </form>
            </body>
            </html>
        """.trimIndent()

        val built = LogoutFormParser.buildLogoutPayload(
            pageUrl = "https://jeton.bums.ac.ir/Facility/CreditTransfer.aspx",
            html = futurePageHtml
        )
        assertNotNull(built)
        built!!

        assertEquals("https://jeton.bums.ac.ir/Facility/CreditTransfer.aspx", built.postUrl)
        assertEquals("ctl00\$customLogoutBtn", built.eventTarget)
        assertEquals("exit", built.eventArgument)
        assertEquals("AABBCC11", built.viewStateGenerator)
        assertTrue(built.hasEventValidation)
        assertEquals(3, built.excludedControlsCount)
        assertEquals(2, built.excludedImageOrButtonCount)

        val buf = Buffer()
        built.formBody.writeTo(buf)
        val pairs = decodeFormPairs(buf.readUtf8())
        val map = pairs.toMap()

        assertEquals("ctl00\$customLogoutBtn", map["__EVENTTARGET"])
        assertEquals("exit", map["__EVENTARGUMENT"])
        assertEquals("FUTURE_VS_TOKEN", map["__VIEWSTATE"])
        assertEquals("EV_FUTURE_99", map["__EVENTVALIDATION"])
        assertEquals("", map["ctl00\$cphMain\$hdnId"])
        assertEquals("on", map["ctl00\$chkAgree"])
        assertFalse(map.containsKey("ctl00\$chkSkip"))
        assertEquals("2", map["ctl00\$drpTarget"])
        assertFalse(map.containsKey("ctl00\$cphMain\$imgbtnMessageText"))
        assertFalse(map.containsKey("ctl00\$btnSave"))
        assertFalse(map.containsKey("ctl00\$disabledField"))
        // __EVENTTARGET must never be duplicated
        assertEquals(1, pairs.count { it.first == "__EVENTTARGET" })
    }

    @Test
    fun testLogoutFormParserEnforcesSameOriginForAction() {
        val maliciousHtml = """
            <html><body>
            <form method="post" action="https://attacker.example/steal" id="MainForm">
                <input type="hidden" name="__VIEWSTATE" value="VS123" />
                <a id="logout" href="javascript:__doPostBack('ctl00${'$'}logout','')">Exit</a>
            </form>
            </body></html>
        """.trimIndent()

        val built = LogoutFormParser.buildLogoutPayload(
            pageUrl = "https://jeton.bums.ac.ir/Master/Default.aspx",
            html = maliciousHtml
        )
        assertNotNull(built)
        assertEquals("https://jeton.bums.ac.ir/Master/Default.aspx", built!!.postUrl)
    }

    @Test
    fun testMasterPageTrackerUpdatesOnDeltaAndLogoutUsesDeltaPatchedStrategy() = runBlocking {
        seedSessionCookie()
        val tracker = CullinanHttpClient.masterPageTracker
        val reservationUrl = CullinanHttpClient.RESERVATION_URL

        val initialReservationHtml = """
            <!DOCTYPE html><html><body>
            <form method="post" action="./Reservation.aspx" id="MainForm">
                <input type="hidden" name="__EVENTTARGET" value="" />
                <input type="hidden" name="__EVENTARGUMENT" value="" />
                <input type="hidden" name="__VIEWSTATE" value="OLD_INITIAL_VS" />
                <input type="hidden" name="__VIEWSTATEGENERATOR" value="3ED98A75" />
                <input type="hidden" name="__VIEWSTATEENCRYPTED" value="" />
                <div id="UpdatePanel1">
                    <input type="hidden" name="ctl00${'$'}cphMain${'$'}hdnSelectedTab" value="css3-tabstrip-0-1" />
                </div>
                <a id="logout" href="javascript:__doPostBack('ctl00${'$'}logout','')">Logout</a>
            </form>
            </body></html>
        """.trimIndent()

        // 1. Simulate GET Reservation.aspx
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(initialReservationHtml)
        )
        CullinanHttpClient.executeCall(
            Request.Builder().url(reservationUrl).get().build()
        ).close()

        assertEquals(MasterPageSource.FULL_HTML, tracker.currentState()?.source)

        // 2. Simulate AJAX UpdatePanel Delta response with updated __VIEWSTATE and updated panel control
        val updatedPanelHtml = """<input type="hidden" name="ctl00${'$'}cphMain${'$'}hdnSelectedTab" value="css3-tabstrip-0-2" /><input type="checkbox" name="ctl00${'$'}cphMain${'$'}chkReserve" value="on" checked="checked" />"""
        val deltaBody = buildString {
            append("${updatedPanelHtml.length}|updatePanel|UpdatePanel1|$updatedPanelHtml|")
            append("${"NEW_DELTA_VS_999".length}|hiddenField|__VIEWSTATE|NEW_DELTA_VS_999|")
            append("${"3ED98A75".length}|hiddenField|__VIEWSTATEGENERATOR|3ED98A75|")
        }
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody(deltaBody)
        )
        CullinanHttpClient.executeCall(
            Request.Builder()
                .url(reservationUrl)
                .post(okhttp3.FormBody.Builder().add("__EVENTTARGET", "ctl00\$cphMain\$lnkNextWeek").build())
                .header("X-MicrosoftAjax", "Delta=true")
                .build()
        ).close()

        assertEquals(MasterPageSource.DELTA_PATCHED, tracker.currentState()?.source)

        // 3. Logout must POST directly to Reservation.aspx with patched __VIEWSTATE and discard 302 PII body
        val sensitive302Html = "<html><body>Object moved. STUDENT_PII_DO_NOT_LEAK_XYZ_777</body></html>"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/Login.aspx")
                .setHeader("Set-Cookie", "ASP.NET_SessionId=; path=/; HttpOnly")
                .setBody(sensitive302Html)
        )

        val authRepo = AuthRepository()
        val outcome = authRepo.logoutDetailed(null)

        assertTrue(outcome.serverConfirmed)
        assertFalse(outcome.alreadyLoggedOut)
        assertEquals(LogoutStrategy.DELTA_PATCHED, outcome.strategy)
        assertEquals("/Reservation/Reservation.aspx", outcome.sourcePagePath)
        assertEquals(302, outcome.httpStatus)
        assertNull("Tracked state must be cleared after logout", tracker.currentState())

        // Verify the 3 HTTP requests recorded by MockWebServer
        mockWebServer.takeRequest() // GET Reservation
        mockWebServer.takeRequest() // POST Delta
        val logoutReq = mockWebServer.takeRequest()
        assertEquals("POST", logoutReq.method)
        assertEquals("/Reservation/Reservation.aspx", logoutReq.path)
        val postedMap = decodeFormPairs(logoutReq.body.readUtf8()).toMap()
        assertEquals("ctl00\$logout", postedMap["__EVENTTARGET"])
        assertEquals("NEW_DELTA_VS_999", postedMap["__VIEWSTATE"])
        assertEquals("css3-tabstrip-0-2", postedMap["ctl00\$cphMain\$hdnSelectedTab"])
        assertEquals("on", postedMap["ctl00\$cphMain\$chkReserve"])

        // Verify privacy: 302 body MUST NOT appear in snapshots or logs, and raw cookies/ViewState must NOT leak
        val allSnapshotsText = AppLogger.pageSnapshots.value.joinToString("\n") { it.operation + ":" + it.rawContent }
        assertFalse("302 logout body must never be snapshotted", allSnapshotsText.contains("STUDENT_PII_DO_NOT_LEAK_XYZ_777"))
        assertFalse("LogoutPost must never create snapshot", AppLogger.pageSnapshots.value.any { it.operation.startsWith("Logout") })

        val allLogs = AppLogger.getExportableText() + "\n" + AppLogger.getExportableJsonl()
        assertFalse("302 PII body must never appear in logs", allLogs.contains("STUDENT_PII_DO_NOT_LEAK_XYZ_777"))
        assertFalse("Raw session cookie must never appear in logs", allLogs.contains("TEST_SESSION_ACTIVE_999"))
        assertFalse("Raw keyId cookie must never appear in logs", allLogs.contains("SECRET_KEY_ID_VALUE"))
        assertFalse("Raw ViewState must never appear in logs", allLogs.contains("NEW_DELTA_VS_999"))

        assertTrue(allLogs.contains("LogoutInitiated"))
        assertTrue(allLogs.contains("LogoutFormBuilt"))
        assertTrue(allLogs.contains("RedirectBodyDiscarded"))
        assertTrue(allLogs.contains("ServerLogoutSuccess"))
    }

    @Test
    fun testLogoutFromSensitiveMyProfileUsesMemoryOnlyAndSavesNoSnapshots() = runBlocking {
        seedSessionCookie("PROFILE_SESS_555")
        val profileHtml = readFixture("MyProfile_page_sanitized.html")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(profileHtml)
        )
        CullinanHttpClient.executeCallNoRedirect(
            Request.Builder().url(CullinanHttpClient.PROFILE_URL).get().build()
        ).close()

        assertNotNull(
            "MyProfile.aspx must be tracked in memory for direct logout",
            CullinanHttpClient.masterPageTracker.currentState()
        )
        assertTrue(
            "MyProfile.aspx must NEVER be saved to disk pageSnapshots",
            AppLogger.pageSnapshots.value.isEmpty()
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/Login.aspx")
                .setHeader("Set-Cookie", "ASP.NET_SessionId=; path=/")
                .setBody("Object moved PROFILE_SECRET_NAME_NEVER_LOG")
        )

        val outcome = AuthRepository().logoutDetailed(null)
        assertTrue(outcome.serverConfirmed)
        assertEquals(LogoutStrategy.DIRECT_PAGE_STATE, outcome.strategy)
        assertEquals("/MyCullinan/MyProfile.aspx", outcome.sourcePagePath)

        mockWebServer.takeRequest() // GET MyProfile
        val postReq = mockWebServer.takeRequest()
        assertEquals("/MyCullinan/MyProfile.aspx", postReq.path)
        val postedMap = decodeFormPairs(postReq.body.readUtf8()).toMap()
        assertEquals("B57DACA3", postedMap["__VIEWSTATEGENERATOR"])
        assertEquals("ctl00\$logout", postedMap["__EVENTTARGET"])

        val allLogs = AppLogger.getExportableText()
        assertFalse(allLogs.contains("PROFILE_SECRET_NAME_NEVER_LOG"))
        assertTrue(AppLogger.pageSnapshots.value.isEmpty())
    }

    @Test
    fun testLogoutFallsBackToDefaultAspxWhenNoStateOrWhenPrimaryReturns200() = runBlocking {
        seedSessionCookie("FALLBACK_SESS_111")
        val defaultHtml = readFixture("Logout_Default_page_sanitized.html")

        // Scenario A: No tracked state -> GET /Master/Default.aspx -> POST /Master/Default.aspx -> 302
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(defaultHtml)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/Login.aspx")
                .setBody("Object moved")
        )

        val outcomeA = AuthRepository().logoutDetailed(null)
        assertTrue(outcomeA.serverConfirmed)
        assertEquals(LogoutStrategy.DEFAULT_FALLBACK, outcomeA.strategy)
        assertEquals("/Master/Default.aspx", outcomeA.sourcePagePath)

        val req1 = mockWebServer.takeRequest()
        val req2 = mockWebServer.takeRequest()
        assertEquals("GET", req1.method)
        assertEquals("/Master/Default.aspx", req1.path)
        assertEquals("POST", req2.method)
        assertEquals("/Master/Default.aspx", req2.path)
        assertEquals("79A64AFF", decodeFormPairs(req2.body.readUtf8()).toMap()["__VIEWSTATEGENERATOR"])
        assertTrue("Logout fallback operations must not save disk snapshots", AppLogger.pageSnapshots.value.isEmpty())

        // Scenario B: Tracked state exists, primary POST returns 200 -> single-hop GET Default.aspx -> POST -> 302
        val futureHtml = """
            <html><body>
            <form method="post" action="./FutureMenu.aspx" id="MainForm">
                <input type="hidden" name="__VIEWSTATE" value="STALE_VS" />
                <input type="hidden" name="__VIEWSTATEGENERATOR" value="12345678" />
                <a id="logout" href="javascript:__doPostBack('ctl00${'$'}logout','')">Logout</a>
            </form>
            </body></html>
        """.trimIndent()
        CullinanHttpClient.masterPageTracker.onHttpResponse(
            url = "${CullinanHttpClient.BASE_URL}/Future/FutureMenu.aspx",
            statusCode = 200,
            contentType = "text/html",
            rawBody = futureHtml,
            isDeltaRequest = false
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("<html>Stale ViewState Error</html>"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(defaultHtml))
        mockWebServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Login.aspx"))

        val outcomeB = AuthRepository().logoutDetailed(null)
        assertTrue(outcomeB.serverConfirmed)
        assertEquals(LogoutStrategy.DEFAULT_FALLBACK, outcomeB.strategy)

        val primaryPost = mockWebServer.takeRequest()
        val fallbackGet = mockWebServer.takeRequest()
        val fallbackPost = mockWebServer.takeRequest()
        assertEquals("/Future/FutureMenu.aspx", primaryPost.path)
        assertEquals("/Master/Default.aspx", fallbackGet.path)
        assertEquals("/Master/Default.aspx", fallbackPost.path)
    }

    @Test
    fun testLogoutRecognizesAlreadyLoggedOutWhenDefaultRedirectsToLogin() = runBlocking {
        seedSessionCookie("EXPIRED_SESS_222")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/Login.aspx")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><input name='txtUsernamePlain' /><textarea id='publicKeytxt'>PEM</textarea></html>")
        )

        val outcome = AuthRepository().logoutDetailed(null)
        assertTrue(outcome.serverConfirmed)
        assertTrue(outcome.alreadyLoggedOut)
        assertEquals(LogoutStrategy.ALREADY_LOGGED_OUT, outcome.strategy)
        assertEquals(2, mockWebServer.requestCount)
        assertTrue(AppLogger.getExportableText().contains("LogoutAlreadyLoggedOut"))
    }

    @Test
    fun testLogoutHandlesNetworkFailureCleanlyAndTrackerClearsOnSessionPurge() = runBlocking {
        val tracker = MasterPageTracker()
        tracker.onHttpResponse(
            url = "https://jeton.bums.ac.ir/Master/Default.aspx",
            statusCode = 200,
            contentType = "text/html",
            rawBody = readFixture("Logout_Default_page_sanitized.html"),
            isDeltaRequest = false
        )
        assertNotNull(tracker.currentState())

        // Point to closed server to simulate network failure
        mockWebServer.shutdown()
        val outcome = AuthRepository(tracker).logoutDetailed(null)
        assertFalse(outcome.serverConfirmed)
        assertEquals(LogoutStrategy.LOCAL_ONLY, outcome.strategy)

        CullinanHttpClient.clearLocalSession()
        tracker.clear("unit_test_cleanup")
        assertNull(tracker.currentState())
        assertTrue(CullinanHttpClient.cookieJar.getCookiesForUrl("https://jeton.bums.ac.ir/".toHttpUrl()).isEmpty())
    }
}
