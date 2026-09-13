package com.kuikly.stock.data

import android.content.Context
import android.util.Log
import com.kuikly.stock.update.AlertNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

actual object DataUpdater {
    private const val TAG = "DataUpdater"
    private const val PREFS = "stock_data_update"
    private const val KEY_UPDATED = "lastDataUpdatedAt"
    private const val KEY_BUILD = "lastDataBuild"
    private const val TMP = "stock.db.download"
    private const val MIN_DB_BYTES = 1_000_000L

    actual suspend fun refreshNow(): Boolean {
        val ctx = stockDbContext() ?: throw IllegalStateException("StockDb 未初始化")
        return withContext(Dispatchers.IO) {
            try { AlertNotifier.checkAndNotify(ctx) } catch (_: Throwable) { }
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ver = httpGet(STOCK_DATA_BASE + "/version.json")
                ?: throw IllegalStateException("version.json 不可达")
            val json = JSONObject(ver)
            val remoteUpdated = json.optString("updated_at", "")
            val remoteBuild = json.optLong("build", 0L)
            val remoteSha = json.optString("sha256", "")
            val localUpdated = prefs.getString(KEY_UPDATED, "") ?: ""
            val localBuild = when (val v = prefs.all[KEY_BUILD]) {
                is Long -> v
                is Int -> v.toLong()
                is Double -> v.toLong()
                else -> 0L
            }
            val newer = remoteUpdated.isNotBlank() &&
                (remoteUpdated > localUpdated || remoteBuild > localBuild)
            if (!newer) {
                Log.i(TAG, "无新数据 (local=" + localUpdated + "/" + localBuild + " remote=" + remoteUpdated + "/" + remoteBuild + ")")
                return@withContext false
            }
            val tmp = File(ctx.filesDir, TMP)
            if (!downloadTo(STOCK_DATA_BASE + "/stock.db", tmp)) {
                throw IllegalStateException("stock.db 下载失败")
            }
            if (remoteSha.isNotBlank()) {
                val actual = sha256Of(tmp)
                if (!actual.equals(remoteSha, ignoreCase = true)) {
                    tmp.delete()
                    throw IllegalStateException("下载库 sha256 校验失败")
                }
            }
            val ok = StockDb.refreshFromFile(tmp.absolutePath)
            if (ok) {
                prefs.edit().putString(KEY_UPDATED, remoteUpdated).putLong(KEY_BUILD, remoteBuild).apply()
            }
            Log.i(TAG, "refreshNow updated=" + ok + " size=" + tmp.length())
            ok
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "kuikly-stock")
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            Log.e(TAG, "httpGet failed: " + url + " " + e.message)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadTo(url: String, dest: File): Boolean {        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.setRequestProperty("User-Agent", "kuikly-stock")
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                if (dest.length() < MIN_DB_BYTES) {
                    Log.w(TAG, "downloadTo 文件过小(" + dest.length() + "B)")
                    dest.delete()
                    return false
                }
                true
            } else {
                Log.w(TAG, "downloadTo HTTP " + code + ": " + url)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadTo failed: " + url + " " + e.message, e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
