package com.unscientificjszhai.scantoinput.text

import com.unscientificjszhai.scantoinput.actions.QuickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextProcessorTest {

    @Test
    fun testIsDisplayableText() {
        assertTrue(TextProcessor.isDisplayableText("Hello"))
        assertTrue(!TextProcessor.isDisplayableText(""))
        assertTrue(!TextProcessor.isDisplayableText(null))
    }

    @Test
    fun testDetectWifi() {
        val text = "WIFI:T:WPA;S:MySSID;P:MyPass;;"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Wifi)
        val wifi = result as QuickAction.Wifi
        assertEquals("MySSID", wifi.ssid)
        assertEquals("MyPass", wifi.password)
        assertEquals("WPA", wifi.encryption)
    }

    @Test
    fun testDetectUrl() {
        val text = "https://www.google.com"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Url)
    }

    @Test
    fun testTokenizeBasic() {
        val text = "Hello, world!"
        val tokens = TextProcessor.tokenize(text)
        assertEquals(listOf("Hello", ",", " ", "world", "!"), tokens)
        assertEquals(text, tokens.joinToString(""))
    }

    @Test
    fun testTokenizeWithWhitespace() {
        val text = "Hello  \n world"
        val tokens = TextProcessor.tokenize(text)
        // Check if continuous whitespaces are merged
        assertEquals(listOf("Hello", "  \n ", "world"), tokens)
        assertEquals(text, tokens.joinToString(""))
    }

    @Test
    fun testProcessWifi() {
        val text = "WIFI:T:WPA;S:MySSID;P:MyPass;;"
        val result = TextProcessor.process(text)
        assertTrue(result is TextProcessingResult.Success)
        val success = result as TextProcessingResult.Success
        assertEquals(listOf(text), success.tokens)
        assertTrue(success.quickAction is QuickAction.Wifi)
    }

    @Test
    fun testProcessOrdinaryText() {
        val text = "This is a test."
        val result = TextProcessor.process(text)
        assertTrue(result is TextProcessingResult.Success)
        val success = result as TextProcessingResult.Success
        assertTrue(success.quickAction == null)
        assertEquals(text, success.tokens.joinToString(""))
    }

    @Test
    fun testDetectVCard() {
        val text = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.VCard)
    }

    @Test
    fun testDetectCalendar() {
        val text = "BEGIN:VEVENT\nSUMMARY:Meeting\nEND:VEVENT"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.CalendarEvent)
    }

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

    @Test
    fun testDetectSms() {
        val text = "smsto:12345678901:Hello there"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Sms)
        assertEquals("12345678901", (result as QuickAction.Sms).phoneNumber)
        assertEquals("Hello there", (result as QuickAction.Sms).body)
    }

    @Test
    fun testDetectGeo() {
        val text = "geo:39.9,116.4"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.Geo)
        assertEquals("39.9,116.4", (result as QuickAction.Geo).query)
    }

    @Test
    fun testTokenizeMixedChineseEnglish() {
        val text = "Hello 你好, world 世界!"
        val tokens = TextProcessor.tokenize(text)
        assertEquals(text, tokens.joinToString(""))
        assertTrue(tokens.contains("你好"))
        assertTrue(tokens.contains("世界"))
    }

    @Test
    fun testPriority() {
        val text = "BEGIN:VCARD\nURL:https://google.com\nEND:VCARD"
        val result = TextProcessor.detectQuickAction(text)
        assertTrue(result is QuickAction.VCard)
    }
}
