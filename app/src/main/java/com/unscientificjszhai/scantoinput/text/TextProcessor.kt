package com.unscientificjszhai.scantoinput.text

import android.icu.text.BreakIterator
import com.unscientificjszhai.scantoinput.actions.QuickAction
import java.util.Locale

/** 共享文本规则的 Android ICU 薄适配器。 */
object TextProcessor {
    /**
     * 校验原文，不创建 ICU 对象。
     * @param text 扫描原文。
     * @return 是否可以原样显示。
     */
    fun isDisplayableText(text: String?): Boolean = TextProcessingRules.isDisplayableText(text)

    /**
     * 处理扫描原文。
     * @param rawText 扫描原文。
     * @return 模式识别和分词结果。
     */
    fun process(rawText: String?): TextProcessingResult = TextProcessingRules.process(rawText, ::tokenize)

    /**
     * 识别原文快速操作。
     * @param text 扫描原文。
     * @return 快速操作或 null。
     */
    fun detectQuickAction(text: String): QuickAction? = TextProcessingRules.detectQuickAction(text)

    /**
     * 收集词与字素边界，再由纯规则完成标点和空白处理。
     * @param text 待分词原文。
     * @return 无损 token 列表。
     */
    fun tokenize(text: String): List<String> = TextProcessingRules.tokenize(
        text,
        boundaries(BreakIterator.getWordInstance(Locale.getDefault()), text).toSet(),
        boundaries(BreakIterator.getCharacterInstance(Locale.getDefault()), text)
    )

    /**
     * 收集本次调用独占的 ICU 迭代器边界。
     * @param iterator 词或字素迭代器。
     * @param text 原文。
     * @return 含首尾的 UTF-16 边界列表。
     */
    private fun boundaries(iterator: BreakIterator, text: String): List<Int> {
        iterator.setText(text)
        val result = mutableListOf<Int>()
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            result.add(boundary)
            boundary = iterator.next()
        }
        return result
    }
}
