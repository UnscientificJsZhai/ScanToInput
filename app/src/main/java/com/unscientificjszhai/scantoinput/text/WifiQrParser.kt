package com.unscientificjszhai.scantoinput.text

import java.util.Locale

/** 解析并校验 Wi-Fi 二维码，原样保留解码后的网络字段。 */
object WifiQrParser {
    /** 可交由系统确认界面添加的认证方式。 */
    enum class Security { OPEN, WPA2, WPA3 }

    /** 固定错误类型，不包含敏感原文。 */
    enum class Error { INVALID, UNSUPPORTED }

    /** Wi-Fi 解析结果。 */
    sealed interface Result

    /**
     * 已验证的网络参数。
     * @property ssid 解码后的网络名称。
     * @property password 已验证密码，开放网络为 null。
     * @property security 认证方式。
     * @property hidden 是否隐藏网络。
     */
    data class Success(val ssid: String, val password: String?, val security: Security, val hidden: Boolean) : Result

    /**
     * 可恢复的解析失败。
     * @property error 固定错误类型。
     */
    data class Failure(val error: Error) : Result

    /**
     * 内部解析短路，不携带输入数据。
     * @property error 固定错误类型。
     */
    private class ParseFailure(val error: Error) : RuntimeException()

    /**
     * 解析完整 Wi-Fi 原文。
     * @param rawText 扫描原文。
     * @return 已验证参数或明确失败。
     * @throws ParseFailure 内部校验失败时抛出，并在本方法内转换为 Failure，不传给调用方。
     */
    fun parse(rawText: String): Result = try {
        if (!TextProcessingRules.isDisplayableText(rawText)) throw ParseFailure(Error.INVALID)
        if (!rawText.startsWith("WIFI:", true)) throw ParseFailure(Error.INVALID)
        val fields = linkedMapOf<String, String>()
        var ended = false
        for (field in splitFields(rawText.substring(5))) {
            if (field.isEmpty()) {
                ended = true
                continue
            }
            if (ended) throw ParseFailure(Error.INVALID)
            val separator = field.indexOf(':')
            if (separator < 0) throw ParseFailure(Error.INVALID)
            val key = field.substring(0, separator).uppercase(Locale.ROOT)
            if (key !in setOf("S", "T", "P", "H")) throw ParseFailure(Error.UNSUPPORTED)
            if (fields.containsKey(key)) throw ParseFailure(Error.INVALID)
            fields[key] = decodeValue(field.substring(separator + 1))
        }
        val ssid = fields["S"] ?: throw ParseFailure(Error.INVALID)
        if (ssid.toByteArray(Charsets.UTF_8).size !in 1..32) throw ParseFailure(Error.INVALID)
        val hidden = when (fields["H"]?.lowercase(Locale.ROOT)) {
            null, "false" -> false
            "true" -> true
            else -> throw ParseFailure(Error.INVALID)
        }
        val type = fields["T"].orEmpty().uppercase(Locale.ROOT)
        val security = when {
            type.isEmpty() || type == "NOPASS" -> Security.OPEN
            type == "WPA" || type == "WPA2" -> Security.WPA2
            type == "WPA3" || type == "SAE" -> Security.WPA3
            else -> throw ParseFailure(Error.UNSUPPORTED)
        }
        val password = if (security == Security.OPEN) null else {
            val value = fields["P"] ?: throw ParseFailure(Error.INVALID)
            if (value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw ParseFailure(Error.UNSUPPORTED)
            }
            val minimumLength = if (security == Security.WPA2) 8 else 1
            if (value.length !in minimumLength..63) throw ParseFailure(Error.INVALID)
            if (value.any { it !in ' '..'~' }) throw ParseFailure(Error.INVALID)
            value
        }
        Success(ssid, password, security, hidden)
    } catch (failure: ParseFailure) {
        Failure(failure.error)
    }

    /**
     * 按未转义分号切分字段，保留转义编码供值解码使用。
     * @param text 去掉 WIFI 前缀的字段文本。
     * @return 包括终止空字段的原始字段列表。
     * @throws ParseFailure 转义缺少后继字符时抛出。
     */
    private fun splitFields(text: String): List<String> {
        val fields = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\\' -> {
                    index++
                    if (index == text.length) throw ParseFailure(Error.INVALID)
                }
                ';' -> {
                    fields.add(text.substring(start, index))
                    start = index + 1
                }
            }
            index++
        }
        fields.add(text.substring(start))
        return fields
    }

    /**
     * 解码值及一层可选包装引号，转义引号始终保留为真实字符。
     * @param raw 字段原始值。
     * @return 无损解码值。
     * @throws ParseFailure 未知转义或包装引号不完整时抛出。
     */
    private fun decodeValue(raw: String): String {
        val quoted = raw.startsWith('"')
        val result = StringBuilder()
        var index = if (quoted) 1 else 0
        while (index < raw.length) {
            val char = raw[index]
            when (char) {
                '\\' -> {
                    index++
                    // 字段扫描已保证反斜杠后有字符。
                    val escaped = raw[index]
                    if (escaped !in "\\;:,\"") throw ParseFailure(Error.INVALID)
                    result.append(escaped)
                }
                '"' -> {
                    if (!quoted || index != raw.lastIndex) throw ParseFailure(Error.INVALID)
                    return result.toString()
                }
                else -> result.append(char)
            }
            index++
        }
        if (quoted) throw ParseFailure(Error.INVALID)
        return result.toString()
    }

}
