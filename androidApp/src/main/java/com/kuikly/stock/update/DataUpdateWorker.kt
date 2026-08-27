package com.kuikly.stock.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kuikly.stock.data.StockDb
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 后台数据更新 Worker（方案A）
 *
 * 从 GitHub Release（滚动 tag data-latest）下载最新 stock.db + version.json，
 * 比对 updated_at/build 判断是否有新数据；有则下载 stock.db -> 交给 StockDb.refreshFromFile()
 * 原子替换本地只读库。触发方式见 companion.schedule。
 */
class DataUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val remote = fetchVersion(ctx)
            if (remote == null) {
                Log.i(TAG, "version.json 不可达，跳过本次")
                return Result.success()
            }
            val remoteUpdated = remote.optString("updated_at", "")
            val remoteBuild = remote.optInt("build", 0)
            val localUpdated = prefs.getString(KEY_UPDATED, "") ?: ""
            val localBuild = prefs.getInt(KEY_BUILD, 0)

            val newer = remoteUpdated.isNotBlank() &&
                (remoteUpdated > localUpdated || remoteBuild > localBuild)
            if (!newer) {
                Log.i(TAG, "无新数据 (local=" + localUpdated + "/" + localBuild + " remote=" + remoteUpdated + "/" + remoteBuild + ")")
                Result.success()
            } else {
                val ok = downloadSync(ctx)
                if (ok) {
                    prefs.edit().putString(KEY_UPDATED, remoteUpdated).putInt(KEY_BUILD, remoteBuild).apply()
                    Log.i(TAG, "数据已更新 -> " + remoteUpdated)
                    Result.success()
                } else {
                    Log.w(TAG, "下载失败，稍后重试")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "doWork failed: " + (e.message ?: e.toString()), e)
            Result.retry()
        }
    }

    private fun fetchVersion(ctx: Context): JSONObject? {
        val text = httpGet(RELEASE_URL + "/version.json") ?: return null
        return try { JSONObject(text) } catch (e: Exception) { null }
    }

    private fun downloadSync(ctx: Context): Boolean {
        val tmp = File(ctx.filesDir, STOCK_DB_TMP)
        return try {
            if (downloadTo(RELEASE_URL + "/stock.db", tmp)) {
                StockDb.refreshFromFile(tmp.absolutePath)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadSync failed", e)
            false
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

    private fun downloadTo(url: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
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

    companion object {
        private const val TAG = "DataUpdateWorker"
        const val PREFS = "stock_data_update"
        const val KEY_UPDATED = "lastDataUpdatedAt"
        const val KEY_BUILD = "lastDataBuild"
        const val STOCK_DB_TMP = "stock.db.new"

        const val RELEASE_URL = "https://github.com/LiaoHaoXing123/kuikly_stock_app/releases/download/data-latest"

        private const val ONE_OFF = "stock_data_one_off"
        private const val PERIODIC = "stock_data_periodic"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            wm.enqueueUniqueWork(
                ONE_OFF, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DataUpdateWorker>()
                    .setConstraints(constraints)
                    .build()
            )

            val delay = millisUntilNext(16, 30)
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DataUpdateWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        private fun millisUntilNext(hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            return next.timeInMillis - now.timeInMillis
        }
    }
}
