package com.example

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.captcha.solver.CaptchaFrame
import com.example.data.captcha.solver.CaptchaResult
import com.example.data.captcha.solver.CaptchaSolver
import com.example.data.captcha.solver.CaptchaSolverConfig
import com.example.data.captcha.solver.MlKitCaptchaSolver
import com.example.data.captcha.solver.OcrElement
import com.example.data.captcha.solver.OcrModuleState
import com.example.data.captcha.solver.OcrOutput
import com.example.data.captcha.solver.OcrSession
import com.example.data.local.UserPreferences
import com.example.data.logger.AppLogger
import com.example.data.logger.LogLevel
import com.google.mlkit.common.MlKitException
import com.example.data.model.AppThemeMode
import com.example.data.network.CullinanHttpClient
import com.example.data.repository.AuthRepository
import com.example.data.security.BiometricCapabilityChecker
import com.example.ui.screens.LoginScreenContent
import com.example.ui.screens.SettingsScreenContent
import com.example.ui.theme.CullinanTheme
import com.example.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthViewModelAutoCaptchaUnitTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var testPublicKeyPem: String

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(UserPreferences.LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(UserPreferences.UI_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("test_auto_captcha_secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        AppLogger.clearLogs()

        val keyGen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val encoded = Base64.encodeToString(keyGen.generateKeyPair().public.encoded, Base64.NO_WRAP)
        testPublicKeyPem = "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----"

        server = MockWebServer()
        server.start()
        CullinanHttpClient.setCustomBaseUrl(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        CullinanHttpClient.resetCustomBaseUrl()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun createPrefs(autoCaptchaEnabled: Boolean? = null): UserPreferences {
        val prefs = UserPreferences(
            context,
            UserPreferences.SecurePrefsFactory { ctx ->
                ctx.getSharedPreferences("test_auto_captcha_secure_prefs", Context.MODE_PRIVATE)
            }
        )
        if (autoCaptchaEnabled != null) {
            prefs.isAutoCaptchaEnabled = autoCaptchaEnabled
        }
        return prefs
    }

    private fun loginPageHtml(viewState: String = "VS1"): String = """
        <html><body>
        <input type="hidden" name="__VIEWSTATE" value="$viewState" />
        <input type="hidden" name="__VIEWSTATEGENERATOR" value="87AF9C2D" />
        <textarea id="publicKeytxt" name="publicKeytxt">$testPublicKeyPem</textarea>
        </body></html>
    """.trimIndent()

    private fun binaryCaptchaResponse(bytes: ByteArray): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "image/png")
            .setBody(Buffer().write(bytes))

    private fun awaitCondition(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    @Test
    fun defaultPreferenceIsOff_andSolverFactoryIsNeverInvoked() {
        val prefs = createPrefs()
        assertFalse("Auto-captcha preference must default to OFF", prefs.isAutoCaptchaEnabled)

        val img1 = byteArrayOf(1, 2, 3, 4)
        val img2 = byteArrayOf(5, 6, 7, 8)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(img1))
        server.enqueue(binaryCaptchaResponse(img2))

        val factoryCalls = AtomicInteger(0)
        val solveCalls = AtomicInteger(0)
        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                factoryCalls.incrementAndGet()
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult {
                        solveCalls.incrementAndGet()
                        return CaptchaResult.Success("1234")
                    }
                }
            }
        )

        awaitCondition { vm.pageState.value.captchaBytes != null }
        assertEquals(0, factoryCalls.get())
        assertEquals(0, solveCalls.get())
        assertEquals("", vm.captchaInput.value)
        assertFalse(vm.isCaptchaAutoFilled.value)
        assertNull(vm.captchaSoftMessage.value)

        vm.refreshCaptcha()
        awaitCondition { vm.pageState.value.captchaBytes?.contentEquals(img2) == true }
        assertEquals(0, factoryCalls.get())
        assertEquals(0, solveCalls.get())
    }

    @Test
    fun whenEnabledAndSuccess_fillsEmptyFieldOnce_andDoesNotRepeatOnResumeOrAutoLogin() {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val imgBytes = byteArrayOf(10, 20, 30, 40)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(imgBytes))

        val solveCalls = AtomicInteger(0)
        var loginCallbackInvoked = false
        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult {
                        solveCalls.incrementAndGet()
                        return CaptchaResult.Success("4829")
                    }
                }
            },
            ocrModuleAvailabilityChecker = { true }
        )

        awaitCondition { vm.captchaInput.value == "4829" }
        assertTrue(vm.isCaptchaAutoFilled.value)
        assertNull(vm.captchaSoftMessage.value)
        assertEquals(1, solveCalls.get())
        assertEquals(2, server.requestCount)
        assertFalse(loginCallbackInvoked)

        // Simulating screen rotation / ON_RESUME syncCredentialState must NOT repeat OCR
        vm.syncCredentialState(initialLoad = false)
        assertEquals(1, solveCalls.get())
        assertEquals("4829", vm.captchaInput.value)
        assertTrue(vm.isCaptchaAutoFilled.value)

        // Manual edit clears the auto-filled badge
        vm.onCaptchaChange("4820")
        assertEquals("4820", vm.captchaInput.value)
        assertFalse(vm.isCaptchaAutoFilled.value)
    }

    @Test
    fun whenEnabledAndSuccess_doesNotOverwriteExistingUserTypedText() = runBlocking {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val imgBytes = byteArrayOf(11, 22, 33, 44)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(imgBytes))

        val gate = CompletableDeferred<CaptchaResult>()
        val started = CompletableDeferred<Unit>()
        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult {
                        started.complete(Unit)
                        return gate.await()
                    }
                }
            },
            ocrModuleAvailabilityChecker = { true }
        )

        started.await()
        // User types manually while OCR is running
        vm.onCaptchaChange("77")
        gate.complete(CaptchaResult.Success("9999"))

        awaitCondition { !vm.isSolvingCaptcha.value }
        assertEquals("77", vm.captchaInput.value)
        assertFalse("Existing user text must not be marked auto-filled", vm.isCaptchaAutoFilled.value)
        assertNull(vm.captchaSoftMessage.value)
    }

    @Test
    fun whenNotConfidentOrError_leavesFieldEmpty_showsSoftMessage_andNeverAutoRefreshesOrLogsIn() {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val img1 = byteArrayOf(1, 3, 5)
        val img2 = byteArrayOf(2, 4, 6)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(img1))
        server.enqueue(binaryCaptchaResponse(img2))

        val results = ArrayDeque(
            listOf(
                CaptchaResult.NotConfident("low confidence"),
                CaptchaResult.Error("timeout")
            )
        )
        val solveCalls = AtomicInteger(0)
        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult {
                        solveCalls.incrementAndGet()
                        return results.removeFirst()
                    }
                }
            },
            ocrModuleAvailabilityChecker = { true }
        )

        awaitCondition { vm.captchaSoftMessage.value == AuthViewModel.CAPTCHA_SOLVE_FAILED_MESSAGE }
        assertEquals("", vm.captchaInput.value)
        assertFalse(vm.isCaptchaAutoFilled.value)
        assertEquals(1, solveCalls.get())
        // Must NOT auto-refresh captcha or submit login (only GET Login.aspx + GET captcha)
        assertEquals(2, server.requestCount)

        // Manual "کد جدید" runs OCR again -> Error result still leaves empty + shows soft message
        vm.refreshCaptcha()
        awaitCondition { solveCalls.get() == 2 && !vm.isSolvingCaptcha.value }
        assertEquals("", vm.captchaInput.value)
        assertEquals(AuthViewModel.CAPTCHA_SOLVE_FAILED_MESSAGE, vm.captchaSoftMessage.value)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun refreshMidOcr_cancelsPriorJob_ignoresStaleResult_andAppliesFreshResult() = runBlocking {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val img1 = byteArrayOf(10, 10, 10)
        val img2 = byteArrayOf(20, 20, 20)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(img1))
        server.enqueue(binaryCaptchaResponse(img2))

        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<CaptchaResult>()
        var firstWasCancelled = false
        val callIndex = AtomicInteger(0)

        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult {
                        return if (callIndex.incrementAndGet() == 1) {
                            try {
                                firstStarted.complete(Unit)
                                firstGate.await()
                            } catch (ce: CancellationException) {
                                firstWasCancelled = true
                                throw ce
                            }
                        } else {
                            CaptchaResult.Success("9012")
                        }
                    }
                }
            },
            ocrModuleAvailabilityChecker = { true }
        )

        firstStarted.await()
        assertTrue(vm.isSolvingCaptcha.value)

        // Trigger "کد جدید" while first OCR is mid-flight
        vm.refreshCaptcha()

        awaitCondition { vm.captchaInput.value == "9012" && !vm.isSolvingCaptcha.value }
        assertTrue("Previous OCR job must be cancelled on captcha refresh", firstWasCancelled)

        // Even if the deferred resolves later, stale "1111" must never touch current field
        firstGate.complete(CaptchaResult.Success("1111"))
        assertEquals("9012", vm.captchaInput.value)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun afterFailedLogin_refreshesAndSolvesNewCaptcha_withoutAutoSubmittingLogin() {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val imgInitial = byteArrayOf(1, 2, 3)
        val imgAfterFail = byteArrayOf(4, 5, 6)

        val failedLoginHtml = """
            <html><body>
            <span id="lblMessage">کد امنیتی اشتباه است</span>
            <input type="hidden" name="__VIEWSTATE" value="VS2" />
            <input type="hidden" name="__VIEWSTATEGENERATOR" value="87AF9C2D" />
            <input type="hidden" name="publicKeytxt" value="$testPublicKeyPem" />
            </body></html>
        """.trimIndent()

        // 1) Initial load (GET html + GET captcha1)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(imgInitial))
        // 2) User submits login -> POST fails -> AuthViewModel calls refreshCaptcha() (GET captcha2)
        server.enqueue(MockResponse().setResponseCode(200).setBody(failedLoginHtml))
        server.enqueue(binaryCaptchaResponse(imgAfterFail))

        val seenImages = CopyOnWriteArrayList<ByteArray>()
        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult {
                        seenImages.add(imageBytes)
                        return if (imageBytes.contentEquals(imgInitial)) {
                            CaptchaResult.Success("4321")
                        } else {
                            CaptchaResult.Success("6543")
                        }
                    }
                }
            },
            ocrModuleAvailabilityChecker = { true }
        )

        awaitCondition { vm.captchaInput.value == "4321" }
        vm.onUsernameChange("4031001")
        vm.onPasswordChange("Pass123!")

        var loginSuccessCount = 0
        vm.login { loginSuccessCount++ }

        awaitCondition { vm.captchaInput.value == "6543" && !vm.isLoading.value && !vm.isCaptchaRefreshing.value }
        assertEquals(0, loginSuccessCount)
        assertEquals(2, seenImages.size)

        // Inspect all HTTP requests recorded by MockWebServer: exactly 1 POST, never auto-retried
        val recordedMethods = mutableListOf<String>()
        repeat(server.requestCount) {
            recordedMethods.add(server.takeRequest().method ?: "")
        }
        assertEquals(1, recordedMethods.count { it == "POST" })
        assertEquals(4, recordedMethods.size)
    }

    @Test
    fun zeroSensitiveLoggingOfRecognizedDigitsOrImageBytes() {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val imgBytes = "SECRET_PNG_BYTES".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(imgBytes))

        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult =
                        CaptchaResult.Success("7391")
                }
            },
            ocrModuleAvailabilityChecker = { true }
        )

        awaitCondition { vm.captchaInput.value == "7391" }
        val allLogs = AppLogger.recentEntries.value.joinToString("\n") {
            "${it.operation} ${it.message} ${it.exceptionMessage.orEmpty()}"
        }
        assertFalse("Recognized captcha digits must never be logged", allLogs.contains("7391"))
        assertFalse("Raw captcha image content must never be logged", allLogs.contains("SECRET_PNG_BYTES"))
        assertTrue(AppLogger.recentEntries.value.any { it.component == "CaptchaOCR" && it.operation == "AutoSolveSuccess" })
    }

    @Test
    fun whenMlKitFails_logsTechnicalDiagnosticsToAppLoggerOnly_andKeepsSimpleUserHint() {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val imgBytes = "FAILING_CAPTCHA_IMAGE".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(imgBytes))

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = testDispatcher,
            framePreprocessor = { _, _ ->
                object : CaptchaFrame {
                    override fun release() {}
                }
            },
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame): OcrOutput {
                        throw MlKitException("Waiting for the text optional module", MlKitException.UNAVAILABLE)
                    }
                    override fun close() {}
                }
            }
        )

        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = { solver },
            ocrModuleAvailabilityChecker = { true }
        )

        awaitCondition { vm.captchaSoftMessage.value == AuthViewModel.CAPTCHA_SOLVE_FAILED_MESSAGE && !vm.isSolvingCaptcha.value }
        // User-facing soft hint remains simple and non-technical
        assertEquals(AuthViewModel.CAPTCHA_SOLVE_FAILED_MESSAGE, vm.captchaSoftMessage.value)
        assertEquals("", vm.captchaInput.value)

        // Developer logs contain technical root cause (stage, mlKitErrorCode, exceptionType, attempts)
        val diagEntry = AppLogger.recentEntries.value.firstOrNull {
            it.component == "CaptchaOCR" && it.operation == "AutoSolveError"
        }
        assertTrue("Expected CaptchaOCR AutoSolveError log entry", diagEntry != null)
        assertEquals(LogLevel.WARN, diagEntry!!.level)
        assertEquals("CAPTCHA_OCR_RECOGNIZE", diagEntry.errorCategory)
        assertEquals("com.google.mlkit.common.MlKitException", diagEntry.exceptionType)
        assertTrue(diagEntry.message.contains("stage=RECOGNIZE"))
        assertTrue(diagEntry.message.contains("mlKitErrorCode=${MlKitException.UNAVAILABLE}"))
        assertTrue(diagEntry.message.contains("attempts=1"))
        assertTrue(diagEntry.exceptionMessage?.contains("MlKitException") == true)

        val exportedText = AppLogger.getExportableText()
        assertFalse(exportedText.contains("FAILING_CAPTCHA_IMAGE"))
        assertTrue(exportedText.contains("CaptchaOCR/AutoSolveError"))
    }

    @Test
    fun whenOcrNotConfident_logsStageAndCountsWithoutLeakingRecognizedTextOrDigits() {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val imgBytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(imgBytes))

        val solver = MlKitCaptchaSolver(
            solverConfig = CaptchaSolverConfig(),
            dispatcher = testDispatcher,
            framePreprocessor = { _, _ ->
                object : CaptchaFrame {
                    override fun release() {}
                }
            },
            sessionFactory = {
                object : OcrSession {
                    override suspend fun recognize(frame: CaptchaFrame): OcrOutput =
                        OcrOutput("84629", listOf(OcrElement("84629", 0.91f)))
                    override fun close() {}
                }
            }
        )

        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = { solver },
            ocrModuleAvailabilityChecker = { true }
        )

        awaitCondition { vm.captchaSoftMessage.value == AuthViewModel.CAPTCHA_SOLVE_FAILED_MESSAGE && !vm.isSolvingCaptcha.value }
        val diagEntry = AppLogger.recentEntries.value.firstOrNull {
            it.component == "CaptchaOCR" && it.operation == "AutoSolveNotConfident"
        }
        assertTrue("Expected CaptchaOCR AutoSolveNotConfident log entry", diagEntry != null)
        assertEquals("CAPTCHA_OCR_DIGIT_COUNT_MISMATCH", diagEntry!!.errorCategory)
        assertTrue(diagEntry.message.contains("stage=VALIDATE"))
        assertTrue(diagEntry.message.contains("attempts=3"))
        assertTrue(diagEntry.message.contains("elements=1"))
        assertTrue(diagEntry.message.contains("foundDigitsCount=5"))
        assertFalse("Recognized OCR digits must NEVER appear in logs", diagEntry.message.contains("84629"))
        assertFalse("Recognized OCR digits must NEVER appear in exception field", diagEntry.exceptionMessage.orEmpty().contains("84629"))
    }

    @Test
    fun settingsSwitchAndLoginBadges_renderAccessiblePersianCopy() {
        var toggledTo: Boolean? = null
        composeRule.setContent {
            CullinanTheme(darkTheme = false) {
                SettingsScreenContent(
                    currentThemeMode = AppThemeMode.LIGHT,
                    isEncryptionAvailable = true,
                    canUseBiometric = true,
                    isBiometricEnabled = false,
                    baseUrl = "https://jeton.bums.ac.ir",
                    isCompatibilityModeActive = false,
                    isDevUnlocked = false,
                    versionName = "1.0.0",
                    versionCode = 66,
                    onNavigateBack = {},
                    onSelectTheme = {},
                    onToggleBiometric = {},
                    onOpenDeviceSecuritySettings = {},
                    onRecoveryWipeAndDisableLock = {},
                    onToggleCompatibilityMode = {},
                    onOpenLogs = {},
                    onExportZip = {},
                    onClearLogs = {},
                    onExitDeveloperMode = {},
                    onVersionClick = {},
                    onCheckForUpdates = {},
                    isAutoCaptchaEnabled = false,
                    onToggleAutoCaptcha = { toggledTo = it }
                )
            }
        }

        composeRule.onNodeWithTag("auto_captcha_switch").performScrollTo().assertIsDisplayed().assertIsOff()
        composeRule.onNodeWithText("تشخیص خودکار کد امنیتی (روی دستگاه)").assertIsDisplayed()
        composeRule.onNodeWithText(
            "تصویر فقط روی گوشی پردازش می‌شود، چیزی ارسال نمی‌شود و ممکن است گاهی اشتباه شود."
        ).assertIsDisplayed()

        composeRule.onNodeWithTag("auto_captcha_switch").performClick()
        assertEquals(true, toggledTo)
    }

    @Test
    fun loginScreenContent_displaysAutoFilledBadgeAndSoftFailureMessageWithTalkBackState() {
        composeRule.setContent {
            CullinanTheme(darkTheme = false) {
                LoginScreenContent(
                    username = "",
                    password = "",
                    passwordVisible = false,
                    captchaInput = "4829",
                    captchaBitmap = null,
                    rememberMe = false,
                    isEncryptionAvailable = true,
                    requiresBiometricUnlock = false,
                    biometricUnavailableWhileLocked = false,
                    canQuickFillSavedCredentials = false,
                    isLoading = false,
                    isCaptchaRefreshing = false,
                    errorMessage = null,
                    isCompatibilityModeActive = false,
                    onOpenSettings = {},
                    onOpenDeviceSecuritySettings = {},
                    onClearLockedSavedCredentials = {},
                    onRetryConnection = {},
                    onBiometricUnlockClick = {},
                    onQuickFillSavedCredentials = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onTogglePasswordVisibility = {},
                    onCaptchaChange = {},
                    onRefreshCaptcha = {},
                    onToggleRememberMe = {},
                    onSubmitLogin = {},
                    isSolvingCaptcha = false,
                    isCaptchaAutoFilled = true,
                    captchaSoftMessage = null
                )
            }
        }

        composeRule.onNodeWithTag("captcha_auto_filled_badge").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("پر شده خودکار").assertIsDisplayed()
        val semantics = composeRule.onNodeWithTag("captcha_input").fetchSemanticsNode().config
        assertEquals(
            "کد امنیتی به‌صورت خودکار پر شد، قابل ویرایش",
            semantics[SemanticsProperties.StateDescription]
        )
    }

    @Test
    fun whenAutoCaptchaEnabledButModuleUnavailable_disablesPreferenceAndNeverInvokesSolver() {
        val prefs = createPrefs(autoCaptchaEnabled = true)
        val imgBytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(binaryCaptchaResponse(imgBytes))

        val factoryCalls = AtomicInteger(0)
        val vm = AuthViewModel(
            application = context,
            authRepository = AuthRepository(),
            userPrefs = prefs,
            biometricCapabilityChecker = BiometricCapabilityChecker { true },
            captchaSolverFactory = {
                factoryCalls.incrementAndGet()
                object : CaptchaSolver {
                    override suspend fun solve(imageBytes: ByteArray): CaptchaResult =
                        CaptchaResult.Success("1234")
                }
            },
            ocrModuleAvailabilityChecker = { false }
        )

        awaitCondition { vm.pageState.value.captchaBytes != null && !vm.isSolvingCaptcha.value && !vm.isAutoCaptchaEnabled.value }
        assertEquals(0, factoryCalls.get())
        assertFalse(prefs.isAutoCaptchaEnabled)
        assertFalse(vm.isAutoCaptchaEnabled.value)
        assertEquals("", vm.captchaInput.value)
        assertNull(vm.captchaSoftMessage.value)
        assertTrue(
            AppLogger.recentEntries.value.any {
                it.component == "CaptchaOCR" && it.operation == "AutoSolveSkippedModuleUnavailable"
            }
        )
    }

    @Test
    fun settingsOcrStates_gateSwitchAndExposeManualDownloadButton() {
        var downloadClicks = 0
        composeRule.setContent {
            CullinanTheme(darkTheme = false) {
                SettingsScreenContent(
                    currentThemeMode = AppThemeMode.LIGHT,
                    isEncryptionAvailable = true,
                    canUseBiometric = true,
                    isBiometricEnabled = false,
                    baseUrl = "https://jeton.bums.ac.ir",
                    isCompatibilityModeActive = false,
                    isDevUnlocked = false,
                    versionName = "1.0.0",
                    versionCode = 66,
                    onNavigateBack = {},
                    onSelectTheme = {},
                    onToggleBiometric = {},
                    onOpenDeviceSecuritySettings = {},
                    onRecoveryWipeAndDisableLock = {},
                    onToggleCompatibilityMode = {},
                    onOpenLogs = {},
                    onExportZip = {},
                    onClearLogs = {},
                    onExitDeveloperMode = {},
                    onVersionClick = {},
                    onCheckForUpdates = {},
                    isAutoCaptchaEnabled = false,
                    onToggleAutoCaptcha = {},
                    ocrModuleState = OcrModuleState.NotInstalled,
                    onDownloadOcrModule = { downloadClicks++ }
                )
            }
        }

        composeRule.onNodeWithTag("auto_captcha_switch").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_download_ocr_button")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, downloadClicks)
    }

    @Test
    fun settingsPlayServicesUnavailable_keepsSwitchDisabledAndHidesDownloadButton() {
        composeRule.setContent {
            CullinanTheme(darkTheme = false) {
                SettingsScreenContent(
                    currentThemeMode = AppThemeMode.LIGHT,
                    isEncryptionAvailable = true,
                    canUseBiometric = true,
                    isBiometricEnabled = false,
                    baseUrl = "https://jeton.bums.ac.ir",
                    isCompatibilityModeActive = false,
                    isDevUnlocked = false,
                    versionName = "1.0.0",
                    versionCode = 66,
                    onNavigateBack = {},
                    onSelectTheme = {},
                    onToggleBiometric = {},
                    onOpenDeviceSecuritySettings = {},
                    onRecoveryWipeAndDisableLock = {},
                    onToggleCompatibilityMode = {},
                    onOpenLogs = {},
                    onExportZip = {},
                    onClearLogs = {},
                    onExitDeveloperMode = {},
                    onVersionClick = {},
                    onCheckForUpdates = {},
                    isAutoCaptchaEnabled = true,
                    onToggleAutoCaptcha = {},
                    ocrModuleState = OcrModuleState.PlayServicesUnavailable(
                        connectionResultCode = 9,
                        isUserResolvable = false
                    )
                )
            }
        }

        composeRule.onNodeWithTag("auto_captcha_switch").performScrollTo().assertIsNotEnabled().assertIsOff()
        composeRule.onNodeWithTag("settings_ocr_module_status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_download_ocr_button").assertDoesNotExist()
    }
}
