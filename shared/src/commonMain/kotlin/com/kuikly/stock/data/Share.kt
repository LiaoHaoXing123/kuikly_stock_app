package com.kuikly.stock.data

internal expect fun exportTimestampString(epochMs: Long): String

internal expect fun shareText(title: String, text: String): Boolean

internal expect fun saveTextToDownloads(fileName: String, text: String, mimeType: String): String?
