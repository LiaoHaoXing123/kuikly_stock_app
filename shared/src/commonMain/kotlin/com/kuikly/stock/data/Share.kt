package com.kuikly.stock.data

/**
 * 会话导出所需的平台能力（Android 有完整实现，iOS/JS 为优雅降级的空实现）。
 */

/** 导出头部的可读时间戳，如 "2026-09-08 12:34"；不支持的平台返回空串。 */
internal expect fun exportTimestampString(epochMs: Long): String

/** 调起系统分享面板分享纯文本；调起失败或不支持时返回 false。 */
internal expect fun shareText(title: String, text: String): Boolean

/**
 * 把文本保存为公共下载目录下的文件。
 * @return 成功时返回文件名，失败或不支持时返回 null。
 */
internal expect fun saveTextToDownloads(fileName: String, text: String, mimeType: String): String?
