package com.unscientificjszhai.scantoinput.actions

import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiNetworkSuggestion
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.unscientificjszhai.scantoinput.R
import com.unscientificjszhai.scantoinput.text.TextProcessor
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** 真实 Android Intent 与 Wi-Fi 参数映射回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickActionIntentFactoryTest {
    /** URL、国际化主机和空路径自定义启动保持完整 data。 */
    @Test
    fun urlAndCustomSchemesKeepCompleteData() {
        for (raw in listOf("https://www.google.com", "exampleapp://open/item?id=1", "myapp:", "myapp://", "https://例子.测试:443/路径?q=A%2FB")) {
            val actual = intent(QuickAction.Url(raw))
            assertEquals(Intent.ACTION_VIEW, actual.action)
            assertEquals(raw, actual.data.toString())
            assertTrue(actual.hasCategory(Intent.CATEGORY_BROWSABLE))
        }
        assertEquals("http://www.example.com", intent(QuickAction.Url("www.example.com")).data.toString())
    }

    /** Wi-Fi 认证、隐藏状态及转义后的原参数均交给系统确认。 */
    @Test
    fun wifiSuggestionContainsExactFields() {
        val actual = intent(QuickAction.Wifi("WIFI:S: semi\\;name ;T:WPA;P: password ;H:true;;"))
        assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, actual.action)
        val network = suggestion(actual)
        assertEquals(" semi;name ", network.ssid)
        assertEquals(" password ", network.passphrase)
        assertTrue(network.isHiddenSsid)
        val config = ReflectionHelpers.getField<WifiConfiguration>(network, "wifiConfiguration")
        assertTrue(config.allowedKeyManagement[WifiConfiguration.KeyMgmt.WPA_PSK])
        val open = suggestion(intent(QuickAction.Wifi("WIFI:S:Open;T:nopass;P:ignored;;")))
        assertEquals("Open", open.ssid)
        assertNull(open.passphrase)
        assertFalse(open.isHiddenSsid)
        val sae = suggestion(intent(QuickAction.Wifi("WIFI:S:Modern;T:SAE;P:x;;")))
        assertEquals("x", sae.passphrase)
        assertTrue(ReflectionHelpers.getField<WifiConfiguration>(sae, "wifiConfiguration").allowedKeyManagement[WifiConfiguration.KeyMgmt.SAE])
    }

    /** 联系人保留原有字段和原文备注，并区分缺失与空值。 */
    @Test
    fun vCardIntentPreservesLegacyFieldsAndNotes() {
        val raw = "BEGIN:VCARD\nFN:John Doe\nTEL:123456789\nEND:VCARD"
        val actual = intent(QuickAction.VCard(raw))
        assertEquals(Intent.ACTION_INSERT, actual.action)
        assertEquals(ContactsContract.Contacts.CONTENT_TYPE, actual.type)
        assertEquals("John Doe", actual.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("123456789", actual.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals(raw, actual.getStringExtra(ContactsContract.Intents.Insert.NOTES))
        val absent = intent(QuickAction.VCard("BEGIN:VCARD\nEND:VCARD"))
        assertFalse(absent.hasExtra(ContactsContract.Intents.Insert.NAME))
        assertFalse(absent.hasExtra(ContactsContract.Intents.Insert.PHONE))
        val empty = intent(QuickAction.VCard("FN:\nTEL:"))
        assertEquals("", empty.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("", empty.getStringExtra(ContactsContract.Intents.Insert.PHONE))
    }

    /** 原有无时间日历草稿保留，所有时间 extras 均缺省。 */
    @Test
    fun calendarDraftDoesNotInventTime() {
        val raw = "BEGIN:VEVENT\nSUMMARY:Meeting\nDESCRIPTION:Discuss project\nLOCATION:Room\nEND:VEVENT"
        val actual = intent(QuickAction.CalendarEvent(raw))
        assertEquals(Intent.ACTION_INSERT, actual.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, actual.data)
        assertEquals("Meeting", actual.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("Room", actual.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals("Discuss project\n\nOriginal Text:\n$raw", actual.getStringExtra(CalendarContract.Events.DESCRIPTION))
        for (key in listOf(CalendarContract.EXTRA_EVENT_BEGIN_TIME, CalendarContract.EXTRA_EVENT_END_TIME,
            CalendarContract.EXTRA_EVENT_ALL_DAY, CalendarContract.Events.EVENT_TIMEZONE)) assertFalse(actual.hasExtra(key))
    }

    /** 全天 UTC epoch、排他结束和时区 extras 正确映射。 */
    @Test
    fun allDayCalendarKeepsUtcExtras() {
        val raw = "BEGIN:VEVENT\nDTSTART;VALUE=DATE:20240229\nDTEND;VALUE=DATE:20240302\nEND:VEVENT"
        val actual = intent(QuickAction.CalendarEvent(raw), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2024-02-29T00:00:00Z").toEpochMilli(), actual.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals(Instant.parse("2024-03-02T00:00:00Z").toEpochMilli(), actual.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        assertTrue(actual.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
        assertEquals("UTC", actual.getStringExtra(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals("\n\nOriginal Text:\n$raw", actual.getStringExtra(CalendarContract.Events.DESCRIPTION))
    }

    /** 日期和链接失败只返回固定资源，没有 Intent。 */
    @Test
    fun invalidActionsHaveSpecificRecoverableResults() {
        val cases = listOf(
            QuickAction.CalendarEvent("BEGIN:VEVENT\nDTSTART:20260229T000000Z\nEND:VEVENT") to R.string.invalid_calendar_event,
            QuickAction.CalendarEvent("BEGIN:VEVENT\nDTSTART:20260912T080000Z\nDURATION:PT1H\nEND:VEVENT") to R.string.unsupported_calendar_time,
            QuickAction.CalendarEvent("BEGIN:VEVENT\nDTSTART;TZID=Missing/Zone:20260912T080000\nEND:VEVENT") to R.string.unsupported_calendar_time_zone,
            QuickAction.Url("Note: buy milk") to R.string.invalid_action_link
        )
        for ((action, resource) in cases) assertEquals(QuickActionIntentFactory.CreationResult.Failure(resource), QuickActionIntentFactory.createIntent(action))
    }

    /** 裸邮箱、电话及大写内置 scheme 仍使用对应系统动作。 */
    @Test
    fun bareAndPrefixedEmailAndPhoneAreSupported() {
        for (raw in listOf("test@example.com", "MAILTO:test@example.com")) {
            val actual = intent(TextProcessor.detectQuickAction(raw)!!)
            assertEquals(Intent.ACTION_SENDTO, actual.action)
            assertEquals("mailto:test@example.com", actual.data.toString())
        }
        for (raw in listOf("123456789", "TEL:123456789")) {
            val actual = intent(TextProcessor.detectQuickAction(raw)!!)
            assertEquals(Intent.ACTION_DIAL, actual.action)
            assertEquals("tel:123456789", actual.data.toString())
        }
    }

    /** 短信冒号正文和查询形式保持现有交付约定。 */
    @Test
    fun smsConventionsRemainUnchanged() {
        val legacy = intent(TextProcessor.detectQuickAction("SMSTO:123456789:Hello there:again")!!)
        assertEquals(Intent.ACTION_SENDTO, legacy.action)
        assertEquals("smsto:123456789", legacy.data.toString())
        assertEquals("Hello there:again", legacy.getStringExtra("sms_body"))
        val query = intent(TextProcessor.detectQuickAction("SMS:123?body=hello%20world")!!)
        assertEquals("smsto:123?body=hello%20world", query.data.toString())
        assertFalse(query.hasExtra("sms_body"))
    }

    /** 邮件主题、正文和编码必须完整交给接收端。 */
    @Test
    fun regressionMailQueryIsPreserved() {
        val raw = "mailto:a@example.com?subject=Hello&body=a%20b&cc=x@example.com"
        assertEquals(raw, intent(QuickAction.Email(raw, "a@example.com")).data.toString())
    }

    /** 地图查询不能被当作地名再次编码。 */
    @Test
    fun regressionGeoQueryIsPreserved() {
        for (raw in listOf("geo:0,0?q=Beijing&z=15", "geo:39.9,116.4")) {
            val actual = intent(QuickAction.Geo(raw, raw.substring(4)))
            assertEquals(raw, actual.data.toString())
            assertEquals(Intent.ACTION_VIEW, actual.action)
            assertTrue(actual.hasCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    /** 输出只规范链接的 scheme，保留路径和查询大小写。 */
    @Test
    fun regressionUriSchemeIsNormalized() {
        assertEquals("https://Example.com/Case?q=A%2FB", intent(QuickAction.Url("HTTPS://Example.com/Case?q=A%2FB")).data.toString())
    }

    /** 已有日历开始与结束毫秒、全天状态及时区必须传给系统。 */
    @Test
    fun regressionCalendarTimeIsTransferred() {
        val raw = "BEGIN:VEVENT\nDTSTART:20260912T080000Z\nDTEND:20260912T090000Z\nEND:VEVENT"
        val actual = intent(QuickAction.CalendarEvent(raw))
        assertEquals(Instant.parse("2026-09-12T08:00:00Z").toEpochMilli(), actual.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals(Instant.parse("2026-09-12T09:00:00Z").toEpochMilli(), actual.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        assertFalse(actual.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true))
        assertEquals("UTC", actual.getStringExtra(CalendarContract.Events.EVENT_TIMEZONE))
    }

    /** 不支持的认证不能静默改为 WPA2。 */
    @Test
    fun regressionUnsupportedWifiIsNotDowngraded() {
        val action = QuickAction.Wifi("WIFI:S:Network;T:WEP;P:password;;")
        assertEquals(QuickActionIntentFactory.CreationResult.Failure(R.string.unsupported_wifi_config), QuickActionIntentFactory.createIntent(action))
    }

    /** 非法参数成为可恢复结果，点击路径不再抛出异常。 */
    @Test
    fun regressionInvalidWifiConstructionIsRecoverable() {
        val action = QuickAction.Wifi("WIFI:S:Network;T:WPA;P:密码password;;")
        assertEquals(QuickActionIntentFactory.CreationResult.Failure(R.string.invalid_wifi_config), QuickActionIntentFactory.createIntent(action))
    }

    /**
     * 读取成功结果，失败时直接给出对应结果对象。
     * @param action 待构造动作。
     * @param zone 日历浮动时区。
     * @return 成功 Intent。
     */
    private fun intent(action: QuickAction, zone: ZoneId = ZoneId.of("UTC")): Intent {
        val result = QuickActionIntentFactory.createIntent(action, zone)
        assertTrue(result.toString(), result is QuickActionIntentFactory.CreationResult.Success)
        return (result as QuickActionIntentFactory.CreationResult.Success).intent
    }

    /**
     * 读取系统网络确认列表中的唯一项。
     * @param intent 添加网络 Intent。
     * @return 唯一的 Wi-Fi 参数。
     */
    private fun suggestion(intent: Intent): WifiNetworkSuggestion {
        val list = intent.getParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, WifiNetworkSuggestion::class.java)!!
        assertEquals(1, list.size)
        return list.single()
    }
}
