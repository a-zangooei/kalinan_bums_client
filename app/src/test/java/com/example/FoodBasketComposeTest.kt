package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.data.logger.AppLogger
import com.example.data.model.BasketActionButton
import com.example.data.model.BasketActionOutcome
import com.example.data.model.BasketItemState
import com.example.data.model.BasketPageSession
import com.example.data.model.BasketReloadResult
import com.example.data.model.BasketSlot
import com.example.data.model.BasketStatus
import com.example.data.model.DayMenu
import com.example.data.model.MealItem
import com.example.data.model.MealType
import com.example.data.model.ReservationStatus
import com.example.data.model.WeeklyMenu
import com.example.data.parser.DeltaParser
import com.example.data.parser.FoodBasketParser
import com.example.data.repository.FoodBasketDataSource
import com.example.ui.screens.FoodBasketScreen
import com.example.ui.theme.CullinanTheme
import com.example.ui.viewmodel.FoodBasketViewModel
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        AppLogger.clearLogs()
    }

    @After
    fun tearDown() {
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

    private class FixtureFoodBasketDataSource(
        private val initialHtml: String,
        private val reloadSequence: ArrayDeque<String>,
        private val actionSequence: ArrayDeque<Pair<BasketActionButton, String>>
    ) : FoodBasketDataSource {
        var openCalls = 0
            private set
        var reloadCalls = 0
            private set
        var actionCalls = 0
            private set

        val totalRequests: Int
            get() = openCalls + reloadCalls + actionCalls

        override suspend fun openSalePage(): Result<BasketPageSession> {
            openCalls++
            return Result.success(FoodBasketParser.parseInitialPage(initialHtml))
        }

        override suspend fun reloadSlot(
            slot: BasketSlot,
            session: BasketPageSession
        ): Result<BasketReloadResult> {
            reloadCalls++
            val rawDelta = reloadSequence.removeFirstOrNull()
                ?: error("Unexpected reloadSlot call #$reloadCalls")
            val delta = DeltaParser.parse(rawDelta)
            return Result.success(FoodBasketParser.parseUpdatePanelDelta(delta, slot, session))
        }

        override suspend fun executeSaleOrCancel(
            action: BasketActionButton,
            item: BasketItemState,
            session: BasketPageSession
        ): Result<BasketActionOutcome> {
            actionCalls++
            val (expectedAction, rawDelta) = actionSequence.removeFirstOrNull()
                ?: error("Unexpected executeSaleOrCancel call #$actionCalls")
            assertEquals(expectedAction, action)
            val delta = DeltaParser.parse(rawDelta)
            val parsed = FoodBasketParser.parseUpdatePanelDelta(delta, item.slot, session)
            return Result.success(
                BasketActionOutcome(
                    action = action,
                    isSuccess = delta.toastType.equals("success", ignoreCase = true),
                    toastType = delta.toastType.orEmpty(),
                    toastTitle = delta.toastTitle.orEmpty(),
                    toastMessage = delta.toastMessage.orEmpty(),
                    updatedItem = parsed.item,
                    updatedSession = parsed.updatedSession,
                    alertMessage = parsed.alertMessage
                )
            )
        }
    }

    private fun scrollBasketTo(tag: String) {
        composeRule.onNodeWithTag("basket_lazy_column")
            .performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    @Test
    fun sellRequiresExplicitConfirmationDialogAndUpdatesViaPostActionReload() {
        val dataSource = FixtureFoodBasketDataSource(
            initialHtml = readFixture("FoodBasketSale_page_sanitized.html"),
            reloadSequence = ArrayDeque(
                listOf(
                    readFixture("FoodBasket_reload_delta_sanitized.txt"),
                    readFixture("FoodBasket_reload_after_sell_delta_sanitized.txt")
                )
            ),
            actionSequence = ArrayDeque(
                listOf(
                    BasketActionButton.ACCEPT to readFixture("FoodBasket_sell_delta_sanitized.txt")
                )
            )
        )

        val viewModel = FoodBasketViewModel(dataSource)
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

        // Wait until initial page session completes
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.pageSession != null && !viewModel.uiState.value.isBusy
        }

        // Click the quick reserved meal chip (triggers btnFoodReload)
        scrollBasketTo("basket_quick_slot_1405/09/03_2")
        composeRule.onNodeWithTag("basket_quick_slot_1405/09/03_2").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.currentItem != null && !viewModel.uiState.value.isBusy
        }

        scrollBasketTo("basket_result_card")
        composeRule.onNodeWithTag("basket_result_card").assertIsDisplayed()
        scrollBasketTo("basket_sell_button")
        composeRule.onNodeWithTag("basket_sell_button").assertIsDisplayed()

        val requestsBeforeTap = dataSource.totalRequests
        // Tap "فروش به سبد غذا" -> MUST open confirmation dialog, ZERO sell POST yet!
        composeRule.onNodeWithTag("basket_sell_button").performClick()
        composeRule.waitForIdle()

        assertEquals(
            "Tapping sell must not fire network request before dialog confirmation",
            requestsBeforeTap,
            dataSource.totalRequests
        )
        composeRule.onNodeWithTag("basket_dialog_confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("basket_dialog_cancel").assertIsDisplayed()

        // Confirm dialog -> sends btnAccept + mandatory post-action sync btnFoodReload
        composeRule.onNodeWithTag("basket_dialog_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.currentItem?.status == BasketStatus.IN_BASKET &&
                !viewModel.uiState.value.isBusy
        }

        scrollBasketTo("basket_cancel_button")
        composeRule.onNodeWithTag("basket_cancel_button").assertIsDisplayed()

        scrollBasketTo("basket_feedback_banner")
        composeRule.onNodeWithTag("basket_feedback_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("basket_feedback_banner")
            .assertTextContains("این غذا به سبد غذایی جهت فروش اضافه شد", substring = true)

        assertEquals(1, dataSource.openCalls)
        assertEquals(2, dataSource.reloadCalls)
        assertEquals(1, dataSource.actionCalls)
        assertEquals(4, dataSource.totalRequests)
    }

    @Test
    fun dismissingConfirmationDialogSendsZeroNetworkRequests() {
        val dataSource = FixtureFoodBasketDataSource(
            initialHtml = readFixture("FoodBasketSale_page_sanitized.html"),
            reloadSequence = ArrayDeque(
                listOf(
                    readFixture("FoodBasket_reload_delta_sanitized.txt")
                )
            ),
            actionSequence = ArrayDeque()
        )

        val viewModel = FoodBasketViewModel(dataSource)

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
            viewModel.uiState.value.pageSession != null && !viewModel.uiState.value.isBusy
        }

        scrollBasketTo("basket_meal_type_2")
        composeRule.onNodeWithTag("basket_meal_type_2").performClick()
        scrollBasketTo("basket_reload_button")
        composeRule.onNodeWithTag("basket_reload_button").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.currentItem != null && !viewModel.uiState.value.isBusy
        }

        scrollBasketTo("basket_sell_button")
        val countAfterReload = dataSource.totalRequests
        composeRule.onNodeWithTag("basket_sell_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("basket_dialog_cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(countAfterReload, dataSource.totalRequests)
        assertEquals(0, dataSource.actionCalls)
        composeRule.onNodeWithTag("basket_sell_button").assertIsDisplayed()
        composeRule.onNodeWithTag("basket_sell_button").assertTextContains("فروش به سبد غذا", substring = true)
    }
}
