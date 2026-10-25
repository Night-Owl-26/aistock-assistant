package com.aistock.assistant.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.aistock.assistant.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupInfo(val createdAt: Long, val dbVersion: Int, val counts: Map<String, Int>) {
    val products: Int get() = counts["products"] ?: 0
    val customers: Int get() = counts["customers"] ?: 0
    val suppliers: Int get() = counts["suppliers"] ?: 0
    val sales: Int get() = counts["sales"] ?: 0
    val purchases: Int get() = counts["purchases"] ?: 0
    val totalRows: Int get() = counts.values.sum()
}

class BackupException(val code: String) : Exception(code) {
    companion object {
        const val NOT_A_BACKUP = "NOT_A_BACKUP"
        const val CORRUPT = "CORRUPT"
        const val NEWER_VERSION = "NEWER_VERSION"
        const val TOO_LARGE = "TOO_LARGE"
    }
}

/**
 * Local backup and restore.
 *
 * A backup is a zip holding manifest.json (format, row counts, SHA-256) and data.json (every table).
 * Restore validates the whole file in memory first, then replaces the data inside one transaction:
 * if anything fails, the transaction rolls back and the current data is exactly as it was.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    private val dir: File get() = File(context.filesDir, "backups").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun suggestedName(prefix: String = "aistock-backup"): String =
        prefix + "-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + EXT

    /** Writes a new backup into the app's private backups folder and keeps the newest [KEEP] files. */
    suspend fun create(prefix: String = "aistock-backup"): File = withContext(Dispatchers.IO) {
        val bytes = snapshot()
        val file = File(dir, suggestedName(prefix))
        val tmp = File(dir, file.name + ".tmp")
        tmp.outputStream().use { it.write(bytes) }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        list().drop(KEEP).forEach { it.delete() }
        file
    }

    suspend fun writeTo(out: OutputStream) = withContext(Dispatchers.IO) { out.write(snapshot()) }

    /** Builds the backup bytes from a consistent read of every table. */
    suspend fun snapshot(): ByteArray = withContext(Dispatchers.IO) {
        val data = JSONObject()
        val counts = JSONObject()
        db.withTransaction {
            val sdb = db.openHelper.readableDatabase
            for (table in AppDatabase.TABLES) {
                val rows = JSONArray()
                sdb.query("SELECT * FROM `$table`").use { c ->
                    val names = c.columnNames
                    while (c.moveToNext()) {
                        val o = JSONObject()
                        for (i in names.indices) {
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> o.put(names[i], JSONObject.NULL)
                                Cursor.FIELD_TYPE_INTEGER -> o.put(names[i], c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> o.put(names[i], c.getDouble(i))
                                else -> o.put(names[i], c.getString(i))
                            }
                        }
                        rows.put(o)
                    }
                }
                data.put(table, rows)
                counts.put(table, rows.length())
            }
        }
        val dataBytes = data.toString().toByteArray(Charsets.UTF_8)
        val manifest = JSONObject()
            .put("app", APP_TAG).put("format", FORMAT).put("dbVersion", AppDatabase.VERSION)
            .put("createdAt", System.currentTimeMillis()).put("tables", counts).put("sha256", sha256(dataBytes))
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json")); zip.write(dataBytes); zip.closeEntry()
        }
        bos.toByteArray()
    }

    private class Parsed(val info: BackupInfo, val rows: Map<String, List<ContentValues>>)

    /** Checks a backup completely without changing anything. Throws [BackupException] when it cannot be restored. */
    suspend fun inspect(input: InputStream): BackupInfo = withContext(Dispatchers.IO) { parse(input.readLimited()).info }

    /**
     * Replaces all data with the backup. A safety backup of the current data is written first.
     * Throws [BackupException] when the file is invalid; in that case nothing is changed.
     */
    suspend fun restore(input: InputStream): BackupInfo = withContext(Dispatchers.IO) {
        val parsed = parse(input.readLimited())
        try { create("before-restore") } catch (e: Exception) { /* a missing safety copy must not block a valid restore */ }
        db.withTransaction {
            val sdb = db.openHelper.writableDatabase
            for (table in AppDatabase.TABLES.reversed()) sdb.execSQL("DELETE FROM `$table`")
            for (table in AppDatabase.TABLES) {
                for (cv in parsed.rows[table].orEmpty()) {
                    sdb.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
                }
            }
            // The stock in a linked Excel file belongs to the data before the restore: stop updating it until the owner turns it on again.
            sdb.execSQL("UPDATE excel_links SET enabled = 0")
        }
        db.invalidationTracker.refreshVersionsAsync()
        parsed.info
    }

    private fun InputStream.readLimited(): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw BackupException(BackupException.TOO_LARGE)
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private fun parse(bytes: ByteArray): Parsed {
        var manifestBytes: ByteArray? = null
        var dataBytes: ByteArray? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name) {
                        "manifest.json" -> manifestBytes = zip.readBytes()
                        "data.json" -> dataBytes = zip.readBytes()
                    }
                }
            }
        } catch (e: Exception) {
            throw BackupException(BackupException.NOT_A_BACKUP)
        }
        val mBytes = manifestBytes ?: throw BackupException(BackupException.NOT_A_BACKUP)
        val dBytes = dataBytes ?: throw BackupException(BackupException.NOT_A_BACKUP)
        val manifest = try { JSONObject(String(mBytes, Charsets.UTF_8)) } catch (e: Exception) { throw BackupException(BackupException.NOT_A_BACKUP) }
        if (manifest.optString("app") != APP_TAG || manifest.optInt("format", -1) != FORMAT) throw BackupException(BackupException.NOT_A_BACKUP)
        val dbVersion = manifest.optInt("dbVersion", -1)
        if (dbVersion < 1) throw BackupException(BackupException.CORRUPT)
        if (dbVersion > AppDatabase.VERSION) throw BackupException(BackupException.NEWER_VERSION)
        if (manifest.optString("sha256") != sha256(dBytes)) throw BackupException(BackupException.CORRUPT)

        val data = try { JSONObject(String(dBytes, Charsets.UTF_8)) } catch (e: Exception) { throw BackupException(BackupException.CORRUPT) }
        val expected = manifest.optJSONObject("tables") ?: throw BackupException(BackupException.CORRUPT)
        val sdb = db.openHelper.readableDatabase
        val rows = LinkedHashMap<String, List<ContentValues>>()
        val counts = LinkedHashMap<String, Int>()
        val keys = data.keys().asSequence().toList()
        if (keys.any { it !in AppDatabase.TABLES }) throw BackupException(BackupException.CORRUPT)
        for (table in AppDatabase.TABLES) {
            val arr = data.optJSONArray(table) ?: JSONArray()
            if (expected.optInt(table, 0) != arr.length()) throw BackupException(BackupException.CORRUPT)
            // column name -> must not be null
            val columns = HashMap<String, Boolean>()
            sdb.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                val notNullIdx = c.getColumnIndexOrThrow("notnull")
                val dfltIdx = c.getColumnIndexOrThrow("dflt_value")
                while (c.moveToNext()) columns[c.getString(nameIdx)] = c.getInt(notNullIdx) == 1 && c.isNull(dfltIdx)
            }
            val list = ArrayList<ContentValues>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: throw BackupException(BackupException.CORRUPT)
                val cv = ContentValues()
                for (name in o.keys()) {
                    if (name !in columns) throw BackupException(BackupException.CORRUPT)
                    when (val v = o.get(name)) {
                        JSONObject.NULL -> cv.putNull(name)
                        is Int -> cv.put(name, v.toLong())
                        is Long -> cv.put(name, v)
                        is Boolean -> cv.put(name, if (v) 1L else 0L)
                        is Number -> cv.put(name, v.toDouble())
                        is String -> cv.put(name, v)
                        else -> throw BackupException(BackupException.CORRUPT)
                    }
                }
                for ((name, required) in columns) {
                    if (required && (!cv.containsKey(name) || cv.get(name) == null)) throw BackupException(BackupException.CORRUPT)
                }
                list.add(cv)
            }
            rows[table] = list
            counts[table] = list.size
        }
        return Parsed(BackupInfo(manifest.optLong("createdAt", 0L), dbVersion, counts), rows)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val EXT = ".aistock"
        const val MIME = "application/octet-stream"
        private const val APP_TAG = "AIStock Assistant"
        private const val FORMAT = 1
        private const val KEEP = 15
        private const val MAX_BYTES = 200L * 1024 * 1024
    }
}
