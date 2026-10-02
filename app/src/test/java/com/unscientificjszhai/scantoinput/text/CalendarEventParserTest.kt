package com.unscientificjszhai.scantoinput.text

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** 日历严格时间、结构边界与无时间草稿的纯 JVM 回归。 */
class CalendarEventParserTest {
    private val utc = ZoneId.of("UTC")

    /** 既有无时间草稿不读取当前时间，也不受注入时区影响。 */
    @Test
    fun untimedDraftRemainsUntimed() {
        val raw = event("SUMMARY:Meeting\nDESCRIPTION:Discuss\nLOCATION:Room")
        val expected = CalendarEventParser.Success("Meeting", "Discuss", "Room", null)
        assertEquals(expected, CalendarEventParser.parse(raw, utc))
        assertEquals(expected, CalendarEventParser.parse(raw, ZoneId.of("Asia/Shanghai")))
        assertEquals(CalendarEventParser.Success(null, null, null, null), CalendarEventParser.parse(event(""), utc))
    }

    /** UTC 开始结束精确到 epoch，缺失结束的时间事件不额外增加一小时。 */
    @Test
    fun utcEventsKeepExactInstants() {
        val raw = event("DTSTART:20260912T080000Z\nDTEND:20260912T090000Z")
        assertTime(raw, "2026-09-12T08:00:00Z", "2026-09-12T09:00:00Z", false, "UTC")
        assertTime(event("DTSTART:20260912T080000Z"), "2026-09-12T08:00:00Z", "2026-09-12T08:00:00Z", false, "UTC")
    }

    /** 浮动时间明确使用调用方注入时区。 */
    @Test
    fun floatingTimesUseInjectedZone() {
        val raw = event("DTSTART:20260912T080000\nDTEND:20260912T090000")
        assertTime(raw, "2026-09-12T08:00:00Z", "2026-09-12T09:00:00Z", false, "UTC")
        assertTime(raw, "2026-09-12T00:00:00Z", "2026-09-12T01:00:00Z", false, "Asia/Shanghai", ZoneId.of("Asia/Shanghai"))
    }

    /** 显式时区与引用参数中的冒号正确解析，开始结束可各自指定本地时区。 */
    @Test
    fun namedAndQuotedTimeZonesAreExact() {
        assertTime(event("dtstart;tzid=America/New_York;value=date-time:20260912T080000\nDTEND;TZID=America/New_York:20260912T090000"),
            "2026-09-12T12:00:00Z", "2026-09-12T13:00:00Z", false, "America/New_York")
        assertTime(event("DTSTART;TZID=\"GMT+08:00\":20260912T080000\nDTEND;TZID=UTC:20260912T090000"),
            "2026-09-12T00:00:00Z", "2026-09-12T09:00:00Z", false, "GMT+08:00")
    }

    /** 夏令时重叠采用较早偏移，空档按标准 atZone 向前调整。 */
    @Test
    fun daylightSavingResolutionIsExplicit() {
        assertTime(event("DTSTART;TZID=America/New_York:20261101T013000"),
            "2026-11-01T05:30:00Z", "2026-11-01T05:30:00Z", false, "America/New_York")
        assertTime(event("DTSTART;TZID=America/New_York:20260308T023000"),
            "2026-03-08T07:30:00Z", "2026-03-08T07:30:00Z", false, "America/New_York")
    }

    /** 全天按 UTC 午夜处理，保留排他结束及缺省次日。 */
    @Test
    fun allDayDatesKeepExclusiveUtcEnd() {
        assertTime(event("DTSTART;VALUE=DATE:20240229\nDTEND;VALUE=DATE:20240302"),
            "2024-02-29T00:00:00Z", "2024-03-02T00:00:00Z", true, "UTC", ZoneId.of("America/New_York"))
        assertTime(event("DTSTART;VALUE=date:20260131"), "2026-01-31T00:00:00Z", "2026-02-01T00:00:00Z", true, "UTC")
        assertTime(event("DTSTART;VALUE=DATE:00010101"), "0001-01-01T00:00:00Z", "0001-01-02T00:00:00Z", true, "UTC")
    }

    /** 折行和转义保持文本，嵌套提醒和时区组件不会污染事件时间。 */
    @Test
    fun unfoldingAndComponentsDoNotStealTimeFields() {
        val raw = "BEGIN:VCALENDAR\r\nBEGIN:VTIMEZONE\r\nDTSTART:19000101T000000\r\nEND:VTIMEZONE\r\n" +
            "BEGIN:VEVENT\r\nSUMMARY:Long \r\n title\r\nDESCRIPTION:one\\ntwo\\Nthree\\,four\\;five\\\\six\r\n" +
            "LOCATION:Room\r\n\t A\r\nDTSTART:20260912T080000Z\r\nBEGIN:VALARM\r\nDTSTART:19000101T000000Z\r\n" +
            "DESCRIPTION:Alarm\r\nEND:VALARM\r\nX-EXTENSION:ignored\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parsed = CalendarEventParser.parse(raw, utc) as CalendarEventParser.Success
        assertEquals("Long title", parsed.title)
        assertEquals("one\ntwo\nthree,four;five\\six", parsed.description)
        assertEquals("Room A", parsed.location)
        assertEquals(Instant.parse("2026-09-12T08:00:00Z").toEpochMilli(), parsed.time!!.beginMillis)
    }

    /** 非法日期、位数、时间类型或结束顺序均不能宽松归一化。 */
    @Test
    fun invalidDatesAndTimeRelationshipsFail() {
        val properties = listOf("DTSTART:", "DTSTART:20260229T080000Z", "DTSTART:20261301T080000Z", "DTSTART:20260931T080000Z",
            "DTSTART:20260912T240000Z", "DTSTART:20260912T086100Z", "DTSTART:20260912T080061Z", "DTSTART:2026912T080000Z",
            "DTSTART:00000912T080000Z", "DTSTART:20260912", "DTSTART;TZID=UTC:20260912T080000Z", "DTSTART;VALUE=DATE:20260229",
            "DTSTART;VALUE=DATE:20261301", "DTSTART;VALUE=DATE:00000101", "DTSTART;VALUE=DATE:2026912", "DTSTART;VALUE=DATE;TZID=UTC:20260912",
            "DTSTART:20260912T080000Z\nDTEND:", "DTSTART:20260912T080000Z\nDTEND:20260912T080000Z",
            "DTSTART:20260912T080000Z\nDTEND:20260912T070000Z", "DTSTART:20260912T080000Z\nDTEND:20260912T090000",
            "DTSTART;VALUE=DATE:20260912\nDTEND:20260913T090000Z", "DTSTART;VALUE=DATE:20260912\nDTEND;VALUE=DATE:20260912",
            "DTEND:20260912T090000Z", "DURATION:PT1H", "DTSTART:20260912T080000Z\nDURATION:",
            "DTSTART:20260912T080000Z\nDTSTART:20260913T080000Z", "DTSTART:20260912T080000Z\nDTEND:20260913T080000Z\nDTEND:20260914T080000Z",
            "DTSTART:20260912T080000Z\nDURATION:PT1H\nDURATION:PT2H")
        properties.forEach { failure(event(it)) }
    }

    /** 不支持的时区和时间形式使用明确分类，绝不退回 GMT。 */
    @Test
    fun unsupportedTimeIsDistinguishedFromInvalidDate() {
        failure(event("DTSTART;TZID=No/Such_Zone:20260912T080000"), CalendarEventParser.Error.UNSUPPORTED_TIME_ZONE)
        failure(event("DTSTART;TZID=:20260912T080000"), CalendarEventParser.Error.UNSUPPORTED_TIME_ZONE)
        for (properties in listOf("DTSTART:20260912T080000Z\nDURATION:PT1H", "DTSTART:20161231T235960Z", "DTSTART;VALUE=PERIOD:20260912T080000Z", "DTSTART;X-FORMAT=OTHER:20260912T080000Z")) {
            failure(event(properties), CalendarEventParser.Error.UNSUPPORTED_TIME)
        }
    }

    /** 组件边界与参数损坏必须明确失败。 */
    @Test
    fun malformedContainersAndPropertiesFail() {
        val rawCases = listOf("", "\u0000", " orphan", "BEGIN:OTHER\nEND:OTHER", "SUMMARY:outside", "END:VEVENT", "BEGIN:VEVENT\nEND:VCALENDAR",
            "BEGIN:VEVENT", "BEGIN:VCALENDAR\nEND:VCALENDAR", event("") + "\n" + event(""), "BEGIN:VCALENDAR\n${event("")}\n${event("")}\nEND:VCALENDAR",
            event("BEGIN:VEVENT\nEND:VEVENT"), event("SUMMARY without colon"), event(":empty-name"), event("DTSTART;VALUE:20260912T080000Z"),
            event("DTSTART;=DATE:20260912"), event("DTSTART;VALUE=DATE;VALUE=DATE:20260912"), event("DTSTART;TZID=\"UTC:20260912T080000"))
        rawCases.forEach { failure(it) }
    }

    /**
     * 创建裸事件测试数据。
     * @param properties 直接属性文本。
     * @return 完整 VEVENT。
     */
    private fun event(properties: String): String = "BEGIN:VEVENT\n$properties\nEND:VEVENT"

    /**
     * 断言解析时间组的全部字段。
     * @param raw 完整事件。
     * @param begin 预期开始的 ISO instant。
     * @param end 预期结束的 ISO instant。
     * @param allDay 预期全天状态。
     * @param zone 预期开始时区。
     * @param floatingZone 注入的浮动时区。
     */
    private fun assertTime(raw: String, begin: String, end: String, allDay: Boolean, zone: String, floatingZone: ZoneId = utc) {
        val parsed = CalendarEventParser.parse(raw, floatingZone) as CalendarEventParser.Success
        assertEquals(CalendarEventParser.EventTime(Instant.parse(begin).toEpochMilli(), Instant.parse(end).toEpochMilli(), allDay, zone), parsed.time)
    }

    /**
     * 断言固定失败类型。
     * @param raw 完整事件文本。
     * @param error 预期错误分类。
     */
    private fun failure(raw: String, error: CalendarEventParser.Error = CalendarEventParser.Error.INVALID) {
        assertEquals(raw, CalendarEventParser.Failure(error), CalendarEventParser.parse(raw, utc))
    }
}
