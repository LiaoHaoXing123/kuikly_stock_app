package com.kuikly.stock.data

internal expect fun appPrefsGet(key: String): String?

internal expect fun appPrefsSet(key: String, value: String)
