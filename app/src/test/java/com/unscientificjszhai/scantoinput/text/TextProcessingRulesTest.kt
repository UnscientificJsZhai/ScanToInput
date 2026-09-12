package com.unscientificjszhai.scantoinput.text

import com.unscientificjszhai.scantoinput.actions.QuickAction
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** 纯 JVM 文本业务回归，不加载 Android 或 ICU。 */
class TextProcessingRulesTest {
    /** Unicode 空白白名单及边界完整匹配。 */
    @Test
    fun whitespaceSetMatchesUnicodeWhiteSpace() {
        val expected = ((9..13) + listOf(0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A) +
            listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)).toSet()
        for (point in -1..0x3001) assertEquals("U+${point.toString(16)}", point in expected, TextProcessingRules.isWhitespaceCodePoint(point))
        assertTrue(TextProcessingRules.isDisplayableText(expected.map { it.toChar() }.joinToString("")))
    }

    /** 无效 UTF-16 与非空白控制字符均不能进入显示或输入。 */
    @Test
    fun invalidTextNeverInvokesTokenization() {
        val invalid = listOf(null, "", "\u0000", "a\u0001b", "\u0002", "\u007f", "\u0080", "\u009f", "\uD83D", "a\uDC00", "\uD83Dx")
        for (text in invalid) {
            assertFalse(TextProcessingRules.isDisplayableText(text))
            assertEquals(TextProcessingResult.NonText, TextProcessingRules.process(text) { error("非法文本不能分词") })
        }
        assertNull(TextProcessingRules.detectQuickAction("\u0000"))
    }

    /** 合法格式字符、组合字符、代理对与原有空白均保留。 */
    @Test
    fun validUnicodeIsUnmodified() {
        for (text in listOf("中文 العربية", "e\u0301", "👩🏽‍💻", "\u200C\u200D\uFE0F", "\r\n\t ")) {
            assertTrue(TextProcessingRules.isDisplayableText(text))
            val result = TextProcessingRules.process(text) { listOf(it) } as TextProcessingResult.Success
            assertEquals(text, result.tokens.single())
            assertNull(result.quickAction)
        }
    }

    /** 结构化格式优先，并保留错误配置供点击时给出明确错误。 */
    @Test
    fun structuredPatternsKeepWholeTextAndPriority() {
        val cases = listOf(
            "WIFI:S:Net;" to QuickAction.Wifi::class.java,
            "wifi:" to QuickAction.Wifi::class.java,
            "BEGIN:VCARD\nFN:https://example.com\nEND:VCARD" to QuickAction.VCard::class.java,
            "begin:vevent\nSUMMARY:Draft" to QuickAction.CalendarEvent::class.java
        )
        for ((text, type) in cases) {
            val result = TextProcessingRules.process(text) { error("结构化文本不应分词") } as TextProcessingResult.Success
            assertEquals(listOf(text), result.tokens)
            assertTrue(type.isInstance(result.quickAction))
            assertEquals(text, result.quickAction!!.rawText)
        }
        assertNull(TextProcessingRules.detectQuickAction("BEGIN:VCARD\nFN:Incomplete"))
    }

    /** 规范 scheme 时不修改后续 Unicode、编码、查询和片段。 */
    @Test
    fun validUriFormsRetainEveryCharacterAfterScheme() {
        val cases = mapOf(
            "HTTPS://Example.com/Case?q=A%2FB+Z#Frag" to "https://Example.com/Case?q=A%2FB+Z#Frag",
            "http://localhost" to "http://localhost", "http://127.0.0.1:0/" to "http://127.0.0.1:0/",
            "https://[::1]:65535/" to "https://[::1]:65535/", "https://user:pass@例子.测试:443/路径?q=原文" to "https://user:pass@例子.测试:443/路径?q=原文",
            "www.Example.com/a" to "http://www.Example.com/a", "WWW.例子.测试" to "http://WWW.例子.测试",
            "MAILTO:a@example.com?subject=A&body=a%20b&cc=b@example.com" to "mailto:a@example.com?subject=A&body=a%20b&cc=b@example.com",
            "TEL:+12345678901" to "tel:+12345678901", "GEO:0,0?q=Beijing&z=15" to "geo:0,0?q=Beijing&z=15",
            "MyApp:" to "myapp:", "MyApp://" to "myapp://", "MyApp:opaque" to "myapp:opaque", "MyApp://host/path?q=A%2FB#Z" to "myapp://host/path?q=A%2FB#Z",
            "a@example.com" to "mailto:a@example.com", "+12345678901" to "tel:+12345678901", "1234567" to "tel:1234567"
        )
        for ((raw, expected) in cases) assertEquals(raw, expected, TextProcessingRules.normalizeActionUri(raw))
    }

    /** 普通冒号文本、无效 URI 和被排除的本地资源不能触发快速操作。 */
    @Test
    fun invalidWholeUrisRemainOrdinaryText() {
        val cases = listOf("", "\uD800", "Note: buy milk", "app:a\nb", "app:a\u00A0b", "app:bad%", "app:%2", "app:%GG",
            "file:///sdcard/a", "CONTENT://a", "http:", "http://", "https:/path", "http://:80/", "http://host:/",
            "https://host:65536/", "http://host:-1/", "https://host:abc/", "http://[bad]/", "http://bad_host/",
            "http://-bad.com/", "http://a..com/", "http://user@@host/", "1app:thing", "ordinary", "app://host/[bad",
            "mailto:", "tel:", "geo:", "app:bad|thing", "http://host:999999999999/")
        for (text in cases) {
            assertNull(text, TextProcessingRules.normalizeActionUri(text))
            assertNull(text, TextProcessingRules.detectQuickAction(text))
        }
    }

    /** 内置动作保留派生字段，短信原有冒号与查询约定不变。 */
    @Test
    fun builtInActionsKeepTheirFieldsAndSmsConvention() {
        val email = TextProcessingRules.detectQuickAction("MAILTO:a@example.com?subject=Hi") as QuickAction.Email
        assertEquals("a@example.com", email.address)
        assertEquals("+123456789", (TextProcessingRules.detectQuickAction("TEL:+123456789") as QuickAction.Phone).phoneNumber)
        assertEquals("0,0?q=X&z=2", (TextProcessingRules.detectQuickAction("GEO:0,0?q=X&z=2") as QuickAction.Geo).query)
        assertTrue(TextProcessingRules.detectQuickAction("https://example.com") is QuickAction.Url)
        val legacy = TextProcessingRules.detectQuickAction("SMSTO:123:hello world:again") as QuickAction.Sms
        assertEquals("123", legacy.phoneNumber)
        assertEquals("hello world:again", legacy.body)
        val query = TextProcessingRules.detectQuickAction("sms:123?body=hello%20world") as QuickAction.Sms
        assertEquals("123?body=hello%20world", query.phoneNumber)
        assertNull(query.body)
    }

    /** 所有 Unicode 标点类别独立，连续空白合并。 */
    @Test
    fun punctuationAndWhitespaceAreIndependentOfWordBoundaries() {
        val text = "can't foo.bar 1.2_—(x)‘y’！\t\n  z"
        val result = TextProcessingRules.tokenize(text, emptySet(), (0..text.length).toList())
        assertEquals(listOf("can", "'", "t", " ", "foo", ".", "bar", " ", "1", ".", "2", "_", "—", "(", "x", ")", "‘", "y", "’", "！", "\t\n  ", "z"), result)
        assertEquals(text, result.joinToString(""))
        assertEquals(emptyList<String>(), TextProcessingRules.tokenize("", emptySet(), listOf(0)))
    }

    /** 词边界落在字素内部时不拆开 emoji 或组合字素。 */
    @Test
    fun wordBoundariesNeverCutGraphemes() {
        val clusters = listOf("👩🏽‍💻", "e\u0301", "🇨🇳", "1️⃣", "x")
        val boundaries = mutableListOf(0)
        clusters.forEach { boundaries.add(boundaries.last() + it.length) }
        val text = clusters.joinToString("")
        assertEquals(clusters, TextProcessingRules.tokenize(text, (0..text.length).toSet(), boundaries))
        assertEquals(listOf("ab", "cd"), TextProcessingRules.tokenize("abcd", setOf(2), (0..4).toList()))
        assertEquals(listOf("  "), TextProcessingRules.tokenize("  ", setOf(1), listOf(0, 1, 2)))
    }

    /** 固定种子验证拼接恒等和所有输出边界均在输入字素边界上。 */
    @Test
    fun generatedClusterSequencesKeepIdentity() {
        val random = Random(90212)
        val alphabet = listOf("a", "中", "e\u0301", "👨‍👩‍👧", "🇨🇳", "1️⃣", ",", "！", " ", "\t")
        repeat(200) {
            val clusters = List(random.nextInt(1, 50)) { alphabet[random.nextInt(alphabet.size)] }
            val boundaries = mutableListOf(0)
            clusters.forEach { boundaries.add(boundaries.last() + it.length) }
            val text = clusters.joinToString("")
            val result = TextProcessingRules.tokenize(text, (0..text.length).filter { random.nextBoolean() }.toSet(), boundaries)
            assertEquals(text, result.joinToString(""))
            var offset = 0
            for (token in result) {
                offset += token.length
                assertTrue(offset in boundaries)
            }
        }
    }

    /** 联系人保留原有首次未锚定匹配、trim、缺失与空串区别。 */
    @Test
    fun vCardExtractionPreservesExistingSemantics() {
        assertEquals(TextProcessingRules.VCardFields("Alice", "+123"), TextProcessingRules.extractVCardFields("xfn: Alice \nFN:Other\ntel;type=CELL: +123 \nTEL:456"))
        assertEquals(TextProcessingRules.VCardFields(null, null), TextProcessingRules.extractVCardFields("BEGIN:VCARD\nEND:VCARD"))
        assertEquals(TextProcessingRules.VCardFields("", ""), TextProcessingRules.extractVCardFields("FN:   \nTEL:"))
        assertEquals(TextProcessingRules.VCardFields("A", null), TextProcessingRules.extractVCardFields("FN:A"))
        assertEquals(TextProcessingRules.VCardFields(null, "7"), TextProcessingRules.extractVCardFields("TEL:7"))
    }
}
