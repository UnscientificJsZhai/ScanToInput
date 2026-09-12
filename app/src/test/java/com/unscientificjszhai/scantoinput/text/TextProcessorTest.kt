package com.unscientificjszhai.scantoinput.text

import com.unscientificjszhai.scantoinput.actions.QuickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实 Android ICU 薄适配及共享规则的回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextProcessorTest {

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testIsDisplayableText() {
        assertTrue(TextProcessor.isDisplayableText("Hello"))
        assertTrue(!TextProcessor.isDisplayableText(""))
        assertTrue(!TextProcessor.isDisplayableText(null))
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectWifi() {
        val text = "WIFI:T:WPA;S:MySSID;P:MyPass;;"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Wifi)
        val wifi = result as QuickAction.Wifi
        assertEquals(text, wifi.rawText)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectUrl() {
        val text = "https://www.google.com"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Url)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectAppDeepLink() {
        val text = "exampleapp://open/item?id=1"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Url)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testIgnoreLocalResourceDeepLink() {
        assertTrue(TextProcessor.detectQuickAction("file:///sdcard/private.txt") == null)
        assertTrue(TextProcessor.detectQuickAction("content://example/items/1") == null)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testTokenizeBasic() {
        val text = "Hello, world!"
        val tokens = TextProcessor.tokenize(text)
        assertEquals(listOf("Hello", ",", " ", "world", "!"), tokens)
        assertEquals(text, tokens.joinToString(""))
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testTokenizeWithWhitespace() {
        val text = "Hello  \n world"
        val tokens = TextProcessor.tokenize(text)
        // 连续空白保留为一个 token。
        assertEquals(listOf("Hello", "  \n ", "world"), tokens)
        assertEquals(text, tokens.joinToString(""))
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testProcessWifi() {
        val text = "WIFI:T:WPA;S:MySSID;P:MyPass;;"
        val result = TextProcessor.process(text)
        assertTrue(result is TextProcessingResult.Success)
        val success = result as TextProcessingResult.Success
        assertEquals(listOf(text), success.tokens)
        assertTrue(success.quickAction is QuickAction.Wifi)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testProcessOrdinaryText() {
        val text = "This is a test."
        val result = TextProcessor.process(text)
        assertTrue(result is TextProcessingResult.Success)
        val success = result as TextProcessingResult.Success
        assertTrue(success.quickAction == null)
        assertEquals(text, success.tokens.joinToString(""))
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectVCard() {
        val text = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.VCard)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectCalendar() {
        val text = "BEGIN:VEVENT\nSUMMARY:Meeting\nEND:VEVENT"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.CalendarEvent)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectEmail() {
        val text = "test@example.com"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Email)
        assertEquals("test@example.com", (result as QuickAction.Email).address)

        val mailto = "mailto:test@example.com?subject=Hi"
        val result2 = TextProcessor.detectQuickAction(mailto)
        assertTrue(result2 is QuickAction.Email)
        assertEquals("test@example.com", (result2 as QuickAction.Email).address)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectPhone() {
        val text = "12345678901"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Phone)
        
        val tel = "tel:+8612345678901"
        val result2 = TextProcessor.detectQuickAction(tel)
        assertTrue(result2 is QuickAction.Phone)
        assertEquals("+8612345678901", (result2 as QuickAction.Phone).phoneNumber)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectSms() {
        val text = "smsto:12345678901:Hello there"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Sms)
        assertEquals("12345678901", (result as QuickAction.Sms).phoneNumber)
        assertEquals("Hello there", (result as QuickAction.Sms).body)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testDetectGeo() {
        val text = "geo:39.9,116.4"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Geo)
        assertEquals("39.9,116.4", (result as QuickAction.Geo).query)
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testTokenizeMixedChineseEnglish() {
        val text = "Hello 你好, world 世界!"
        val tokens = TextProcessor.tokenize(text)
        assertEquals(text, tokens.joinToString(""))
        assertTrue(tokens.contains("你好"))
        assertTrue(tokens.contains("世界"))
    }

    /** 验证真实适配入口的既有行为。 */
    @Test
    fun testPriority() {
        val text = "BEGIN:VCARD\nURL:https://google.com\nEND:VCARD"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.VCard)
    }

    /** 控制字符不能成为可提交的扫描文本。 */
    @Test
    fun regressionRejectsControlCharacters() {
        assertTrue(!TextProcessor.isDisplayableText("before\u0000after"))
    }

    /** 无配对代理项不能被当作合法文本。 */
    @Test
    fun regressionRejectsUnpairedSurrogate() {
        assertTrue(!TextProcessor.isDisplayableText("\uD83D"))
    }

    /** Wi-Fi 转义分号必须保留在原字段中。 */
    @Test
    fun regressionWifiFieldEscapesRemainIntact() {
        val wifi = TextProcessor.detectQuickAction("WIFI:S:semi\\; name;T:WPA;P:password;;") as QuickAction.Wifi
        assertEquals("semi; name", (WifiQrParser.parse(wifi.rawText) as WifiQrParser.Success).ssid)
    }

    /** 大写邮件前缀不能混入派生地址。 */
    @Test
    fun regressionUppercaseMailPrefixIsRemoved() {
        val mail = TextProcessor.detectQuickAction("MAILTO:a@example.com?subject=Hi") as QuickAction.Email
        assertEquals("a@example.com", mail.address)
    }

    /** 含空白的冒号文本不能显示打开链接操作。 */
    @Test
    fun regressionPlainColonTextIsNotDeepLink() {
        assertEquals(null, TextProcessor.detectQuickAction("Note: buy milk"))
    }

    /** ICU 词内部的标点也必须可独立选择。 */
    @Test
    fun regressionPunctuationInsideWordsIsIndependent() {
        assertEquals(listOf("can", "'", "t", " ", "foo", ".", "bar", " ", "1", ".", "2"),
            TextProcessor.tokenize("can't foo.bar 1.2"))
    }
    /** 实际 ICU 字素边界不能拆开已支持的组合与 emoji。 */
    @Test
    fun actualIcuKeepsGraphemesAndEmptyText() {
        val clusters = listOf("👩🏽‍💻", "e\u0301", "🇨🇳", "1️⃣")
        for (cluster in clusters) assertEquals(listOf(cluster), TextProcessor.tokenize(cluster))
        val text = clusters.joinToString("， ")
        assertEquals(text, TextProcessor.tokenize(text).joinToString(""))
        assertEquals(emptyList<String>(), TextProcessor.tokenize(""))
        assertEquals(TextProcessingResult.NonText, TextProcessor.process(null))
    }

}
