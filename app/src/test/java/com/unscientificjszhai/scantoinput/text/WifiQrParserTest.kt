package com.unscientificjszhai.scantoinput.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wi-Fi 字段转义、认证约束和固定错误的纯 JVM 回归。 */
class WifiQrParserTest {
    /** 所有允许转义、包装引号及普通空白均按原值保留。 */
    @Test
    fun escapedAndQuotedValuesRemainExact() {
        assertEquals(
            WifiQrParser.Success(" semi;:a,b\\\" ", " pass;:\\,\" ", WifiQrParser.Security.WPA2, true),
            WifiQrParser.parse("wifi:s: semi\\;\\:a\\,b\\\\\\\" ;t:wPa;p: pass\\;\\:\\\\\\,\\\" ;h:TrUe;;")
        )
        assertEquals(WifiQrParser.Success(" wrapped ", " password ", WifiQrParser.Security.WPA2, false),
            WifiQrParser.parse("WIFI:S:\" wrapped \";P:\" password \";T:WPA2;H:false;"))
        assertEquals(WifiQrParser.Success("\"literal\"", null, WifiQrParser.Security.OPEN, false),
            WifiQrParser.parse("WIFI:S:\\\"literal\\\";;"))
    }

    /** 开放网络不由 P 字段意外升级认证，字段与终止符允许合法缺省。 */
    @Test
    fun openNetworksKeepExplicitDefaults() {
        for (tail in listOf("", ";", ";;", ";;;", ";T:", ";T:nopass;P:ignored;", ";H:FALSE;")) {
            assertEquals(WifiQrParser.Success("网络", null, WifiQrParser.Security.OPEN, false), WifiQrParser.parse("WIFI:S:网络$tail"))
        }
    }

    /** WPA2 与 SAE 密码长度及可打印 ASCII 边界严格验证。 */
    @Test
    fun supportedPasswordsKeepAuthentication() {
        for (type in listOf("WPA", "WPA2")) {
            for (password in listOf("12345678", "~".repeat(63), "  pass  ")) {
                assertEquals(WifiQrParser.Success("N", password, WifiQrParser.Security.WPA2, false),
                    WifiQrParser.parse("WIFI:S:N;T:$type;P:$password;"))
            }
        }
        for (type in listOf("WPA3", "SAE")) {
            for (password in listOf("x", "~".repeat(63))) {
                assertEquals(WifiQrParser.Success("N", password, WifiQrParser.Security.WPA3, false),
                    WifiQrParser.parse("WIFI:S:N;T:$type;P:$password;"))
            }
        }
    }

    /** SSID 使用 UTF-8 字节数限制，允许合法首尾空白。 */
    @Test
    fun ssidLimitsAreByteBased() {
        for (ssid in listOf("a", "a".repeat(32), "中".repeat(10) + "ab", " ")) {
            assertEquals(WifiQrParser.Success(ssid, null, WifiQrParser.Security.OPEN, false), WifiQrParser.parse("WIFI:S:$ssid;"))
        }
        for (ssid in listOf("", "a".repeat(33), "中".repeat(11))) invalid("WIFI:S:$ssid;")
    }

    /** 错误字段和格式绝不构造成另一张网络。 */
    @Test
    fun invalidFieldsFailExplicitly() {
        val cases = listOf("", "not wifi", "WIFI:S:a\u0000;", "WIFI:S:\uD800;", "WIFI:", "WIFI:T:WPA;P:password;",
            "WIFI:S", "WIFI:S:N;;T:WPA;", "WIFI:S:N;S:X;", "WIFI:S:N;T:WPA;T:WPA2;", "WIFI:S:N;P:x;P:y;",
            "WIFI:S:N;H:true;H:false;", "WIFI:S:N;H:yes;", "WIFI:S:N;H:;", "WIFI:S:N\\", "WIFI:S:a\\x;",
            "WIFI:S:\"unterminated;", "WIFI:S:\";", "WIFI:S:un\"quoted;", "WIFI:S:\"a\"b;", "WIFI:S:\"trailing\\\";",
            "WIFI:S:N;T:WPA;", "WIFI:S:N;T:WPA;P:;", "WIFI:S:N;T:WPA;P:1234567;", "WIFI:S:N;T:WPA3;P:;",
            "WIFI:S:N;T:WPA;P:密码password;", "WIFI:S:N;T:WPA;P:pass\tword;", "WIFI:S:N;T:WPA;P:${"g".repeat(64)};",
            "WIFI:S:N;T:WPA3;P:${"x".repeat(65)};", "WIFI:S:N;T:WPA;P:${" ".repeat(64)};")
        cases.forEach(::invalid)
    }

    /** 未支持认证、扩展字段及原始 PSK 均不降级连接。 */
    @Test
    fun unsupportedFormatsNeverDowngrade() {
        val cases = listOf("WIFI:S:N;T:WEP;P:password;", "WIFI:S:N;T:WPA2-EAP;", "WIFI:S:N;T:UNKNOWN;", "WIFI:S:N;E:TLS;",
            "WIFI:S:N;X:value;", "WIFI:S\\:x:N;", "WIFI:S:N;T:WPA;P:${"0aF".repeat(21)}0;")
        for (raw in cases) assertEquals(raw, WifiQrParser.Failure(WifiQrParser.Error.UNSUPPORTED), WifiQrParser.parse(raw))
    }

    /**
     * 断言配置产生固定无效错误。
     * @param raw 完整 Wi-Fi 二维码文本。
     */
    private fun invalid(raw: String) {
        assertEquals(raw, WifiQrParser.Failure(WifiQrParser.Error.INVALID), WifiQrParser.parse(raw))
    }
}
