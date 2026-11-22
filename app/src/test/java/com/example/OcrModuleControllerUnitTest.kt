package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.captcha.solver.OcrDownloadProgress
import com.example.data.captcha.solver.OcrModuleClient
import com.example.data.captcha.solver.OcrModuleController
import com.example.data.captcha.solver.OcrModuleDownloadOutcome
import com.example.data.captcha.solver.OcrModuleState
import com.example.data.captcha.solver.PlayServicesAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrModuleControllerUnitTest {

    private lateinit var context: Application
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        OcrModuleController.resetForTest()
    }

    @After
    fun tearDown() {
        OcrModuleController.resetForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun whenPlayServicesMissing_isModuleReadyReturnsFalseAndNeverTriggersDownload() = runBlocking {
        val installCalls = AtomicInteger(0)
        OcrModuleController.setClientFactoryForTest {
            object : OcrModuleClient {
                override fun checkPlayServicesAvailability(): PlayServicesAvailability =
                    PlayServicesAvailability.Unavailable(connectionResultCode = 9, isUserResolvable = false)

                override suspend fun areModulesAvailable(): Boolean = false

                override suspend fun installModule(onProgress: (OcrDownloadProgress) -> Unit): OcrModuleDownloadOutcome {
                    installCalls.incrementAndGet()
                    return OcrModuleDownloadOutcome.Completed
                }
            }
        }

        val ready = OcrModuleController.isModuleReadyForSolve(context)
        assertFalse(ready)
        assertEquals(0, installCalls.get())
        assertTrue(OcrModuleController.state.value is OcrModuleState.PlayServicesUnavailable)
    }

    @Test
    fun whenModuleNotInstalled_isModuleReadyReturnsFalseWithoutDownloading() = runBlocking {
        val installCalls = AtomicInteger(0)
        OcrModuleController.setClientFactoryForTest {
            object : OcrModuleClient {
                override fun checkPlayServicesAvailability(): PlayServicesAvailability =
                    PlayServicesAvailability.Available

                override suspend fun areModulesAvailable(): Boolean = false

                override suspend fun installModule(onProgress: (OcrDownloadProgress) -> Unit): OcrModuleDownloadOutcome {
                    installCalls.incrementAndGet()
                    return OcrModuleDownloadOutcome.Completed
                }
            }
        }

        val ready = OcrModuleController.isModuleReadyForSolve(context)
        assertFalse(ready)
        assertEquals(0, installCalls.get())
        assertEquals(OcrModuleState.NotInstalled, OcrModuleController.state.value)
    }

    @Test
    fun startManualDownload_reportsProgressAndTransitionsToReady() = runBlocking {
        var installed = false
        val gate = CompletableDeferred<Unit>()
        OcrModuleController.setClientFactoryForTest {
            object : OcrModuleClient {
                override fun checkPlayServicesAvailability(): PlayServicesAvailability =
                    PlayServicesAvailability.Available

                override suspend fun areModulesAvailable(): Boolean = installed

                override suspend fun installModule(onProgress: (OcrDownloadProgress) -> Unit): OcrModuleDownloadOutcome {
                    onProgress(OcrDownloadProgress(progressPercent = 40, bytesDownloaded = 400, totalBytesToDownload = 1000))
                    gate.await()
                    installed = true
                    return OcrModuleDownloadOutcome.Completed
                }
            }
        }

        OcrModuleController.startManualDownload(context)
        val downloading = OcrModuleController.state.value
        assertTrue(downloading is OcrModuleState.Downloading)
        assertEquals(40, (downloading as OcrModuleState.Downloading).progressPercent)
        assertFalse(OcrModuleController.isModuleReadyForSolve(context))

        gate.complete(Unit)
        assertEquals(OcrModuleState.Ready, OcrModuleController.state.value)
        assertTrue(OcrModuleController.isModuleReadyForSolve(context))
    }

    @Test
    fun startManualDownload_mapsInsufficientStorageFailureToFriendlyPersianState() = runBlocking {
        OcrModuleController.setClientFactoryForTest {
            object : OcrModuleClient {
                override fun checkPlayServicesAvailability(): PlayServicesAvailability =
                    PlayServicesAvailability.Available

                override suspend fun areModulesAvailable(): Boolean = false

                override suspend fun installModule(onProgress: (OcrDownloadProgress) -> Unit): OcrModuleDownloadOutcome =
                    OcrModuleDownloadOutcome.Failed(errorCode = 46003, technicalDetail = "INSUFFICIENT_STORAGE")
            }
        }

        OcrModuleController.startManualDownload(context)
        val state = OcrModuleController.state.value
        assertTrue(state is OcrModuleState.Failed)
        val failed = state as OcrModuleState.Failed
        assertEquals(46003, failed.errorCode)
        assertTrue(failed.message.contains("حافظه"))
    }
}
