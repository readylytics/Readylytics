package app.readylytics.health.core.database.data.security

import kotlinx.coroutines.CancellationException
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun isPlaintextDatabase(dbFile: File): Boolean {
    if (!dbFile.exists()) return false
    return try {
        DataInputStream(dbFile.inputStream()).use { stream ->
            val header = ByteArray(16)
            stream.readFully(header)
            header.contentEquals("SQLite format 3\u0000".encodeToByteArray())
        }
    } catch (_: EOFException) {
        false
    }
}

/** The source (including committed WAL data) remains authoritative until atomic replacement. */
internal class PlaintextDatabaseExporter {
    fun export(dbFile: File, rawKey: ByteArray, beforeReplace: () -> Unit) {
        val target = File(dbFile.parentFile, "${dbFile.name}.cipher_tmp")
        deleteTarget(target)
        val password = "x'${rawKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) }}'".encodeToByteArray()
        try {
            SQLiteDatabase.openOrCreateDatabase(
                target,
                password,
                null,
                null,
                CIPHER_COMPATIBILITY_HOOK,
            ).use { database ->
                exportAndValidate(database, dbFile)
            }
            // All native handles are closed before cancellation is checked or the source is replaced.
            beforeReplace()
            Files.move(
                target.toPath(),
                dbFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        } catch (e: CancellationException) {
            deleteTarget(target)
            throw e
        } catch (e: Exception) {
            deleteTarget(target)
            throw SqlCipherKeyManager.MigrationException("SQLCipher migration failed", e)
        } finally {
            password.fill(0)
        }
    }

    private fun exportAndValidate(database: SQLiteDatabase, source: File) {
        database.execSQL("ATTACH DATABASE ? AS plaintext KEY ''", arrayOf(source.absolutePath))
        // Leave the source's journal mode alone: ATTACH reads its committed WAL snapshot.
        // Changing it to DELETE would fail when a producer still has a source handle open.
        val version = database.rawQuery("PRAGMA plaintext.user_version", emptyArray<String>()).use { cursor ->
            check(cursor.moveToFirst()) { "Plaintext database has no user_version" }
            cursor.getInt(0)
        }
        database.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
        database.version = version // sqlcipher_export does not copy user_version.
        database.rawExecSQL("DETACH DATABASE plaintext")
        database.rawQuery("PRAGMA quick_check", emptyArray<String>()).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Encrypted export validation failed" }
        }
    }

    private fun deleteTarget(target: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = File("${target.absolutePath}$suffix")
            check(!file.exists() || file.delete()) { "Cannot discard incomplete encrypted export" }
        }
    }
}
