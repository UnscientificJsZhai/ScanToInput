package com.unscientificjszhai.scantoinput.actions

import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.unscientificjszhai.scantoinput.R
import com.unscientificjszhai.scantoinput.text.CalendarEventParser
import com.unscientificjszhai.scantoinput.text.TextProcessingRules
import com.unscientificjszhai.scantoinput.text.WifiQrParser
import java.time.ZoneId

/** 将已验证的纯业务参数映射为需要用户确认的系统 Intent。 */
object QuickActionIntentFactory {
    /** 系统 Intent 创建结果。 */
    sealed interface CreationResult {
        /**
         * 可交由系统处理的操作。
         * @property intent 已构造的 Intent。
         */
        data class Success(val intent: Intent) : CreationResult

        /**
         * 可恢复错误，不携带敏感原文或异常消息。
         * @property messageResId 固定提示资源。
         */
        data class Failure(val messageResId: Int) : CreationResult
    }

    /**
     * 创建快速操作，预期的参数异常转换为固定错误。
     * @param action 当前操作。
     * @param floatingZone 日历浮动时间采用的时区，默认为点击时的系统时区。
     * @return 可用 Intent 或明确失败。
     */
    fun createIntent(action: QuickAction, floatingZone: ZoneId = ZoneId.systemDefault()): CreationResult = try {
        when (action) {
            is QuickAction.Url -> createUriIntent(action.rawText, Intent.ACTION_VIEW, browsable = true)
            is QuickAction.Wifi -> createWifiIntent(action)
            is QuickAction.VCard -> CreationResult.Success(createVCardIntent(action))
            is QuickAction.CalendarEvent -> createCalendarIntent(action, floatingZone)
            is QuickAction.Email -> createUriIntent(action.rawText, Intent.ACTION_SENDTO)
            is QuickAction.Phone -> createUriIntent(action.rawText, Intent.ACTION_DIAL)
            is QuickAction.Sms -> CreationResult.Success(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${action.phoneNumber}")).apply {
                action.body?.let { putExtra("sms_body", it) }
            })
            is QuickAction.Geo -> createUriIntent(action.rawText, Intent.ACTION_VIEW, browsable = true)
        }
    } catch (_: IllegalArgumentException) {
        CreationResult.Failure(when (action) {
            is QuickAction.Wifi -> R.string.invalid_wifi_config
            is QuickAction.CalendarEvent -> R.string.invalid_calendar_event
            else -> R.string.cannot_perform_action
        })
    }

    /**
     * 映射无损规范后的 URI。
     * @param text 原始 URI 或裸地址。
     * @param intentAction 系统动作名。
     * @param browsable 是否允许浏览器路由。
     * @return 保真 URI Intent 或固定链接错误。
     */
    private fun createUriIntent(text: String, intentAction: String, browsable: Boolean = false): CreationResult {
        val uri = TextProcessingRules.normalizeActionUri(text) ?: return CreationResult.Failure(R.string.invalid_action_link)
        return CreationResult.Success(Intent(intentAction, Uri.parse(uri)).apply {
            if (browsable) addCategory(Intent.CATEGORY_BROWSABLE)
        })
    }

    /**
     * 将 Wi-Fi 参数映射到系统添加网络确认界面。
     * @param action Wi-Fi 原文动作。
     * @return 系统确认 Intent 或固定配置错误。
     */
    private fun createWifiIntent(action: QuickAction.Wifi): CreationResult {
        return when (val parsed = WifiQrParser.parse(action.rawText)) {
            is WifiQrParser.Failure -> CreationResult.Failure(when (parsed.error) {
                WifiQrParser.Error.INVALID -> R.string.invalid_wifi_config
                WifiQrParser.Error.UNSUPPORTED -> R.string.unsupported_wifi_config
            })
            is WifiQrParser.Success -> {
                val builder = WifiNetworkSuggestion.Builder().setSsid(parsed.ssid).setIsHiddenSsid(parsed.hidden)
                when (parsed.security) {
                    WifiQrParser.Security.OPEN -> Unit
                    WifiQrParser.Security.WPA2 -> builder.setWpa2Passphrase(requireNotNull(parsed.password))
                    WifiQrParser.Security.WPA3 -> builder.setWpa3Passphrase(requireNotNull(parsed.password))
                }
                CreationResult.Success(Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                    putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(builder.build()))
                })
            }
        }
    }

    /**
     * 映射原有姓名、电话和完整原文备注。
     * @param action 联系人原文动作。
     * @return 系统新增联系人 Intent。
     */
    private fun createVCardIntent(action: QuickAction.VCard): Intent {
        val fields = TextProcessingRules.extractVCardFields(action.rawText)
        return Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            fields.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            fields.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            putExtra(ContactsContract.Intents.Insert.NOTES, action.rawText)
        }
    }

    /**
     * 将严格时间与文本字段映射到新增日历界面。
     * @param action 日历原文动作。
     * @param floatingZone 浮动时间采用的时区。
     * @return 日历 Intent 或固定解析错误。
     */
    private fun createCalendarIntent(action: QuickAction.CalendarEvent, floatingZone: ZoneId): CreationResult {
        return when (val parsed = CalendarEventParser.parse(action.rawText, floatingZone)) {
            is CalendarEventParser.Failure -> CreationResult.Failure(when (parsed.error) {
                CalendarEventParser.Error.INVALID -> R.string.invalid_calendar_event
                CalendarEventParser.Error.UNSUPPORTED_TIME -> R.string.unsupported_calendar_time
                CalendarEventParser.Error.UNSUPPORTED_TIME_ZONE -> R.string.unsupported_calendar_time_zone
            })
            is CalendarEventParser.Success -> CreationResult.Success(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                parsed.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
                parsed.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                putExtra(CalendarContract.Events.DESCRIPTION, (parsed.description ?: "") + "\n\nOriginal Text:\n" + action.rawText)
                parsed.time?.let {
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.beginMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.endMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, it.allDay)
                    putExtra(CalendarContract.Events.EVENT_TIMEZONE, it.timeZone)
                }
            })
        }
    }
}
