package com.example

import com.example.data.repository.UpdateRepository
import com.example.ui.viewmodel.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRepositoryUnitTest {

    private lateinit var repository: UpdateRepository
    private lateinit var mockWebServer: MockWebServer
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()
        repository = UpdateRepository()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun testDefaultRepositoryConfigurationPointsToAZangooei() {
        assertEquals("a-zangooei", UpdateRepository.GITHUB_OWNER)
        assertEquals("kalinan_bums_client", UpdateRepository.GITHUB_REPO)
        assertEquals(
            "https://api.github.com/repos/a-zangooei/kalinan_bums_client/releases/latest",
            UpdateRepository.RELEASES_API_URL
        )
        assertEquals(
            "https://github.com/a-zangooei/kalinan_bums_client/releases/latest",
            UpdateRepository.FALLBACK_RELEASE_WEB_URL
        )
    }

    @Test
    fun testIsNewerVersion() {
        assertTrue(repository.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(repository.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(repository.isNewerVersion("v1.1.0", "1.0.5"))
        assertTrue(repository.isNewerVersion("v2.0.0", "1.9.9"))
        assertTrue(repository.isNewerVersion("1.0.0.1", "1.0.0"))

        assertFalse(repository.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(repository.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(repository.isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(repository.isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test
    fun testExtractChangelogSnippetWithPersianHeader() {
        val sampleReleaseBody = """
            ## 🍱 کالینان (Kalinan) - نسخه v1.0.1
            کلاینت مدرن، امن و متن‌باز سامانه تغذیه دانشگاه علوم پزشکی بیرجند

            ### 📝 تغییرات این نسخه
            • فعال‌سازی رزرو شام و صبحانه
            • حذف روزهای تعطیل پنجشنبه و جمعه
            • رفع خطای انقضای سشن کاری

            ---

            ### 📲 دانلود مستقیم فایل نصبی (بدون نیاز به آنزیپ)
            فایل‌ها با پسوند apk پیوست شده‌اند.
        """.trimIndent()

        val snippet = repository.extractChangelogSnippet(sampleReleaseBody)

        assertTrue(snippet.contains("فعال‌سازی رزرو شام و صبحانه"))
        assertTrue(snippet.contains("حذف روزهای تعطیل پنجشنبه و جمعه"))
        assertTrue(snippet.contains("رفع خطای انقضای سشن کاری"))
        assertFalse(snippet.contains("دانلود مستقیم فایل نصبی"))
    }

    @Test
    fun testExtractChangelogFallbackOnPlainBulletPoints() {
        val plainBody = """
            Release Notes:
            • Security fixes
            • Better network error handling
        """.trimIndent()

        val snippet = repository.extractChangelogSnippet(plainBody)
        assertTrue(snippet.contains("Security fixes"))
    }

    @Test
    fun testCheckForUpdateParsesLatestReleaseAndPrefersReleaseApk() = runTest {
        val releaseJson = """
            {
              "tag_name": "v1.2.0",
              "published_at": "2026-11-20T10:00:00Z",
              "body": "### 📝 تغییرات این نسخه\n• بهبود تشخیص کد امنیتی\n• رفع خطای نشست",
              "assets": [
                {
                  "name": "Kalinan-v1.2.0-debug.apk",
                  "browser_download_url": "https://example.com/debug.apk"
                },
                {
                  "name": "Kalinan-v1.2.0-release.apk",
                  "browser_download_url": "https://example.com/Kalinan-v1.2.0-release.apk"
                }
              ]
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))

        val repo = UpdateRepository(
            client = OkHttpClient(),
            releasesApiUrl = mockWebServer.url("/repos/a-zangooei/kalinan_bums_client/releases/latest").toString()
        )

        val result = repo.checkForUpdate(currentVersionName = "1.0.0")
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertTrue(info.hasPublishedRelease)
        assertTrue(info.isUpdateAvailable)
        assertEquals("1.2.0", info.latestVersionName)
        assertEquals("1.0.0", info.currentVersionName)
        assertEquals("https://example.com/Kalinan-v1.2.0-release.apk", info.downloadUrl)
        assertTrue(info.changelogSnippet.contains("بهبود تشخیص کد امنیتی"))
    }

    @Test
    fun testCheckForUpdateReturnsNoPublishedReleaseOnHttp404() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        val repo = UpdateRepository(
            client = OkHttpClient(),
            releasesApiUrl = mockWebServer.url("/repos/a-zangooei/kalinan_bums_client/releases/latest").toString()
        )

        val result = repo.checkForUpdate(currentVersionName = "1.0.0")
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertFalse(info.hasPublishedRelease)
        assertFalse(info.isUpdateAvailable)
        assertEquals("1.0.0", info.currentVersionName)
    }

    @Test
    fun testCheckForUpdateReportsRateLimitOnHttp403() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))

        val repo = UpdateRepository(
            client = OkHttpClient(),
            releasesApiUrl = mockWebServer.url("/repos/a-zangooei/kalinan_bums_client/releases/latest").toString()
        )

        val result = repo.checkForUpdate(currentVersionName = "1.0.0")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("محدودیت موقت") == true)
    }

    @Test
    fun testUpdateViewModelSilentOnAuto404AndExplicitOnManual404() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val repo = UpdateRepository(
            client = OkHttpClient(),
            releasesApiUrl = mockWebServer.url("/repos/a-zangooei/kalinan_bums_client/releases/latest").toString()
        )

        val viewModel = UpdateViewModel(updateRepository = repo)
        // Wait for auto check from init
        var attempts = 0
        while (viewModel.isChecking.value && attempts < 20) {
            Thread.sleep(25)
            attempts++
        }
        assertNull(viewModel.updateInfo.value)
        assertNull(viewModel.userMessage.value)

        // Trigger manual check
        viewModel.checkForUpdates(isManual = true)
        attempts = 0
        while ((viewModel.isChecking.value || viewModel.userMessage.value == null) && attempts < 20) {
            Thread.sleep(25)
            attempts++
        }
        assertNull(viewModel.updateInfo.value)
        assertTrue(viewModel.userMessage.value?.contains("هنوز هیچ نسخه رسمی در مخزن گیت‌هاب منتشر نشده است") == true)
    }
}
