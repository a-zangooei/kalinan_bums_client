package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.logger.AppLogger
import com.example.data.model.BasketStatus
import com.example.data.model.DayMenu
import com.example.data.model.MealItem
import com.example.data.model.MealType
import com.example.data.model.ReservationStatus
import com.example.data.model.WeeklyMenu
import com.example.data.network.CullinanHttpClient
import com.example.data.repository.FoodBasketRepository
import com.example.ui.screens.FoodBasketScreen
import com.example.ui.theme.CullinanTheme
import com.example.ui.viewmodel.FoodBasketViewModel
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class FoodBasketComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        AppLogger.clearLogs()
        mockWebServer = MockWebServer()
        mockWebServer.start()
        CullinanHttpClient.clearLocalSession()
        CullinanHttpClient.setCustomBaseUrl(mockWebServer.url("/").toString().trimEnd('/'))
        val baseUrl = mockWebServer.url("/").toString().toHttpUrl()
        CullinanHttpClient.cookieJar.saveFromResponse(
            baseUrl,
            listOf(
                Cookie.Builder()
                    .name("ASP.NET_SessionId")
                    .value("COMPOSE_TEST_SESSION")
                    .domain(baseUrl.host)
                    .path("/")
                    .build()
            )
        )
    }

    @After
    fun tearDown() {
        CullinanHttpClient.clearLocalSession()
        CullinanHttpClient.resetCustomBaseUrl()
        mockWebServer.shutdown()
        AppLogger.clearLogs()
    }

    private fun readFixture(name: String): String {
        val file = listOf(
            File("docs/05-samples-and-fixtures/$name"),
            File("../docs/05-samples-and-fixtures/$name")
        ).firstOrNull { it.exists() }
        requireNotNull(file) { "Fixture not found: $name" }
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun sellRequiresExplicitConfirmationDialogAndUpdatesViaPostActionReload() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("FoodBasketSale_page_sanitized.html")))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("FoodBasket_reload_delta_sanitized.txt")))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("FoodBasket_sell_delta_sanitized.txt")))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("FoodBasket_reload_after_sell_delta_sanitized.txt")))

        val viewModel = FoodBasketViewModel(FoodBasketRepository())
        viewModel.updateWeeklyMenu(
            WeeklyMenu(
                weekDateRange = "1405/09/01 الی 1405/09/07",
                days = listOf(
                    DayMenu(
                        dayIndex = 3,
                        dayName = "سه‌شنبه",
                        dateJalali = "1405/09/03",
                        meals = mapOf(
                            MealType.LUNCH to listOf(
                                MealItem(
                                    id = "m1",
                                    mealType = MealType.LUNCH,
                                    dayIndex = 3,
                                    foodName = "نام غذای نمونه",
                                    diningHall = "سلف نمونه",
                                    priceRials = 180000L,
                                    priceFormatted = "180,000 ریال",
                                    calories = 0,
                                    status = ReservationStatus.RESERVED_LOCKED,
                                    count = 1
                                )
                            )
                        )
                    )
                )
            )
        )

        composeRule.setContent {
            CullinanTheme {
                FoodBasketScreen(
                    viewModel = viewModel,
                    onLogout = {},
                    isLoggingOut = false,
                    onOpenProfile = {},
                    onOpenMessagesTab = {},
                    onOpenSurveyWebView = {},
                    onReloginRequired = {}
                )
            }
        }

        // Wait until initial GET completes
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.pageSession != null
        }

        // Click the quick reserved meal chip (triggers btnFoodReload)
        composeRule.onNodeWithTag("basket_quick_slot_1405/09/03_2").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.currentItem != null
        }

        composeRule.onNodeWithTag("basket_result_card").assertIsDisplayed()
        composeRule.onNodeWithTag("basket_sell_button").assertIsDisplayed()

        val requestsBeforeTap = mockWebServer.requestCount
        // Tap "فروش به سبد غذا" -> MUST open confirmation dialog, ZERO sell POST yet!
        composeRule.onNodeWithTag("basket_sell_button").performClick()
        composeRule.waitForIdle()

        assertEquals("Tapping sell must not fire network request before dialog confirmation", requestsBeforeTap, mockWebServer.requestCount)
        composeRule.onNodeWithTag("basket_dialog_confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("basket_dialog_cancel").assertIsDisplayed()

        // Confirm dialog -> sends btnAccept + post-action sync btnFoodReload
        composeRule.onNodeWithTag("basket_dialog_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.currentItem?.status == BasketStatus.IN_BASKET &&
                !viewModel.uiState.value.isBusy
        }

        composeRule.onNodeWithTag("basket_cancel_button").assertIsDisplayed()
        composeRule.onNodeWithTag("basket_feedback_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("basket_feedback_banner").assertTextContains("این غذا به سبد غذایی جهت فروش اضافه شد", substring = true)
        assertTrue(mockWebServer.requestCount >= 4)
    }

    @Test
    fun dismissingConfirmationDialogSendsZeroNetworkRequests() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("FoodBasketSale_page_sanitized.html")))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(readFixture("FoodBasket_reload_delta_sanitized.txt")))

        val viewModel = FoodBasketViewModel(FoodBasketRepository())

        composeRule.setContent {
            CullinanTheme {
                FoodBasketScreen(
                    viewModel = viewModel,
                    onLogout = {},
                    isLoggingOut = false,
                    onOpenProfile = {},
                    onOpenMessagesTab = {},
                    onOpenSurveyWebView = {},
                    onReloginRequired = {}
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.pageSession != null
        }

        composeRule.onNodeWithTag("basket_meal_type_2").performClick()
        composeRule.onNodeWithTag("basket_reload_button").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.currentItem != null
        }

        val countAfterReload = mockWebServer.requestCount
        composeRule.onNodeWithTag("basket_sell_button").performClick()
        composeRule.onNodeWithTag("basket_dialog_cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(countAfterReload, mockWebServer.requestCount)
        composeRule.onNodeWithText("فروش به سبد غذا").assertIsDisplayed()
    }
}
