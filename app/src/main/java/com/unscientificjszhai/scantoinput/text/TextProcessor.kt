package com.unscientificjszhai.scantoinput.text

import android.icu.text.BreakIterator
import com.unscientificjszhai.scantoinput.actions.QuickAction

/**
 * 文本处理类，负责将原始扫码文本转换为 [TextProcessingResult]。
 */
object TextProcessor {

    /**
     * 判断文本是否为可显示文本。
     *
     * @param text 原始文本。
     * @return 如果是可显示文本返回 true，否则返回 false。
     */
    fun isDisplayableText(text: String?): Boolean {
        return !text.isNullOrEmpty()
    }

    /**
     * 处理原始文本。
     *
     * @param rawText 原始文本。
     * @return 处理结果。
     */
    fun process(rawText: String?): TextProcessingResult {
        if (!isDisplayableText(rawText)) {
            return TextProcessingResult.NonText
        }

        val text = rawText!!
        val quickAction = detectQuickAction(text)

        return if (quickAction != null) {
            // 命中特定模式，不进行普通拆词
            TextProcessingResult.Success(listOf(text), quickAction)
        } else {
            // 未命中，进行普通拆词
            TextProcessingResult.Success(tokenize(text))
        }
    }

    /**
     * 检测特定模式。
     *
     * @param text 文本。
     * @return 匹配的快速操作，未匹配返回 null。
     */
    fun detectQuickAction(text: String): QuickAction? {
        // 1. Wi-Fi
        if (text.startsWith("WIFI:", ignoreCase = true)) {
            val ssid = Regex("S:([^;]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            if (ssid != null) {
                val password = Regex("P:([^;]*)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                val encryption = Regex("T:([^;]*)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                return QuickAction.Wifi(text, ssid, password, encryption)
            }
        }

        // 2. vCard
        if (text.contains("BEGIN:VCARD", ignoreCase = true) && 
            text.contains("END:VCARD", ignoreCase = true)) {
            return QuickAction.VCard(text)
        }

        // 3. Calendar Event
        if (text.contains("BEGIN:VEVENT", ignoreCase = true) && 
            text.contains("END:VEVENT", ignoreCase = true)) {
            return QuickAction.CalendarEvent(text)
        }

        // 4. URL
        val urlRegex = Regex("^(https?://|www\\.)[\\w-.]+(\\.[a-zA-Z]{2,})?(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE)
        if (urlRegex.find(text) != null) {
            return QuickAction.Url(text)
        }

        // 5. Email
        if (text.startsWith("mailto:", ignoreCase = true)) {
            val address = text.substringAfter("mailto:").substringBefore("?")
            return QuickAction.Email(text, address)
        }
        val emailRegex = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
        if (emailRegex.matches(text)) {
            return QuickAction.Email(text, text)
        }

        // 6. Phone
        if (text.startsWith("tel:", ignoreCase = true)) {
            val number = text.substringAfter("tel:")
            return QuickAction.Phone(text, number)
        }
        val phoneRegex = Regex("^\\+?[0-9]{7,15}$")
        if (phoneRegex.matches(text)) {
            return QuickAction.Phone(text, text)
        }

        // 7. SMS
        if (text.startsWith("smsto:", ignoreCase = true) || text.startsWith("sms:", ignoreCase = true)) {
            val parts = text.split(":")
            val number = parts.getOrNull(1) ?: ""
            val body = if (parts.size > 2) text.substringAfter("${parts[0]}:${parts[1]}:") else null
            return QuickAction.Sms(text, number, body)
        }

        // 8. Geo
        if (text.startsWith("geo:", ignoreCase = true)) {
            val query = text.substringAfter("geo:")
            return QuickAction.Geo(text, query)
        }

        return null
    }

    /**
     * 普通拆词。
     *
     * @param text 文本。
     * @return token 列表。
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val boundary = BreakIterator.getWordInstance()
        boundary.setText(text)

        var start = boundary.first()
        var end = boundary.next()

        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end)
            
            // 处理合并连续空格和换行
            if (word.isBlank()) {
                if (tokens.isNotEmpty() && tokens.last().isBlank()) {
                    tokens[tokens.size - 1] = tokens.last() + word
                } else {
                    tokens.add(word)
                }
            } else {
                tokens.add(word)
            }

            start = end
            end = boundary.next()
        }

        return tokens
    }
}
