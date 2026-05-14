package com.unscientificjszhai.scantoinput.text

import com.unscientificjszhai.scantoinput.actions.QuickAction

/**
 * 文本处理结果。
 */
sealed class TextProcessingResult {

    /**
     * 表示扫描结果不是可显示的文本。
     */
    data object NonText : TextProcessingResult()

    /**
     * 表示扫描结果是可显示的文本，且经过处理得到了 token 列表和可选的快速操作。
     *
     * @property tokens 拆词后的 token 列表。
     * @property quickAction 识别出的快速操作，如果没有匹配任何模式则为 null。
     */
    data class Success(
        val tokens: List<String>,
        val quickAction: QuickAction? = null
    ) : TextProcessingResult()
}
