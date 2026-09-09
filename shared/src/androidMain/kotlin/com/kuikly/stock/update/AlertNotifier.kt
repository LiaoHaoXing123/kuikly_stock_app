package com.kuikly.stock.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.kuikly.stock.data.AlertEngine
import com.kuikly.stock.data.appPrefsGet
import com.kuikly.stock.data.appPrefsSet

object AlertNotifier {
    const val CHANNEL_ID = "stock_alerts"
    private const val KEY_LAST = "alert_last_day"
    private const val PRIORITY = Notification.PRIORITY_HIGH

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "盯盘提醒",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "自选股价格与涨跌幅触达提醒"
        manager.createNotificationChannel(channel)
    }

    fun checkAndNotify(context: Context) {
        val hits = try { AlertEngine.hits() } catch (e: Throwable) { return }
        if (hits.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val today = java.text.SimpleDateFormat("yyyyMMdd").format(java.util.Date())
        val fired = parseFired(appPrefsGet(KEY_LAST))
        var changed = false
        for ((rule, message) in hits) {
            val key = rule.identity
            if (fired[key] == today) continue
            fired[key] = today
            changed = true
            try {
                val builder = if (Build.VERSION.SDK_INT >= 26) {
                    Notification.Builder(context, CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }
                builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("盯盘提醒 · " + rule.name)
                    .setContentText(message)
                    .setStyle(Notification.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setPriority(PRIORITY)
                manager.notify(key.hashCode(), builder.build())
            } catch (e: Throwable) {
            }
        }
        if (changed) appPrefsSet(KEY_LAST, encodeFired(fired))
    }

    private fun parseFired(raw: String?): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        if (raw.isNullOrBlank()) return out
        try {
            val obj = org.json.JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next() as String
                out[k] = obj.optString(k, "")
            }
        } catch (e: Throwable) {
        }
        return out
    }

    private fun encodeFired(map: Map<String, String>): String {
        val obj = org.json.JSONObject()
        for ((k, v) in map) obj.put(k, v)
        return obj.toString()
    }
}
