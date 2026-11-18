package com.example

import com.example.data.model.MandatorySurveyException
import com.example.data.model.UnreadMessagesException
import com.example.data.network.CullinanHttpClient
import com.example.data.parser.PageClassifier
import com.example.data.repository.MenuRepository
import com.example.data.repository.MessageRepository
import com.example.ui.viewmodel.MenuViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyAndPaginationUnitTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        mockWebServer = MockWebServer()
        mockWebServer.start()
        CullinanHttpClient.cookieJar.clear()
        CullinanHttpClient.setCustomBaseUrl(mockWebServer.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        CullinanHttpClient.cookieJar.clear()
        CullinanHttpClient.resetCustomBaseUrl()
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    private val realisticSurveyHtml = """
        <!DOCTYPE html>
        <html lang="fa-ir">
        <head><title>کالینان</title></head>
        <body>
        <form method="post" action="./Survey_Answer.aspx" id="MainForm">
            <input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="SURVEY_VS" />
            <input type="hidden" name="__VIEWSTATEGENERATOR" id="__VIEWSTATEGENERATOR" value="4D6A1972" />
            <div id="accordion">
                <a href="../MyCullinan/MyMessage.aspx"><div class="subMenu">پیام های من</div></a>
                <a href="../Reservation/Reservation.aspx"><div class="subMenu">رزرو و خرید هفتگی غذا</div></a>
                <a href="../Survey/Survey_Answer.aspx"><div class="subMenu">پاسخ به نظرسنجی</div></a>
            </div>
            <div id="UpdatePanel1">
                <input name="ctl00${'$'}cphMain${'$'}hdnSelectedAccordion" type="hidden" id="cphMain_hdnSelectedAccordion" value="0" />
                <div id="accordion1">
                    <h3>نظرسنجی ها</h3>
                    <div>
                        <table class="GridviewStyle" id="cphMain_grdContent">
                            <tr class="HeaderStyle">
                                <th>ردیف</th><th>نظرسنجی</th><th>وضعیت</th><th>تاریخ شروع</th><th>تاریخ پایان</th><th>مشاهده</th>
                            </tr>
                            <tr>
                                <td>1</td>
                                <td>کیفیت غذای سلف در مهر و آبان 1405</td>
                                <td>فعال</td>
                                <td>1405/08/26</td>
                                <td>1405/09/06</td>
                                <td><input type="image" name="ctl00${'$'}cphMain${'$'}grdContent${'$'}ctl02${'$'}imgbtnView" id="cphMain_grdContent_imgbtnView_0" src="../Images/Icon/Answer16.png" /></td>
                            </tr>
                        </table>
                    </div>
                    <h3>نظرسنجی انتخاب شده</h3>
                    <div></div>
                </div>
            </div>
            <script type="text/javascript">
            toastify('warning', 'شرکت در نظرسنجی، برای استفاده از امکان رزرو در سامانه ضروری است.', '', 'toast-bottom-right', 'true');
            </script>
        </form>
        </body>
        </html>
    """.trimIndent()

    private fun buildMessagesPageHtml(
        pageNumber: Int,
        rowOffset: Int,
        pagerCellsHtml: String,
        viewState: String = "VS_PAGE_$pageNumber",
        eventValidation: String = "EV_PAGE_$pageNumber"
    ): String {
        val rowsHtml = (1..5).joinToString("\n") { i ->
            val globalIdx = rowOffset + i
            val ctlIdx = "ctl0${i + 1}"
            """
                <tr>
                    <td>$globalIdx</td>
                    <td>پیام شماره $globalIdx</td>
                    <td><input type="image" name="ctl00${'$'}cphMain${'$'}grdContent${'$'}$ctlIdx${'$'}imgbtnMessageText" src="../Images/Icon/MessageText.png" /></td>
                    <td>1405/08/1$i</td>
                    <td>10:1$i:00</td>
                    <td><img src="../Images/Icon/MsgOpen.png" /></td>
                    <td>1405/08/1$i</td>
                    <td>11:00:00</td>
                </tr>
            """.trimIndent()
        }

        return """
            <!DOCTYPE html>
            <html lang="fa-ir">
            <body>
            <form method="post" action="./MyMessage.aspx" id="MainForm">
                <input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="$viewState" />
                <input type="hidden" name="__VIEWSTATEGENERATOR" id="__VIEWSTATEGENERATOR" value="87AF9C2D" />
                <input type="hidden" name="__EVENTVALIDATION" id="__EVENTVALIDATION" value="$eventValidation" />
                <a href="../Reservation/Reservation.aspx">رزرو و خرید هفتگی غذا</a>
                <div id="UpdatePanel1">
                    <table id="cphMain_grdContent" class="GridviewStyle">
                        <tr class="HeaderStyle">
                            <th>ردیف</th><th>عنوان پیام</th><th>متن پیام</th><th>تاریخ ارسال</th><th>زمان ارسال</th><th>وضعیت</th><th>تاریخ خواندن</th><th>زمان خواندن</th>
                        </tr>
                        $rowsHtml
                        <tr class="PagerStyle">
                            <td colspan="8"><table><tr>$pagerCellsHtml</tr></table></td>
                        </tr>
                    </table>
                </div>
            </form>
            </body>
            </html>
        """.trimIndent()
    }

    @Test
    fun pageClassifier_classifiesSurveyPageAsSurveyNotMessages() {
        val withUrl = PageClassifier.classify(
            html = realisticSurveyHtml,
            httpStatusCode = 200,
            finalUrl = "https://jeton.bums.ac.ir/Survey/Survey_Answer.aspx"
        )
        assertEquals(PageClassifier.TYPE_SURVEY, withUrl.pageType)
        assertFalse(withUrl.hasUnreadMessages)
        assertEquals(0, withUrl.unreadMessageCount)
        assertEquals("شرکت در نظرسنجی، برای استفاده از امکان رزرو در سامانه ضروری است.", withUrl.surveyMessage)

        val withoutUrl = PageClassifier.classify(
            html = realisticSurveyHtml,
            httpStatusCode = 200,
            finalUrl = null
        )
        assertEquals(PageClassifier.TYPE_SURVEY, withoutUrl.pageType)
    }

    @Test
    fun menuRepository_andViewModel_mapSurveyRedirectToSurveyBlockNotUnreadMessages() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/Survey/Survey_Answer.aspx")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(realisticSurveyHtml)
        )

        val menuRepo = MenuRepository()
        val viewModel = MenuViewModel(menuRepo)

        viewModel.loadInitialMenu()
        withTimeout(5_000) {
            while (viewModel.isLoading.value || viewModel.surveyBlockInfo.value == null) {
                delay(15)
            }
        }

        assertFalse("Survey block must never trigger unread messages block", viewModel.hasUnreadMessagesBlocked.value)
        val surveyBlock = viewModel.surveyBlockInfo.value
        assertNotNull("Expected SurveyBlockInfo when portal redirects to Survey_Answer.aspx", surveyBlock)
        assertTrue(surveyBlock!!.message.contains("نظرسنجی"))
        assertTrue(surveyBlock.surveyUrl.contains("Survey_Answer.aspx"))
        assertNull("Menu error snackbar should remain clear when full Survey blocking card is shown", viewModel.errorMessage.value)
    }

    @Test
    fun messageRepository_parsesPagerMetadataAndLoadsNextPage() = runBlocking {
        val pagerPage1 = """
            <td><span>1</span></td>
            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}2')">2</a></td>
            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}3')">3</a></td>
            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}6')">6</a></td>
        """.trimIndent()
        val page1Html = buildMessagesPageHtml(pageNumber = 1, rowOffset = 0, pagerCellsHtml = pagerPage1)

        val panelPage2Html = """
            <div id="UpdatePanel1">
                <table id="cphMain_grdContent" class="GridviewStyle">
                    <tr class="HeaderStyle"><th>ردیف</th></tr>
                    <tr>
                        <td>6</td><td>پیام شماره ۶</td>
                        <td><input type="image" name="ctl00${'$'}cphMain${'$'}grdContent${'$'}ctl02${'$'}imgbtnMessageText" /></td>
                        <td>1405/07/20</td><td>09:00:00</td><td><img src="../Images/Icon/MsgOpen.png" /></td><td>1405/07/21</td><td>10:00:00</td>
                    </tr>
                    <tr>
                        <td>7</td><td>پیام شماره ۷</td>
                        <td><input type="image" name="ctl00${'$'}cphMain${'$'}grdContent${'$'}ctl03${'$'}imgbtnMessageText" /></td>
                        <td>1405/07/10</td><td>08:00:00</td><td><img src="../Images/Icon/MsgClose.png" /></td><td>-</td><td>-</td>
                    </tr>
                    <tr class="PagerStyle">
                        <td colspan="8"><table><tr>
                            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}1')">1</a></td>
                            <td><span>2</span></td>
                            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}3')">3</a></td>
                            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}6')">6</a></td>
                        </tr></table></td>
                    </tr>
                </table>
            </div>
        """.trimIndent()

        val deltaPage2 = "1|#||4|${panelPage2Html.length}|updatePanel|ctl00${'$'}UpdatePanel1|$panelPage2Html|9|hiddenField|__VIEWSTATE|VS_PAGE_2|9|hiddenField|__EVENTVALIDATION|EV_PAGE_2|"

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(page1Html))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(deltaPage2))

        val repo = MessageRepository()
        val page1Result = repo.fetchMessagesPage()
        assertTrue(page1Result.isSuccess)
        val state1 = page1Result.getOrThrow()

        assertEquals(5, state1.messages.size)
        assertEquals(1, state1.currentServerPage)
        assertEquals(1, state1.loadedPagesCount)
        assertEquals(6, state1.totalKnownPages)
        assertTrue(state1.hasNextPage)
        assertEquals("ctl00\$cphMain\$grdContent", state1.nextPageEventTarget)
        assertEquals("Page\$2", state1.nextPageEventArgument)

        val page2Result = repo.fetchNextMessagesPage(state1)
        assertTrue(page2Result.isSuccess)
        val state2 = page2Result.getOrThrow()

        assertEquals(7, state2.messages.size)
        assertEquals(2, state2.currentServerPage)
        assertEquals(2, state2.loadedPagesCount)
        assertEquals(6, state2.totalKnownPages)
        assertEquals("Page\$3", state2.nextPageEventArgument)
        assertEquals(1, state2.unreadCount)
        assertEquals(1, state2.messages.first().pageNumber)
        assertEquals(2, state2.messages.last().pageNumber)

        mockWebServer.takeRequest() // initial GET
        val page2Req = mockWebServer.takeRequest()
        val reqBody = URLDecoder.decode(page2Req.body.readUtf8(), StandardCharsets.UTF_8.name())
        assertTrue(reqBody.contains("__EVENTTARGET=ctl00\$cphMain\$grdContent"))
        assertTrue(reqBody.contains("__EVENTARGUMENT=Page\$2"))
        assertTrue(reqBody.contains("__ASYNCPOST=true"))
        assertTrue(reqBody.contains("__VIEWSTATE=VS_PAGE_1"))
    }

    @Test
    fun messageRepository_openMessageFromPage1AfterLoadingPage2SwitchesPageFirst() = runBlocking {
        val pagerPage1 = """
            <td><span>1</span></td>
            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}2')">2</a></td>
            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}3')">3</a></td>
        """.trimIndent()
        val page1Html = buildMessagesPageHtml(pageNumber = 1, rowOffset = 0, pagerCellsHtml = pagerPage1)

        val panelPage2Html = """
            <div id="UpdatePanel1">
                <table id="cphMain_grdContent" class="GridviewStyle">
                    <tr class="HeaderStyle"><th>ردیف</th></tr>
                    <tr>
                        <td>6</td><td>پیام شماره ۶</td>
                        <td><input type="image" name="ctl00${'$'}cphMain${'$'}grdContent${'$'}ctl02${'$'}imgbtnMessageText" /></td>
                        <td>1405/07/20</td><td>09:00:00</td><td><img src="../Images/Icon/MsgOpen.png" /></td><td>1405/07/21</td><td>10:00:00</td>
                    </tr>
                    <tr class="PagerStyle">
                        <td colspan="8"><table><tr>
                            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}1')">1</a></td>
                            <td><span>2</span></td>
                            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}3')">3</a></td>
                        </tr></table></td>
                    </tr>
                </table>
            </div>
        """.trimIndent()
        val deltaPage2 = "1|#||4|${panelPage2Html.length}|updatePanel|ctl00${'$'}UpdatePanel1|$panelPage2Html|9|hiddenField|__VIEWSTATE|VS_PAGE_2|9|hiddenField|__EVENTVALIDATION|EV_PAGE_2|"

        val panelSwitchBackTo1 = """
            <div id="UpdatePanel1">
                <table id="cphMain_grdContent" class="GridviewStyle">
                    <tr class="HeaderStyle"><th>ردیف</th></tr>
                    <tr>
                        <td>1</td><td>پیام شماره 1</td>
                        <td><input type="image" name="ctl00${'$'}cphMain${'$'}grdContent${'$'}ctl02${'$'}imgbtnMessageText" /></td>
                        <td>1405/08/11</td><td>10:11:00</td><td><img src="../Images/Icon/MsgOpen.png" /></td><td>1405/08/11</td><td>11:00:00</td>
                    </tr>
                    <tr class="PagerStyle">
                        <td colspan="8"><table><tr>
                            <td><span>1</span></td>
                            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}2')">2</a></td>
                            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}3')">3</a></td>
                        </tr></table></td>
                    </tr>
                </table>
            </div>
        """.trimIndent()
        val deltaSwitchTo1 = "1|#||4|${panelSwitchBackTo1.length}|updatePanel|ctl00${'$'}UpdatePanel1|$panelSwitchBackTo1|14|hiddenField|__VIEWSTATE|VS_BACK_PAGE_1|14|hiddenField|__EVENTVALIDATION|EV_BACK_PAGE_1|"

        val openedContentHtml = "<div><p id=\"cphMain_pMessage\">متن کامل پیام اول از صفحه یک</p></div>"
        val deltaOpenMsg1 = "1|#||4|${openedContentHtml.length}|updatePanel|ctl00${'$'}UpdatePanel1|$openedContentHtml|13|hiddenField|__VIEWSTATE|VS_AFTER_OPEN|13|hiddenField|__EVENTVALIDATION|EV_AFTER_OPEN|"

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(page1Html))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(deltaPage2))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(deltaSwitchTo1))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(deltaOpenMsg1))

        val repo = MessageRepository()
        val state1 = repo.fetchMessagesPage().getOrThrow()
        val state2 = repo.fetchNextMessagesPage(state1).getOrThrow()

        val firstPageMessage = state2.messages.first()
        assertEquals(1, firstPageMessage.pageNumber)

        val openRes = repo.openMessageForItem(firstPageMessage, state2)
        assertTrue(openRes.isSuccess)
        val (updatedState, updatedItem) = openRes.getOrThrow()

        assertEquals("متن کامل پیام اول از صفحه یک", updatedItem.messageText)
        assertEquals(6, updatedState.messages.size)
        assertEquals("Page\$3", updatedState.nextPageEventArgument)
        assertEquals(1, updatedState.currentServerPage)

        mockWebServer.takeRequest() // GET page 1
        mockWebServer.takeRequest() // POST Page$2
        val switchReq = mockWebServer.takeRequest()
        val switchBody = URLDecoder.decode(switchReq.body.readUtf8(), StandardCharsets.UTF_8.name())
        assertTrue(switchBody.contains("__EVENTARGUMENT=Page\$1"))
        assertTrue(switchBody.contains("__VIEWSTATE=VS_PAGE_2"))

        val openReq = mockWebServer.takeRequest()
        val openBody = URLDecoder.decode(openReq.body.readUtf8(), StandardCharsets.UTF_8.name())
        assertTrue(openBody.contains("__VIEWSTATE=VS_BACK_PAGE_1"))
        assertTrue(openBody.contains("ctl00\$cphMain\$grdContent\$ctl02\$imgbtnMessageText.x=10"))
    }

    @Test
    fun messageRepository_supportsEllipsisPagerForMoreThanTenPages() = runBlocking {
        val pagerBlockEnd = """
            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}9')">9</a></td>
            <td><span>10</span></td>
            <td><a href="javascript:__doPostBack('ctl00${'$'}cphMain${'$'}grdContent','Page${'$'}11')">...</a></td>
        """.trimIndent()
        val page10Html = buildMessagesPageHtml(pageNumber = 10, rowOffset = 45, pagerCellsHtml = pagerBlockEnd)
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(page10Html))

        val state = MessageRepository().fetchMessagesPage().getOrThrow()
        assertEquals(10, state.currentServerPage)
        assertTrue(state.hasNextPage)
        assertEquals("Page\$11", state.nextPageEventArgument)
    }
}
