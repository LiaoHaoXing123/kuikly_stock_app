// 换肤配置读取器，解析 ini 格式的皮肤配置文件。

package com.kuikly.stock

import android.content.Context
import android.util.Log
import com.kuikly.stock.adapter.execOnSubThread
import java.io.IOException
import java.util.regex.Pattern

typealias OnLoadFinishListener = SkinIniFile.(Boolean) -> Unit

class SkinIniFile(private val context: Context) {

    private val sections: MutableMap<String, Section> = mutableMapOf()

    data class Section(val name: String, val values: MutableMap<String, String> = mutableMapOf())

    @Volatile
    private var hasLoaded = false

    fun load(assetsPath: String, onLoadFinishListener: OnLoadFinishListener? = null) {
        execOnSubThread {
            loadInternal(assetsPath, onLoadFinishListener)
        }
    }

    private fun loadInternal(
        assetsPath: String,
        onLoadFinishListener: OnLoadFinishListener? = null
    ) {
        if (hasLoaded) {
            onLoadFinishListener?.invoke(this, true)
            return
        }
        synchronized(this) {
            if (hasLoaded) {
                onLoadFinishListener?.invoke(this, true)
                return
            }
            try {
                context.assets.open(assetsPath).use { inputStream ->
                    inputStream.bufferedReader().use { bufferReader ->
                        var curSectionName = ""
                        bufferReader.forEachLine {
                            val cleanLineStr = it.trim { char -> char <= ' ' }
                            curSectionName = readEachLine(cleanLineStr, curSectionName)
                        }
                    }
                }
                hasLoaded = true
                onLoadFinishListener?.invoke(this@SkinIniFile, true)
            } catch (e: IOException) {
                Log.e(TAG, "加载 assets 失败 ${e.message}")
                hasLoaded = false
                onLoadFinishListener?.invoke(this@SkinIniFile, false)
            }
        }
    }

    fun get(sectionName: String, sectionKey: String, defaultValue: String? = null): String? {
        val section = sections[sectionName] ?: return defaultValue
        return section.values[sectionKey] ?: defaultValue
    }

    fun getAllSectionsKey(sectionName: String): List<String> {
        return sections[sectionName]?.values?.keys?.toList() ?: emptyList()
    }

    private fun readEachLine(cleanLineStr: String, sectionName: String): String {
        var curSectionName = sectionName
        if (sectionPattern.matcher(cleanLineStr).matches()) {
            curSectionName = cleanLineStr.substring(1, cleanLineStr.length - 1)
            val section = sections[curSectionName] ?: Section(curSectionName)
            sections[section.name] = section
        } else {
            val keyValue = cleanLineStr.split("=", limit = 2)
            if (keyValue.size == 2) {
                val section = sections[curSectionName] ?: Section(curSectionName)
                section.values[keyValue[0]] = keyValue[1]
                sections[section.name] = section
            }
        }
        return curSectionName
    }

    companion object {
        private val sectionPattern = Pattern.compile("^\\[.*\\]$")
        private const val TAG = "SkinIniFile"
    }

}
