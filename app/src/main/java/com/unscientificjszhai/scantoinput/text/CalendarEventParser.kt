package com.unscientificjszhai.scantoinput.text

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** 严格解析单个日历事件的文本和时间，不读取系统时钟或默认时区。 */
object CalendarEventParser {
    private val dateFormat = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    /** 可用于固定界面提示的失败分类。 */
    enum class Error { INVALID, UNSUPPORTED_TIME, UNSUPPORTED_TIME_ZONE }

    /** 日历解析结果。 */
    sealed interface Result

    /**
     * 已解析事件，无时间字段时保留为草稿。
     * @property title 标题。
     * @property description 描述。
     * @property location 地点。
     * @property time 时间组，完全没有时间字段时为 null。
     */
    data class Success(val title: String?, val description: String?, val location: String?, val time: EventTime?) : Result

    /**
     * 一致的事件时间组。
     * @property beginMillis 开始的 epoch 毫秒。
     * @property endMillis 结束的 epoch 毫秒，全天事件保持排他结束。
     * @property allDay 是否全天。
     * @property timeZone 开始时间的时区标识，全天为 UTC。
     */
    data class EventTime(val beginMillis: Long, val endMillis: Long, val allDay: Boolean, val timeZone: String)

    /**
     * 可恢复的解析失败。
     * @property error 固定错误分类。
     */
    data class Failure(val error: Error) : Result

    /**
     * 已分离的事件属性。
     * @property name 大写属性名。
     * @property parameters 大写参数名和原值。
     * @property value 原始属性值。
     */
    private data class Property(val name: String, val parameters: Map<String, String>, val value: String)

    /**
     * 单个严格解析的时间点。
     * @property millis epoch 毫秒。
     * @property kind DATE、UTC 或 LOCAL。
     * @property zone 时区标识。
     */
    private data class TimePoint(val millis: Long, val kind: String, val zone: String)

    /**
     * 内部解析短路，不包含敏感原文。
     * @property error 固定错误分类。
     */
    private class ParseFailure(val error: Error) : RuntimeException()

    /**
     * 解析一个裸事件或日历容器内的单个事件。
     * @param rawText 完整原文。
     * @param floatingZone 浮动时间应采用的时区。
     * @return 文本与可选时间，或明确失败。
     * @throws ParseFailure 内部校验失败时抛出，并在本方法内转换为 Failure，不传给调用方。
     */
    fun parse(rawText: String, floatingZone: ZoneId): Result = try {
        if (!TextProcessingRules.isDisplayableText(rawText)) throw ParseFailure(Error.INVALID)
        val properties = eventProperties(unfold(rawText))
        val start = uniqueProperty(properties, "DTSTART")
        val end = uniqueProperty(properties, "DTEND")
        val duration = uniqueProperty(properties, "DURATION")
        val time = if (start == null && end == null && duration == null) null else {
            if (start == null) throw ParseFailure(Error.INVALID)
            val begin = parseTime(start, floatingZone)
            if (duration != null) {
                if (duration.value.isEmpty()) throw ParseFailure(Error.INVALID)
                throw ParseFailure(Error.UNSUPPORTED_TIME)
            }
            val finish = end?.let { parseTime(it, floatingZone) }
            if (finish != null) {
                if (finish.kind != begin.kind) throw ParseFailure(Error.INVALID)
                if (finish.millis <= begin.millis) throw ParseFailure(Error.INVALID)
            }
            val allDay = begin.kind == "DATE"
            EventTime(begin.millis, finish?.millis ?: (begin.millis + if (allDay) 86_400_000L else 0L), allDay, begin.zone)
        }
        Success(textValue(properties, "SUMMARY"), textValue(properties, "DESCRIPTION"), textValue(properties, "LOCATION"), time)
    } catch (failure: ParseFailure) {
        Failure(failure.error)
    } catch (_: DateTimeException) {
        Failure(Error.INVALID)
    }

    /**
     * 展开标准折行，保留属性文本中的其余空白。
     * @param text 完整日历文本。
     * @return 展开后的非空属性行。
     * @throws ParseFailure 首行没有可折叠的前行时抛出。
     */
    private fun unfold(text: String): List<String> {
        val result = mutableListOf<String>()
        for (line in text.split(Regex("\\r?\\n"))) {
            if (line.startsWith(' ') || line.startsWith('\t')) {
                if (result.isEmpty()) throw ParseFailure(Error.INVALID)
                result[result.lastIndex] += line.substring(1)
            } else if (line.isNotEmpty()) {
                result.add(line)
            }
        }
        return result
    }

    /**
     * 验证组件配对，仅收集唯一 VEVENT 的直接属性。
     * @param lines 展开后的属性行。
     * @return 目标事件属性。
     * @throws ParseFailure 组件不完整、嵌套非法或不是单个事件时抛出。
     */
    private fun eventProperties(lines: List<String>): List<Property> {
        val stack = mutableListOf<String>()
        val result = mutableListOf<Property>()
        var events = 0
        var rootClosed = false
        for (line in lines) {
            val property = parseProperty(line)
            when (property.name) {
                "BEGIN" -> {
                    val component = property.value.uppercase(Locale.ROOT)
                    if (rootClosed) throw ParseFailure(Error.INVALID)
                    if (stack.isEmpty() && component !in setOf("VCALENDAR", "VEVENT")) throw ParseFailure(Error.INVALID)
                    if (component == "VEVENT") {
                        if (stack.isNotEmpty() && stack.last() != "VCALENDAR") throw ParseFailure(Error.INVALID)
                        events++
                        if (events > 1) throw ParseFailure(Error.INVALID)
                    }
                    stack.add(component)
                }
                "END" -> {
                    if (stack.isEmpty()) throw ParseFailure(Error.INVALID)
                    if (stack.removeAt(stack.lastIndex) != property.value.uppercase(Locale.ROOT)) throw ParseFailure(Error.INVALID)
                    if (stack.isEmpty()) rootClosed = true
                }
                else -> {
                    if (stack.isEmpty()) throw ParseFailure(Error.INVALID)
                    if (stack.last() == "VEVENT") result.add(property)
                }
            }
        }
        if (stack.isNotEmpty() || events != 1) throw ParseFailure(Error.INVALID)
        return result
    }

    /**
     * 分离属性与参数，引用内的冒号或分号不作为边界。
     * @param line 完整属性行。
     * @return 结构化属性。
     * @throws ParseFailure 属性或参数格式不完整时抛出。
     */
    private fun parseProperty(line: String): Property {
        val colon = separator(line, ':')
        if (colon < 0) throw ParseFailure(Error.INVALID)
        var header = line.substring(0, colon)
        val semicolon = separator(header, ';')
        val name = (if (semicolon < 0) header else header.substring(0, semicolon)).uppercase(Locale.ROOT)
        if (name.isEmpty()) throw ParseFailure(Error.INVALID)
        val parameters = linkedMapOf<String, String>()
        if (semicolon >= 0) {
            header = header.substring(semicolon + 1)
            while (true) {
                val next = separator(header, ';')
                val parameter = if (next < 0) header else header.substring(0, next)
                val equals = parameter.indexOf('=')
                if (equals <= 0) throw ParseFailure(Error.INVALID)
                val key = parameter.substring(0, equals).uppercase(Locale.ROOT)
                if (parameters.containsKey(key)) throw ParseFailure(Error.INVALID)
                val value = parameter.substring(equals + 1)
                parameters[key] = if (value.startsWith('"')) value.substring(1, value.length - 1) else value
                if (next < 0) break
                header = header.substring(next + 1)
            }
        }
        return Property(name, parameters, line.substring(colon + 1))
    }

    /**
     * 找到引用之外的第一个分隔符。
     * @param text 待扫描文本。
     * @param delimiter 目标分隔符。
     * @return 分隔符偏移，不存在时为 -1。
     * @throws ParseFailure 引用未闭合时抛出。
     */
    private fun separator(text: String, delimiter: Char): Int {
        var quoted = false
        for (index in text.indices) {
            val char = text[index]
            if (char == '"') quoted = !quoted
            if (char == delimiter && !quoted) return index
        }
        if (quoted) throw ParseFailure(Error.INVALID)
        return -1
    }

    /**
     * 读取最多出现一次的时间字段。
     * @param properties 事件直接属性。
     * @param name 大写属性名。
     * @return 属性或 null。
     * @throws ParseFailure 字段重复时抛出。
     */
    private fun uniqueProperty(properties: List<Property>, name: String): Property? {
        val matching = properties.filter { it.name == name }
        if (matching.size > 1) throw ParseFailure(Error.INVALID)
        return matching.firstOrNull()
    }

    /**
     * 严格解析日期或时间点，未知时区不降级为 GMT。
     * @param property 时间属性。
     * @param floatingZone 浮动时间使用的时区。
     * @return 精确时间点。
     * @throws ParseFailure 参数、类型或时区不支持时抛出。
     * @throws DateTimeException 日期或时刻不合法时抛出。
     */
    private fun parseTime(property: Property, floatingZone: ZoneId): TimePoint {
        val value = property.value
        if (value.isEmpty()) throw ParseFailure(Error.INVALID)
        if (property.parameters.keys.any { it != "VALUE" && it != "TZID" }) throw ParseFailure(Error.UNSUPPORTED_TIME)
        val type = property.parameters["VALUE"]?.uppercase(Locale.ROOT) ?: "DATE-TIME"
        val tzid = property.parameters["TZID"]
        if (type == "DATE") {
            if (tzid != null) throw ParseFailure(Error.INVALID)
            if (!Regex("[0-9]{8}").matches(value)) throw ParseFailure(Error.INVALID)
            val date = LocalDate.parse(value, dateFormat)
            if (date.year < 1) throw ParseFailure(Error.INVALID)
            return TimePoint(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), "DATE", "UTC")
        }
        if (type != "DATE-TIME") throw ParseFailure(Error.UNSUPPORTED_TIME)
        if (!Regex("[0-9]{8}T[0-9]{6}Z?").matches(value)) throw ParseFailure(Error.INVALID)
        val utc = value.endsWith('Z')
        if (utc && tzid != null) throw ParseFailure(Error.INVALID)
        val localText = value.removeSuffix("Z")
        if (localText.endsWith("60")) throw ParseFailure(Error.UNSUPPORTED_TIME)
        val local = LocalDateTime.parse(localText, dateTimeFormat)
        if (local.year < 1) throw ParseFailure(Error.INVALID)
        val zone = when {
            utc -> ZoneOffset.UTC
            tzid == null -> floatingZone
            else -> try {
                ZoneId.of(tzid)
            } catch (_: DateTimeException) {
                throw ParseFailure(Error.UNSUPPORTED_TIME_ZONE)
            }
        }
        return TimePoint(local.atZone(zone).toInstant().toEpochMilli(), if (utc) "UTC" else "LOCAL", if (utc) "UTC" else zone.id)
    }

    /**
     * 读取首个文本字段并展开原有文本转义。
     * @param properties 事件直接属性。
     * @param name 大写属性名。
     * @return 解码后的文本或 null。
     */
    private fun textValue(properties: List<Property>, name: String): String? {
        val value = properties.firstOrNull { it.name == name }?.value ?: return null
        return Regex("\\\\([nN,;\\\\])").replace(value) {
            when (val escaped = it.groupValues[1]) {
                "n", "N" -> "\n"
                else -> escaped
            }
        }
    }

}
