package com.unscientificjszhai.scantoinput.text

import com.unscientificjszhai.scantoinput.actions.QuickAction
import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** 不依赖 Android 的文本有效性、模式识别及分词后处理规则。 */
object TextProcessingRules {
    private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val phonePattern = Regex("\\+?[0-9]{7,15}")
    private val reservedSchemes = setOf("http", "https", "mailto", "tel", "geo", "sms", "smsto", "file", "content")

    /**
     * 判断文本是否具有完整 UTF-16 且不含非空白控制字符。
     * @param text 待判断原文。
     * @return 非空且可以原样显示时为 true。
     */
    fun isDisplayableText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 == text.length) return false
                if (!Character.isLowSurrogate(text[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(char)) return false
                if (Character.getType(char) == Character.CONTROL.toInt() &&
                    !isWhitespaceCodePoint(char.code)
                ) return false
                index++
            }
        }
        return true
    }

    /**
     * 判断稳定的 Unicode White_Space 集合。
     * @param codePoint Unicode 码点。
     * @return 是否是允许的空白码点。
     */
    fun isWhitespaceCodePoint(codePoint: Int): Boolean = when (codePoint) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /**
     * 将有效原文识别为全文动作或普通 token。
     * @param rawText 扫描原文。
     * @param tokenize 普通文本的分词适配函数。
     * @return 不可显示标记或保留原文的处理结果。
     */
    fun process(rawText: String?, tokenize: (String) -> List<String>): TextProcessingResult {
        if (rawText == null) return TextProcessingResult.NonText
        if (!isDisplayableText(rawText)) return TextProcessingResult.NonText
        val text = rawText
        val action = detectQuickAction(text)
        return TextProcessingResult.Success(if (action == null) tokenize(text) else listOf(text), action)
    }

    /**
     * 按既有优先级识别结构化文本或完整 URI。
     * @param text 原文。
     * @return 可用的动作；普通文本返回 null。
     */
    fun detectQuickAction(text: String): QuickAction? {
        if (!isDisplayableText(text)) return null
        if (text.startsWith("WIFI:", true)) return QuickAction.Wifi(text)
        if (text.contains("BEGIN:VCARD", true) && text.contains("END:VCARD", true)) {
            return QuickAction.VCard(text)
        }
        if (text.contains("BEGIN:VEVENT", true)) return QuickAction.CalendarEvent(text)
        // 保留既有冒号正文短信格式，其正文允许空白及额外冒号。
        if (text.startsWith("smsto:", true) || text.startsWith("sms:", true)) {
            val content = text.substring(text.indexOf(':') + 1)
            val separator = content.indexOf(':')
            return if (separator < 0) QuickAction.Sms(text, content, null)
            else QuickAction.Sms(text, content.substring(0, separator), content.substring(separator + 1))
        }
        val normalized = normalizeActionUri(text) ?: return null
        return when (normalized.substringBefore(':')) {
            "mailto" -> QuickAction.Email(text, normalized.substring(7).substringBefore('?'))
            "tel" -> QuickAction.Phone(text, normalized.substring(4))
            "geo" -> QuickAction.Geo(text, normalized.substring(4))
            else -> QuickAction.Url(text)
        }
    }

    /**
     * 校验完整 URI，保留全部内容，只补裸地址的 scheme 并将 scheme 转为小写。
     * @param rawText 原始 URI、www 地址、裸邮箱或裸电话。
     * @return 合法的规范 scheme URI；非法内容返回 null。
     */
    fun normalizeActionUri(rawText: String): String? {
        if (!isDisplayableText(rawText)) return null
        if (rawText.codePoints().anyMatch { isWhitespaceCodePoint(it) }) {
            return null
        }
        val text = when {
            rawText.startsWith("www.", true) -> "http://$rawText"
            emailPattern.matches(rawText) -> "mailto:$rawText"
            phonePattern.matches(rawText) -> "tel:$rawText"
            else -> rawText
        }
        val prefix = schemePattern.find(text)?.value ?: return null
        val scheme = prefix.dropLast(1).lowercase(Locale.ROOT)
        if (scheme == "file" || scheme == "content") return null
        val rest = text.substring(prefix.length)
        val normalized = "$scheme:$rest"
        if (scheme !in reservedSchemes && (rest.isEmpty() || rest == "//")) return normalized
        return try {
            val uri = URI(normalized)
            if (scheme == "http" || scheme == "https") {
                if (!hasValidServer(uri)) return null
            }
            normalized
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 在仅用于校验的副本中转换国际化主机，再校验服务器 authority。
     * @param uri 已通过整体 URI 语法的地址。
     * @return 是否具有有效主机和端口。
     * @throws URISyntaxException 服务器 authority 不符合 URI 语法时抛出。
     * @throws IllegalArgumentException 国际化域名不合法时抛出。
     */
    private fun hasValidServer(uri: URI): Boolean {
        val authority = uri.rawAuthority ?: return false
        val userInfoEnd = authority.lastIndexOf('@') + 1
        val server = authority.substring(userInfoEnd)
        val hostEnd = if (server.startsWith('[')) server.indexOf(']') + 1 else server.indexOf(':').let {
            if (it < 0) server.length else it
        }
        val host = server.substring(0, hostEnd)
        if (host.isEmpty()) return false
        val asciiHost = if (host.startsWith('[')) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        val asciiAuthority = authority.substring(0, userInfoEnd) + asciiHost + server.substring(hostEnd)
        val checked = URI("${uri.scheme}://$asciiAuthority/").parseServerAuthority()
        val portSuffix = server.substring(hostEnd)
        if (portSuffix == ":") return false
        return checked.port <= 65535
    }

    /**
     * 按字素边界独立标点、合并空白并应用 ICU 词边界。
     * @param text 原文。
     * @param wordBoundaries ICU 词边界的 UTF-16 偏移。
     * @param graphemeBoundaries 完整且递增、含首尾偏移的字素边界。
     * @return 拼接后与原文完全相同的 token 列表。
     */
    fun tokenize(text: String, wordBoundaries: Set<Int>, graphemeBoundaries: List<Int>): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        var tokenStart = 0
        var previousKind = -1
        for (index in 0 until graphemeBoundaries.lastIndex) {
            val start = graphemeBoundaries[index]
            val kind = tokenKind(text.codePointAt(start))
            if (start > 0 && (kind != previousKind || kind == 2 || (kind == 0 && start in wordBoundaries))) {
                tokens.add(text.substring(tokenStart, start))
                tokenStart = start
            }
            previousKind = kind
        }
        tokens.add(text.substring(tokenStart))
        return tokens
    }

    /**
     * 分类字素首码点，标点字素整体保留。
     * @param codePoint 字素首码点。
     * @return 普通内容为 0，空白为 1，标点为 2。
     */
    private fun tokenKind(codePoint: Int): Int {
        if (isWhitespaceCodePoint(codePoint)) return 1
        return when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> 2
            else -> 0
        }
    }

    /**
     * 联系人原有核心字段；空匹配和未匹配分别保留为空串与 null。
     * @property name 首个 FN 字段。
     * @property phone 首个 TEL 字段。
     */
    data class VCardFields(val name: String?, val phone: String?)

    /**
     * 原样保留已有的未锚定、忽略大小写首次匹配规则，不扩展 vCard 语义。
     * @param text 完整原文。
     * @return 经原有 trim 处理的姓名和电话。
     */
    fun extractVCardFields(text: String): VCardFields = VCardFields(
        Regex("FN:(.*)", RegexOption.IGNORE_CASE).find(text)?.let { it.groupValues[1].trim() },
        Regex("TEL(?:;[^:]*)?:(.*)", RegexOption.IGNORE_CASE).find(text)?.let { it.groupValues[1].trim() }
    )
}
