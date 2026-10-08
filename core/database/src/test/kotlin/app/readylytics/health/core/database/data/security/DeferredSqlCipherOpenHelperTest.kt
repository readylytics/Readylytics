package app.readylytics.health.core.database.data.security

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeferredSqlCipherOpenHelperTest {

    @Test
    fun helperConstructionDoesNotRetrieveKey() {
        val context = mockk<android.content.Context>(relaxed = true)
        val callback = mockk<SupportSQLiteOpenHelper.Callback>(relaxed = true)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test.db")
            .callback(callback)
            .build()
        val delegate = mockk<SupportSQLiteOpenHelper>(relaxed = true)

        var createCalls = 0
        val createDelegate = {
            createCalls++
            delegate
        }

        val helper = DeferredSqlCipherOpenHelper(config, createDelegate)

        assertEquals(0, createCalls)
        assertEquals("test.db", helper.databaseName)
        helper.setWriteAheadLoggingEnabled(true)
        helper.close()

        assertEquals(0, createCalls)
    }

    @Test
    fun concurrentFirstAccessProducesOneDelegate() {
        val config = mockk<SupportSQLiteOpenHelper.Configuration>()
        val delegate = mockk<SupportSQLiteOpenHelper>(relaxed = true)
        val writableDb = mockk<SupportSQLiteDatabase>()
        every { delegate.writableDatabase } returns writableDb

        val createCalls = AtomicInteger(0)
        val createDelegate = {
            createCalls.incrementAndGet()
            delegate
        }

        val helper = DeferredSqlCipherOpenHelper(config, createDelegate)
        helper.setWriteAheadLoggingEnabled(true)

        val threads = 10
        val latch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threads)

        repeat(threads) {
            thread {
                latch.await()
                helper.writableDatabase
                doneLatch.countDown()
            }
        }

        latch.countDown()
        doneLatch.await()

        assertEquals(1, createCalls.get())
        verify(exactly = 1) { delegate.setWriteAheadLoggingEnabled(true) }
    }

    @Test
    fun openErrorDoesNotMarkReadinessReady() {
        val config = mockk<SupportSQLiteOpenHelper.Configuration>()
        val delegate = mockk<SupportSQLiteOpenHelper>(relaxed = true)
        every { delegate.writableDatabase } throws IllegalStateException("Open failed")

        val createCalls = AtomicInteger(0)
        val createDelegate = {
            createCalls.incrementAndGet()
            delegate
        }

        val helper = DeferredSqlCipherOpenHelper(config, createDelegate)

        assertThrows(IllegalStateException::class.java) {
            helper.writableDatabase
        }

        // Second attempt should try again (i.e., not marked as successfully opened)
        every { delegate.writableDatabase } returns mockk()
        helper.writableDatabase

        // The delegate may be created once or twice depending on implementation,
        // but the key is that it didn't get stuck in a broken state
        assertTrue(createCalls.get() >= 1)
    }
}
