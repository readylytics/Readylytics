# Production Logging Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a secure, release-only logging architecture that prints sanitized operational logs to Logcat, mirrors them to an encrypted/size-bounded local cache file, and captures them for issue reporting without leaking any sensitive health metrics.

**Architecture:** Extend the pure-Kotlin `DomainLogger` to support a scoped DSL with correlation IDs. Implement an Android `SecureFileLogSink` in the `app` module using Jetpack `EncryptedFile` with a 500 KB limit and 2 rotated backups (max 1.5 MB total). Update the issue capture store to decrypt and read this file, and sanitize all scoring and vitals logs to comply with Google Play health-data policies.

**Tech Stack:** Kotlin, Compose (M3), Room, WorkManager, Jetpack Security Crypto (`androidx.security:security-crypto`), MockK.

---

## Global Constraints

- Never log sensitive biometrics (HRV, RHR, blood pressure, sleep duration, step count, physical measurements).
- Never log calculated outputs (sleep score, readiness score, acute/chronic load ratios, TRIMP, RAS).
- All string interpolation in log statements must use lazy evaluation (`logD(TAG) { "message" }`) to prevent runtime string allocation overhead in release.
- All files created must adhere to the 400-line recommended file size limit.
- Run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` to verify code correctness after each task.

---

## Subsystem File Structure Map

```
app/
 ├── util/
 │    ├── SecureFileLogSink.kt (New)          <-- Encrypted local logger sink
 │    └── SecureLogger.kt                     <-- High-level sanitized logger delegates
 └── data/logcat/
      └── LogcatCaptureStoreImpl.kt           <-- Refactored logcat reader (decrypts prod_logs.txt)
core/model/
 └── src/main/kotlin/app/readylytics/health/domain/util/
      └── AppLog.kt                           <-- DomainLogger interface, DSL scopes, & LogContext
```

---

## Tasks

### Task 1: Scoped Logger API & DSL Builder

Update `AppLog.kt` to introduce `LogLevel`, `LogContext`, and the `ScopedLogger` DSL. This supports log level routing and correlation tracing via explicit correlation IDs (such as sync session IDs).

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt`
- Create: `core/model/src/test/kotlin/app/readylytics/health/domain/util/AppLogTest.kt`

**Interfaces:**
- Consumes: None (Root Task)
- Produces: `LogLevel` enum, `LogContext` class, `DomainLogSink` (modified to accept context), `DomainLogger.scoped` DSL helper.

- [ ] **Step 1: Create Unit Test for Scoped DSL Logger**
  Write a test file `core/model/src/test/kotlin/app/readylytics/health/domain/util/AppLogTest.kt` containing:
  ```kotlin
  package app.readylytics.health.domain.util

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class AppLogTest {
      private class TestLogSink : DomainLogSink {
          val logs = mutableListOf<String>()

          override fun log(
              level: LogLevel,
              tag: String,
              message: String,
              throwable: Throwable?,
              context: LogContext
          ) {
              logs.add("[$level] [$tag] [Session:${context.sessionId ?: "none"}] $message")
          }
      }

      @Test
      fun testScopedLoggerAttachesSessionId() {
          val sink = TestLogSink()
          DomainLogger.installSink(sink)

          DomainLogger.scoped(tag = "SyncTest", correlationId = "sync-1234") {
              info { "Beginning step 1" }
              warn(Exception("Failure")) { "Encountered step 1 delay" }
          }

          assertEquals(2, sink.logs.size)
          assertEquals("[INFO] [SyncTest] [Session:sync-1234] Beginning step 1", sink.logs[0])
          assertEquals("[WARN] [SyncTest] [Session:sync-1234] Encountered step 1 delay", sink.logs[1])
      }
  }
  ```

- [ ] **Step 2: Run test to verify failure**
  Run: `./gradlew :core:model:testDebugUnitTest --tests app.readylytics.health.domain.util.AppLogTest`
  Expected: FAIL (AppLog does not compile with new classes/methods yet)

- [ ] **Step 3: Update `AppLog.kt` code implementation**
  Replace `core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt` with:
  ```kotlin
  package app.readylytics.health.domain.util

  enum class LogLevel { INFO, WARN, ERROR }

  data class LogContext(val sessionId: String? = null)

  interface DomainLogSink {
      fun log(
          level: LogLevel,
          tag: String,
          message: String,
          throwable: Throwable?,
          context: LogContext
      )
  }

  object DomainLogger {
      private object NoOpSink : DomainLogSink {
          override fun log(
              level: LogLevel,
              tag: String,
              message: String,
              throwable: Throwable?,
              context: LogContext
          ) = Unit
      }

      @Volatile
      private var sink: DomainLogSink = NoOpSink

      fun installSink(newSink: DomainLogSink) {
          sink = newSink
      }

      fun log(
          level: LogLevel,
          tag: String,
          throwable: Throwable? = null,
          context: LogContext = LogContext(),
          msg: () -> String
      ) {
          sink.log(level, tag, msg(), throwable, context)
      }
  }

  class ScopedLogger(val tag: String, val context: LogContext) {
      fun info(msg: () -> String) = DomainLogger.log(LogLevel.INFO, tag, context = context, msg = msg)
      fun warn(throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.WARN, tag, throwable, context, msg)
      fun error(throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.ERROR, tag, throwable, context, msg)
  }

  inline fun DomainLogger.scoped(
      tag: String,
      correlationId: String?,
      block: ScopedLogger.() -> Unit
  ) {
      val logger = ScopedLogger(tag, LogContext(correlationId))
      logger.block()
  }

  inline fun logD(
      tag: String,
      msg: () -> String,
  ) {
      DomainLogger.log(LogLevel.INFO, tag, throwable = null, context = LogContext(), msg = msg)
  }

  inline fun logW(
      tag: String,
      throwable: Throwable? = null,
      msg: () -> String,
  ) {
      DomainLogger.log(LogLevel.WARN, tag, throwable, LogContext(), msg)
  }

  inline fun logE(
      tag: String,
      throwable: Throwable? = null,
      msg: () -> String,
  ) {
      DomainLogger.log(LogLevel.ERROR, tag, throwable, LogContext(), msg)
  }
  ```

- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :core:model:testDebugUnitTest --tests app.readylytics.health.domain.util.AppLogTest`
  Expected: PASS

- [ ] **Step 5: Commit changes**
  ```bash
  git add core/model/src/main/kotlin/app/readylytics/health/domain/util/AppLog.kt core/model/src/test/kotlin/app/readylytics/health/domain/util/AppLogTest.kt
  git commit -m "feat: implement LogLevel, LogContext, and ScopedLogger DSL"
  ```

---

### Task 2: Encrypted, Size-Bounded Secure File Logger (`SecureFileLogSink`)

Create `SecureFileLogSink` to write logs to an encrypted internal file with rotation logic.

**Files:**
- Create: `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`

**Interfaces:**
- Consumes: `DomainLogSink` and `LogLevel` from Task 1.
- Produces: `SecureFileLogSink` class writing rotated encrypted files.

- [ ] **Step 1: Write SecureFileLogSink Rotation Test**
  Create `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`:
  ```kotlin
  package app.readylytics.health.util

  import android.content.Context
  import app.readylytics.health.domain.util.LogLevel
  import app.readylytics.health.domain.util.LogContext
  import io.mockk.every
  import io.mockk.mockk
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Rule
  import org.junit.Test
  import org.junit.rules.TemporaryFolder
  import java.io.File

  class SecureFileLogSinkTest {
      @get:Rule
      val tempFolder = TemporaryFolder()

      private lateinit var mockContext: Context
      private lateinit var cacheDir: File

      @Before
      fun setUp() {
          mockContext = mockk()
          cacheDir = tempFolder.newFolder("cache")
          every { mockContext.cacheDir } returns cacheDir
      }

      @Test
      fun testFileRotationCreatesRotatedBackups() {
          // Setup a sink with a tiny max size (100 bytes) to force quick rotation
          val sink = SecureFileLogSink(
              context = mockContext,
              maxFileSize = 100L,
              maxBackups = 2,
              encryptStreams = false // Disable encryption for testing raw content easily
          )

          val longMessage = "This is a very long log message that will exceed the file limit quickly. "
          
          // Write multiple lines to trigger rotation
          for (i in 1..10) {
              sink.log(LogLevel.INFO, "TestTag", "Log entry #$i: $longMessage", null, LogContext("session-1"))
          }

          val logFile = File(cacheDir, "logs/prod_logs.txt")
          val rotatedFile1 = File(cacheDir, "logs/prod_logs.txt.1")
          val rotatedFile2 = File(cacheDir, "logs/prod_logs.txt.2")

          assertTrue("Primary log file must exist", logFile.exists())
          assertTrue("First rotated log file must exist", rotatedFile1.exists())
          assertTrue("Second rotated log file must exist", rotatedFile2.exists())
      }
  }
  ```

- [ ] **Step 2: Run test to verify failure**
  Run: `./gradlew :app:testDebugUnitTest --tests app.readylytics.health.util.SecureFileLogSinkTest`
  Expected: FAIL (SecureFileLogSink does not exist)

- [ ] **Step 3: Implement `SecureFileLogSink.kt`**
  Create `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt`:
  ```kotlin
  package app.readylytics.health.util

  import android.content.Context
  import android.util.Log
  import androidx.security.crypto.EncryptedFile
  import androidx.security.crypto.MasterKeys
  import app.readylytics.health.domain.util.DomainLogSink
  import app.readylytics.health.domain.util.LogLevel
  import app.readylytics.health.domain.util.LogContext
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.asExecutor
  import kotlinx.coroutines.launch
  import java.io.File
  import java.io.FileOutputStream
  import java.io.InputStream
  import java.text.SimpleDateFormat
  import java.util.Date
  import java.util.Locale

  class SecureFileLogSink(
      private val context: Context,
      private val maxFileSize: Long = 500 * 1024L, // 500 KB
      private val maxBackups: Int = 2,
      private val encryptStreams: Boolean = true
  ) : DomainLogSink {

      private val logDirectory = File(context.cacheDir, "logs")
      private val logFile = File(logDirectory, "prod_logs.txt")
      private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
      private val scope = CoroutineScope(SupervisorJob() + writeDispatcher)
      private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

      init {
          if (!logDirectory.exists()) {
              logDirectory.mkdirs()
          }
      }

      override fun log(
          level: LogLevel,
          tag: String,
          message: String,
          throwable: Throwable?,
          context: LogContext
      ) {
          // Log to standard Logcat for developers/debugging in real-time
          val formattedMessage = "[Session:${context.sessionId ?: "none"}] $message"
          when (level) {
              LogLevel.INFO -> Log.i(tag, formattedMessage)
              LogLevel.WARN -> Log.w(tag, formattedMessage, throwable)
              LogLevel.ERROR -> Log.e(tag, formattedMessage, throwable)
          }

          // Offload file writing to serialization coroutine scope
          scope.launch {
              try {
                  writeLogToFile(level, tag, message, throwable, context)
              } catch (e: Exception) {
                  Log.e("SecureFileLogSink", "Failed to write log to file", e)
              }
          }
      }

      private fun writeLogToFile(
          level: LogLevel,
          tag: String,
          message: String,
          throwable: Throwable?,
          logContext: LogContext
      ) {
          checkRotation()

          val timestamp = dateFormat.format(Date())
          val sessionId = logContext.sessionId ?: "none"
          val logLine = "$timestamp [$level] [$tag] [Session:$sessionId] $message" +
                  (throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: "") + "\n"

          if (encryptStreams) {
              writeEncrypted(logLine)
          } else {
              writePlain(logLine)
          }
      }

      private fun writeEncrypted(content: String) {
          val encryptedFile = getEncryptedFile(logFile)
          val tempFile = File(logDirectory, "temp_write.txt")
          
          // EncryptedFile doesn't support append cleanly, so we read, append, and rewrite.
          // Since our max file size is small (500KB), this is fast and safe.
          val existingContent = if (logFile.exists()) {
              try {
                  encryptedFile.openFileInput().use { it.bufferedReader().readText() }
              } catch (e: Exception) {
                  ""
              }
          } else {
              ""
          }

          val updatedContent = existingContent + content

          val tempEncrypted = getEncryptedFile(tempFile)
          if (tempFile.exists()) tempFile.delete()

          tempEncrypted.openFileOutput().use { output ->
              output.write(updatedContent.toByteArray(Charsets.UTF_8))
          }

          if (logFile.exists()) logFile.delete()
          tempFile.renameTo(logFile)
      }

      private fun writePlain(content: String) {
          FileOutputStream(logFile, true).use { output ->
              output.write(content.toByteArray(Charsets.UTF_8))
          }
      }

      private fun getEncryptedFile(file: File): EncryptedFile {
          val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
          return EncryptedFile.Builder(
              file,
              context,
              masterKeyAlias,
              EncryptedFile.FileEncryptionScheme.AES256_GCM_SPEC
          ).build()
      }

      private fun checkRotation() {
          if (!logFile.exists() || logFile.length() < maxFileSize) return

          // Rotate backups
          for (i in maxBackups - 1 downTo 1) {
              val src = File(logDirectory, "prod_logs.txt.$i")
              val dest = File(logDirectory, "prod_logs.txt.${i + 1}")
              if (src.exists()) {
                  if (dest.exists()) dest.delete()
                  src.renameTo(dest)
              }
          }

          val firstBackup = File(logDirectory, "prod_logs.txt.1")
          if (firstBackup.exists()) firstBackup.delete()
          logFile.renameTo(firstBackup)
      }

      // Safe decryption exposure helper for internal diagnostics use
      fun readLogsDecrypted(): String {
          if (!logFile.exists()) return ""
          return try {
              if (encryptStreams) {
                  getEncryptedFile(logFile).openFileInput().use { it.bufferedReader().readText() }
              } else {
                  logFile.readText()
              }
          } catch (e: Exception) {
              "Error reading encrypted log file: ${e.message}"
          }
      }
  }
  ```

- [ ] **Step 4: Run test to verify passing**
  Run: `./gradlew :app:testDebugUnitTest --tests app.readylytics.health.util.SecureFileLogSinkTest`
  Expected: PASS

- [ ] **Step 5: Commit changes**
  ```bash
  git add app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt
  git commit -m "feat: implement SecureFileLogSink with AES encryption & file rotation"
  ```

---

### Task 3: Refactoring LogcatCaptureStoreImpl and IssueReportDialog

Refactor the Logcat capturer to read our new rotated, encrypted log cache instead of the system shell `logcat`.

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/logcat/LogcatCaptureStoreImpl.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/data/logcat/LogcatCaptureStoreImplTest.kt`

**Interfaces:**
- Consumes: `SecureFileLogSink` from Task 2.
- Produces: `LogcatCaptureStore.capture()` reading from local encrypted file logs.

- [ ] **Step 1: Write LogcatCaptureStoreImpl Custom Source Test**
  Check the existing tests in `app/src/test/kotlin/app/readylytics/health/data/logcat/LogcatCaptureStoreImplTest.kt` and modify/write steps to assert that local files are read instead of system commands.
  Update/replace the test:
  ```kotlin
  package app.readylytics.health.data.logcat

  import android.content.Context
  import app.readylytics.health.util.SecureFileLogSink
  import io.mockk.every
  import io.mockk.mockk
  import org.junit.Assert.assertEquals
  import org.junit.Rule
  import org.junit.Test
  import org.junit.rules.TemporaryFolder
  import java.io.File

  class LogcatCaptureStoreImplTest {
      @get:Rule
      val tempFolder = TemporaryFolder()

      @Test
      fun testCaptureReadsDecryptedLogs() {
          val mockContext = mockk<Context>()
          val cacheDir = tempFolder.newFolder("cache")
          every { mockContext.cacheDir } returns cacheDir

          // Populate local logs using SecureFileLogSink
          val sink = SecureFileLogSink(mockContext, encryptStreams = false)
          sink.log(
              level = app.readylytics.health.domain.util.LogLevel.INFO,
              tag = "TestTag",
              message = "Direct Local Log Message",
              throwable = null,
              context = app.readylytics.health.domain.util.LogContext()
          )

          val captureStore = LogcatCaptureStoreImpl(mockContext)
          val output = captureStore.capture(10)

          assertTrue(output != null && output.contains("Direct Local Log Message"))
      }
  }
  ```

- [ ] **Step 2: Run test to verify failure**
  Run: `./gradlew :app:testDebugUnitTest --tests app.readylytics.health.data.logcat.LogcatCaptureStoreImplTest`
  Expected: FAIL

- [ ] **Step 3: Modify `LogcatCaptureStoreImpl.kt`**
  Update the `capture` implementation in `app/src/main/kotlin/app/readylytics/health/data/logcat/LogcatCaptureStoreImpl.kt`:
  ```kotlin
  package app.readylytics.health.data.logcat

  import android.content.Context
  import app.readylytics.health.domain.logcat.LogcatCaptureStore
  import app.readylytics.health.util.SecureFileLogSink
  import dagger.hilt.android.qualifiers.ApplicationContext
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import java.io.File
  import java.io.IOException
  import javax.inject.Inject
  import javax.inject.Singleton

  @Singleton
  class LogcatCaptureStoreImpl
      @Inject
      constructor(
          @ApplicationContext private val context: Context,
      ) : LogcatCaptureStore {

          override suspend fun capture(durationMinutes: Int): String? =
              withContext(Dispatchers.IO) {
                  try {
                      // Bypassing physical system shell logcat entirely.
                      // Read from our encrypted size-bounded log file instead.
                      val sink = SecureFileLogSink(context)
                      val logs = sink.readLogsDecrypted()
                      if (logs.isBlank()) return@withContext null
                      
                      val file = captureFile()
                      file.parentFile?.mkdirs()
                      file.writeText(logs)
                      logs
                  } catch (e: IOException) {
                      null
                  }
              }

          override fun captureFile(): File = File(File(context.cacheDir, LOGCAT_CAPTURE_DIR), LOGCAT_CAPTURE_FILE)

          companion object {
              const val LOGCAT_CAPTURE_DIR = "logcat_capture"
              const val LOGCAT_CAPTURE_FILE = "logcat_capture.txt"
          }
      }
  ```

- [ ] **Step 4: Run test to verify passing**
  Run: `./gradlew :app:testDebugUnitTest --tests app.readylytics.health.data.logcat.LogcatCaptureStoreImplTest`
  Expected: PASS

- [ ] **Step 5: Commit changes**
  ```bash
  git add app/src/main/kotlin/app/readylytics/health/data/logcat/LogcatCaptureStoreImpl.kt app/src/test/kotlin/app/readylytics/health/data/logcat/LogcatCaptureStoreImplTest.kt
  git commit -m "feat: refactor LogcatCaptureStoreImpl to use decrypted local file logs"
  ```

---

### Task 4: In-App Sink Installation & Static Readiness Checks

Install the new secure file logging sink into `HealthDashboardApplication.kt` for release builds, and update static checks.

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt:120-160`
- Modify: `app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt:40-60`

**Interfaces:**
- Consumes: `SecureFileLogSink` from Task 2.
- Produces: Logging configuration enabled in release builds.

- [ ] **Step 1: Write/Update the Production Readiness test**
  Open `app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt` and locate the `"production code uses centralized logging helpers only"` test block.
  Add `"app/readylytics/health/util/SecureFileLogSink.kt"` and `"app/readylytics/health/util/SecureLogger.kt"` to the `allowedLogFiles` set.
  ```kotlin
          val allowedLogFiles =
              setOf(
                  "app/readylytics/health/domain/util/AppLog.kt",
                  "app/readylytics/health/HealthDashboardApplication.kt",
                  "app/readylytics/health/util/SecureFileLogSink.kt",
                  "app/readylytics/health/util/SecureLogger.kt",
              )
  ```

- [ ] **Step 2: Modify `HealthDashboardApplication.kt` to install SecureFileLogSink in release**
  Modify `installAndroidLogSink()` in `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt`:
  ```kotlin
      private fun installAndroidLogSink() {
          if (BuildConfig.DEBUG) {
              // Standard debug logging directly to logcat
              DomainLogger.installSink(
                  object : DomainLogSink {
                      override fun log(
                          level: LogLevel,
                          tag: String,
                          message: String,
                          throwable: Throwable?,
                          context: LogContext
                      ) {
                          val formatted = "[Session:${context.sessionId ?: "none"}] $message"
                          when (level) {
                              LogLevel.INFO -> Log.d(tag, formatted)
                              LogLevel.WARN -> Log.w(tag, formatted, throwable)
                              LogLevel.ERROR -> Log.e(tag, formatted, throwable)
                          }
                      }
                  }
              )
          } else {
              // Release build secure log file sink (includes sanitized logcat mirroring)
              DomainLogger.installSink(SecureFileLogSink(this))
          }
      }
  ```

- [ ] **Step 3: Run the static checking test**
  Run: `./gradlew :app:testDebugUnitTest --tests app.readylytics.health.ProductionReadinessStaticTest`
  Expected: PASS

- [ ] **Step 4: Commit changes**
  ```bash
  git add app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt
  git commit -m "feat: install SecureFileLogSink in release builds and update static checks"
  ```

---

### Task 5: Vitals and Scoring Log Sanitization

Sanitize all identified health information leaks in sleep and scoring computation files.

**Files:**
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCase.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`

**Interfaces:**
- Consumes: `AppLog.kt` methods from Task 1.
- Produces: Fully sanitized release logs inside computation modules.

- [ ] **Step 1: Sanitize HRV logs in `ComputeSleepMetricsUseCase.kt`**
  Modify line 148 to remove logging raw HRV mean:
  ```kotlin
  // Before:
  logD("ComputeSleepMetrics") { "HRV resolved: samples=${sessionHrvSamples.size}, mean=$currentHrvMean" }
  // After:
  logD("ComputeSleepMetrics") { "HRV resolved: samples=${sessionHrvSamples.size}, hasMean=${currentHrvMean != null}" }
  ```
  
- [ ] **Step 2: Completely remove debugPayload logs from release builds in `ComputeSleepMetricsUseCase.kt`**
  Find the `logD("ScoringDebug")` call around line 466:
  ```kotlin
  // Wrap the debug log so it is only printed/built in debug configurations
  if (BuildConfig.DEBUG) {
      logD("ScoringDebug") { "\n$debugPayload" }
  }
  ```

- [ ] **Step 3: Sanitize TRIMP/RAS logs in `ScoringRepositoryImpl.kt`**
  Find the log block around line 233 and replace it with sanitized operational values:
  ```kotlin
  // Before:
  logD("ScoringRepository") {
      "Result - DailyTrimp: $dailyTrimpRaw, DailyRas: $dailyRas, " +
          "Last6d: $last6DaysRasWorkoutOnly, Total7d: $totalRasWorkoutOnly"
  }
  // After:
  logD("ScoringRepository") {
      "RAS calculation completed: workoutsFound=${workouts.isNotEmpty()}, dailyTrimpPresent=${dailyTrimpRaw != null}"
  }
  ```

- [ ] **Step 4: Verify all codebase tests pass**
  Run: `./gradlew testDebugUnitTest && ./gradlew lintRelease`
  Expected: BUILD SUCCESSFUL (No compilation errors, all unit tests pass, lint is clean)

- [ ] **Step 5: Commit changes**
  ```bash
  git add core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCase.kt core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt
  git commit -m "security: sanitize health and workout metrics in scoring computations"
  ```
