package com.unscientificjszhai.scantoinput.scanner

/**
 * 扫码结果。
 */
sealed class ScanResult {
    /**
     * 识别到可显示的文本结果。
     *
     * @property text 原始文本内容。
     */
    data class Text(val text: String) : ScanResult()

    /**
     * 识别到条码，但内容为非文本或不可处理的格式。
     */
    data object NonText : ScanResult()
}
