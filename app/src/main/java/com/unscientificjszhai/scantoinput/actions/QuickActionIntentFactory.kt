package com.unscientificjszhai.scantoinput.actions

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 快速操作 Intent 工厂类。
 * 负责将 [QuickAction] 转换为可执行的 [Intent]。
 */
object QuickActionIntentFactory {

    /**
     * 根据 [QuickAction] 创建对应的 [Intent]。
     *
     * @param action 快速操作。
     * @return 对应的 Intent，如果无法创建则返回 null。
     */
    fun createIntent(action: QuickAction): Intent? {
        return when (action) {
            is QuickAction.Url -> createUrlIntent(action)
            is QuickAction.Wifi -> createWifiIntent(action)
            is QuickAction.VCard -> createVCardIntent(action)
            is QuickAction.CalendarEvent -> createCalendarIntent(action)
            is QuickAction.Email -> createEmailIntent(action)
            is QuickAction.Phone -> createPhoneIntent(action)
            is QuickAction.Sms -> createSmsIntent(action)
            is QuickAction.Geo -> createGeoIntent(action)
        }
    }

    /**
     * 创建打开网页或应用深链的 Intent。
     *
     * @param action URL 快速操作。
     * @return 打开 URI 的 Intent。
     */
    private fun createUrlIntent(action: QuickAction.Url): Intent {
        val rawText = action.rawText
        val uri = if (rawText.toUri().scheme != null) {
            rawText.toUri()
        } else {
            "http://$rawText".toUri()
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    /**
     * 创建添加 Wi-Fi 网络的系统确认 Intent。
     *
     * @param action Wi-Fi 快速操作。
     * @return 添加 Wi-Fi 网络的 Intent。
     */
    private fun createWifiIntent(action: QuickAction.Wifi): Intent {
        // 使用 Settings.ACTION_WIFI_ADD_NETWORKS (API 30+)
        // 虽然名字叫 Suggestion，但在 ACTION_WIFI_ADD_NETWORKS 中用于添加保存的网络
        val builder = WifiNetworkSuggestion.Builder()
            .setSsid(action.ssid)

        when (action.encryption?.uppercase()) {
            "WPA", "WPA2" -> {
                action.password?.let { builder.setWpa2Passphrase(it) }
            }

            "WPA3" -> {
                action.password?.let { builder.setWpa3Passphrase(it) }
            }

            "NOPASS", "" -> {
                // 不设置密码即为开放网络
            }

            else -> {
                // 默认尝试 WPA2
                action.password?.let { builder.setWpa2Passphrase(it) }
            }
        }

        val suggestion = builder.build()
        val bundle = Bundle()
        bundle.putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))

        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putExtras(bundle)
        }
    }

    /**
     * 创建新增联系人的 Intent。
     *
     * @param action vCard 快速操作。
     * @return 打开联系人新增界面的 Intent。
     */
    private fun createVCardIntent(action: QuickAction.VCard): Intent {
        // 尝试解析核心字段：FN (Full Name) 和 TEL (Telephone)
        val text = action.rawText
        val name = Regex("FN:(.*)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val phone =
            Regex("TEL(?:;[^:]*)?:(.*)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                ?.trim()

        return Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            // 兜底：将全文放入备注
            putExtra(ContactsContract.Intents.Insert.NOTES, text)
        }
    }

    /**
     * 创建新增日历事件的 Intent。
     *
     * @param action 日历事件快速操作。
     * @return 打开日历事件新增界面的 Intent。
     */
    private fun createCalendarIntent(action: QuickAction.CalendarEvent): Intent {
        // 尝试解析核心字段：SUMMARY, DTSTART, DTEND
        val text = action.rawText
        val summary =
            Regex("SUMMARY:(.*)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val description =
            Regex("DESCRIPTION:(.*)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
                ?.trim()

        return Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            summary?.let { putExtra(CalendarContract.Events.TITLE, it) }
            description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            // 兜底：备注原始文本
            val finalDesc = (description ?: "") + "\n\nOriginal Text:\n" + text
            putExtra(CalendarContract.Events.DESCRIPTION, finalDesc)
        }
    }

    /**
     * 创建发送邮件的 Intent。
     *
     * @param action 邮件快速操作。
     * @return 打开邮件客户端的 Intent。
     */
    private fun createEmailIntent(action: QuickAction.Email): Intent {
        return Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:${action.address}".toUri()
        }
    }

    /**
     * 创建拨号 Intent。
     *
     * @param action 电话快速操作。
     * @return 打开拨号界面的 Intent。
     */
    private fun createPhoneIntent(action: QuickAction.Phone): Intent {
        return Intent(Intent.ACTION_DIAL).apply {
            data = "tel:${action.phoneNumber}".toUri()
        }
    }

    /**
     * 创建发送短信的 Intent。
     *
     * @param action 短信快速操作。
     * @return 打开短信客户端的 Intent。
     */
    private fun createSmsIntent(action: QuickAction.Sms): Intent {
        return Intent(Intent.ACTION_SENDTO).apply {
            data = "smsto:${action.phoneNumber}".toUri()
            action.body?.let { putExtra("sms_body", it) }
        }
    }

    /**
     * 创建打开地图位置的 Intent。
     *
     * @param action 地理位置快速操作。
     * @return 打开地图应用的 Intent。
     */
    private fun createGeoIntent(action: QuickAction.Geo): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            data = "geo:0,0?q=${Uri.encode(action.query)}".toUri()
        }
    }
}
