package com.unscientificjszhai.scantoinput.actions

import com.unscientificjszhai.scantoinput.R

/**
 * 表示二维码扫描结果中识别出的特定模式及其对应的快速操作。
 *
 * @property rawText 原始扫描文本。
 * @property labelResId 按钮显示的文案资源 ID。
 */
sealed class QuickAction(val rawText: String, val labelResId: Int) {

    /**
     * URL 模式。
     */
    class Url(rawText: String) : QuickAction(rawText, R.string.action_open_url)

    /**
     * Wi-Fi 模式。
     */
    class Wifi(rawText: String, val ssid: String, val password: String?, val encryption: String?) :
        QuickAction(rawText, R.string.action_connect_wifi)

    /**
     * vCard 联系人模式。
     */
    class VCard(rawText: String) : QuickAction(rawText, R.string.action_add_contact)

    /**
     * 日历事件模式。
     */
    class CalendarEvent(rawText: String) : QuickAction(rawText, R.string.action_add_calendar)

    /**
     * 电子邮件模式。
     */
    class Email(rawText: String, val address: String) : QuickAction(rawText, R.string.action_send_email)

    /**
     * 电话模式。
     */
    class Phone(rawText: String, val phoneNumber: String) : QuickAction(rawText, R.string.action_dial_phone)

    /**
     * 短信模式。
     */
    class Sms(rawText: String, val phoneNumber: String, val body: String?) :
        QuickAction(rawText, R.string.action_send_sms)

    /**
     * 地理位置模式。
     */
    class Geo(rawText: String, val query: String) : QuickAction(rawText, R.string.action_open_map)
}
