package com.unscientificjszhai.scantoinput.actions

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickActionIntentFactoryTest {

    @Test
    fun testUrlIntent() {
        val action = QuickAction.Url("https://www.google.com")
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals(Uri.parse("https://www.google.com"), intent?.data)
    }

    @Test
    fun testWifiIntent() {
        val action = QuickAction.Wifi("WIFI:S:MySSID;P:MyPass;;", "MySSID", "MyPass", "WPA")
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, intent?.action)
        @Suppress("DEPRECATION")
        val list = intent?.getParcelableArrayListExtra<android.os.Parcelable>(Settings.EXTRA_WIFI_NETWORK_LIST)
        assertNotNull(list)
        assertEquals(1, list?.size)
    }

    @Test
    fun testVCardIntent() {
        val rawText = "BEGIN:VCARD\nFN:John Doe\nTEL:123456789\nEND:VCARD"
        val action = QuickAction.VCard(rawText)
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_INSERT, intent?.action)
        assertEquals(ContactsContract.Contacts.CONTENT_TYPE, intent?.type)
        assertEquals("John Doe", intent?.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("123456789", intent?.getStringExtra(ContactsContract.Intents.Insert.PHONE))
    }

    @Test
    fun testCalendarIntent() {
        val rawText = "BEGIN:VEVENT\nSUMMARY:Meeting\nDESCRIPTION:Discuss project\nEND:VEVENT"
        val action = QuickAction.CalendarEvent(rawText)
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_INSERT, intent?.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent?.data)
        assertEquals("Meeting", intent?.getStringExtra(CalendarContract.Events.TITLE))
    }

    @Test
    fun testEmailIntent() {
        val action = QuickAction.Email("mailto:test@example.com", "test@example.com")
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SENDTO, intent?.action)
        assertEquals(Uri.parse("mailto:test@example.com"), intent?.data)
    }

    @Test
    fun testPhoneIntent() {
        val action = QuickAction.Phone("tel:123456789", "123456789")
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_DIAL, intent?.action)
        assertEquals(Uri.parse("tel:123456789"), intent?.data)
    }

    @Test
    fun testSmsIntent() {
        val action = QuickAction.Sms("smsto:123456789:Hello", "123456789", "Hello")
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SENDTO, intent?.action)
        assertEquals(Uri.parse("smsto:123456789"), intent?.data)
        assertEquals("Hello", intent?.getStringExtra("sms_body"))
    }

    @Test
    fun testGeoIntent() {
        val action = QuickAction.Geo("geo:39.9,116.4", "39.9,116.4")
        val intent = QuickActionIntentFactory.createIntent(action)
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertTrue(intent?.data?.toString()?.startsWith("geo:") == true)
    }
}
