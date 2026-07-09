package app.readylytics.health.data.logcat

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import app.readylytics.health.util.SecureFileLogSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogcatCaptureStoreImplTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var cacheDir: File
    private lateinit var applicationInfo: ApplicationInfo

    @Before
    fun setUp() {
        mockContext = mockk()
        cacheDir = tempFolder.newFolder("cache")
        applicationInfo = ApplicationInfo().apply { flags = 0 }
        every { mockContext.cacheDir } returns cacheDir
        every { mockContext.applicationInfo } returns applicationInfo

        // Mock android.util.Log to prevent RuntimeException during JVM tests
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.w(any(), any() as String, any()) } returns 0
        every { Log.e(any(), any() as String) } returns 0
        every { Log.e(any(), any() as String, any()) } returns 0
        every { Log.getStackTraceString(any()) } answers { firstArg<Throwable>().stackTraceToString() }
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun testCaptureReadsDecryptedLogs() =
        runBlocking {
            // Populate local logs using SecureFileLogSink
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )
            sink.log(
                level = app.readylytics.health.domain.util.LogLevel.INFO,
                tag = "TestTag",
                message = "Direct Local Log Message",
                throwable = null,
                context =
                    app.readylytics.health.domain.util
                        .LogContext(),
            )

            val captureStore = LogcatCaptureStoreImpl(mockContext, sink)
            val output = captureStore.capture(10)

            assertTrue(output != null && output.contains("Direct Local Log Message"))
        }

    @Test
    fun testDebugBuildFallsBackToLogcatReader() =
        runBlocking {
            applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )

            val captureStore = LogcatCaptureStoreImpl(mockContext, sink)
            captureStore.debugLogReader = { "debug log output" }

            val output = captureStore.capture(10)

            assertTrue(output == "debug log output")
        }
}
