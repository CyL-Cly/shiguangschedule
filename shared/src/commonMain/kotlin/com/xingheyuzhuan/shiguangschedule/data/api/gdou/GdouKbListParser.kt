package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.CourseConfigJsonModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.ImportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.TimeSlotJsonModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object GdouYangjiangTimetable {
    val timeSlots: List<TimeSlotJsonModel> = listOf(
        TimeSlotJsonModel(1, "08:10", "08:55"),
        TimeSlotJsonModel(2, "09:05", "09:50"),
        TimeSlotJsonModel(3, "10:20", "11:05"),
        TimeSlotJsonModel(4, "11:15", "12:00"),
        TimeSlotJsonModel(5, "14:30", "15:15"),
        TimeSlotJsonModel(6, "15:20", "16:05"),
        TimeSlotJsonModel(7, "16:20", "17:05"),
        TimeSlotJsonModel(8, "17:10", "17:55"),
        TimeSlotJsonModel(9, "19:30", "20:15"),
        TimeSlotJsonModel(10, "20:25", "21:10")
    )

    const val SPECIAL_VENUE = "慎思楼"
    const val OTHER_VENUE_START = "10:10"
    const val OTHER_VENUE_END = "11:40"
}

object GdouKbListParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 只要课程结果、不需要诊断信息的调用入口；实现统一委托给 [parseCoursesWithDiagnostics]。 */
    fun parseCourses(rawJson: String): List<ImportCourseJsonModel> =
        parseCoursesWithDiagnostics(rawJson).first

    /**
     * 解析课表原始 JSON，同时回传解析诊断信息。
     * 用于在同步日志中暴露「哪些行被丢弃」，避免课程缺失时无从排查。
     */
    fun parseCoursesWithDiagnostics(rawJson: String): Pair<List<ImportCourseJsonModel>, List<String>> {
        val root = json.parseToJsonElement(rawJson)
        val kbList = findKbList(root) ?: return emptyList<ImportCourseJsonModel>() to listOf("未找到 kbList 字段")
        val parsed = mutableListOf<ImportCourseJsonModel>()
        val skipped = mutableListOf<String>()
        kbList.forEach { element ->
            val rows = parseRow(element)
            if (rows.isEmpty()) {
                val obj = element as? JsonObject
                val name = obj?.string("kcmc").orEmpty()
                val reason = when {
                    name.isBlank() -> "课程名为空"
                    obj?.string("xqj")?.toIntOrNull() !in 1..7 -> "星期字段无效(xqj=${obj?.string("xqj")})"
                    parseWeeks(obj?.string("zcd")).isEmpty() -> "周次无法解析(zcd=${obj?.string("zcd")})"
                    parseSectionSegments(obj?.string("jcs")).isEmpty() -> "节次无法解析(jcs=${obj?.string("jcs")})"
                    else -> "未知原因"
                }
                skipped.add("跳过记录「${name.ifBlank { "<无名称>" }}」：$reason")
            } else {
                parsed.addAll(rows)
            }
        }
        return mergeAndDistinct(parsed).map { applyYangjiangCustomTime(it) } to skipped
    }

    fun parseSemesterInfo(rawJson: String): GdouSemesterInfo? {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return null
        val weeks = flattenWeekItems(root)
        if (weeks.isEmpty()) return null
        val startDate = findSemesterStartDate(root)
        return GdouSemesterInfo(startDate = startDate, totalWeeks = weeks.size.coerceAtLeast(1))
    }

    fun configFrom(info: GdouSemesterInfo?, courses: List<ImportCourseJsonModel>): CourseConfigJsonModel {
        val maxWeek = courses.maxOfOrNull { it.weeks.maxOrNull() ?: 0 } ?: 0
        val total = maxOf(maxWeek, info?.totalWeeks ?: 20, 1)
        return CourseConfigJsonModel(
            semesterStartDate = info?.startDate,
            semesterTotalWeeks = total
        )
    }

    fun inferTerm(nowYear: Int, month1to12: Int): GdouTerm {
        val isFirst = month1to12 >= 9
        val xnm = if (isFirst) nowYear else nowYear - 1
        return GdouTerm(
            xnm = xnm.toString(),
            xqm = if (isFirst) GDOU_XQM_FIRST else GDOU_XQM_SECOND
        )
    }

    /**
     * 解析「单双周」标记。
     * 教务系统的写法并不统一，常见形式包括：
     * `1-15周(单)`、`1-15周（单）`、`1-15单周`、`1-15周(单周)`、`1-15周单`、`1-15周(单,双)`。
     * 仅判断半角 `(单)` 会让全角括号等写法静默退化为「每周都上」，进而导致课程块相互覆盖。
     */
    private fun oddEvenFilter(item: String): (Int) -> Boolean {
        val normalized = item.replace('（', '(').replace('）', ')')
        val oddOnly = normalized.contains("单") && !normalized.contains("双")
        val evenOnly = normalized.contains("双") && !normalized.contains("单")
        return { w -> !(oddOnly && w % 2 == 0) && !(evenOnly && w % 2 != 0) }
    }

    internal fun parseWeeks(weekStr: String?): List<Int> {
        if (weekStr.isNullOrBlank()) return emptyList()
        // 教务系统会用逗号、全角逗号、分号、顿号、空格等多种分隔符并列多个区间
        val groups = weekStr
            // 单周课程会返回「第8周」这种带「第」前缀的写法，
            // 旧正则要求数字开头会整行解析失败，导致课程被丢弃（如「劳动教育2」）
            .replace("第", "")
            .replace('，', ',')
            .replace('；', ',')
            .replace(';', ',')
            .replace('、', ',')
            .split(',', ' ', '\t', '\n')
        val weeks = linkedSetOf<Int>()
        for (rawGroup in groups) {
            val item = rawGroup.trim()
            if (item.isEmpty()) continue
            val range = Regex("""(\d+)\s*[-~—]\s*(\d+)\s*周?""").find(item)
            val single = Regex("""^(\d+)\s*周?""").find(item)
            val start: Int
            val end: Int
            when {
                range != null -> {
                    start = range.groupValues[1].toInt()
                    end = range.groupValues[2].toInt()
                }
                single != null -> {
                    start = single.groupValues[1].toInt()
                    end = start
                }
                else -> continue
            }
            if (start < 1 || end < start) continue
            val keep = oddEvenFilter(item)
            for (w in start..end) {
                if (keep(w)) weeks.add(w)
            }
        }
        return weeks.sorted()
    }

    internal fun parseSectionRange(sectionStr: String?): Pair<Int, Int>? {
        val text = sectionStr ?: return null
        val match = Regex("""^\s*(?:第)?(\d+)\s*(?:-\s*(\d+))?\s*节?\s*$""").find(text) ?: return null
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].ifBlank { match.groupValues[1] }.toInt()
        if (start < 1 || end < start) return null
        return start to end
    }

    /**
     * 解析可能包含多段连续节次的写法，如 `5-6,7-8`、`第5-6节、第7-8节`。
     * 教务系统对同一门课跨节课时会用逗号连写；旧实现只认单段，
     * 导致整行解析失败被静默丢弃，表现为「导入后少一节」。
     *
     * 返回每段的 (start, end)；相邻且同属一条课程记录的多段会在合并阶段被拼回连续区间。
     */
    internal fun parseSectionSegments(sectionStr: String?): List<Pair<Int, Int>> {
        val text = sectionStr?.trim() ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        // 单段优先走原逻辑，保持既有行为不变
        parseSectionRange(text)?.let { return listOf(it) }

        val parts = text
            .replace('，', ',')
            .replace('；', ',')
            .replace(';', ',')
            .replace('、', ',')
            .split(',', ' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return parts.mapNotNull { parseSectionRange(it) }
    }

    internal fun normalizeCourseName(rawName: String): String {
        val name = rawName.trim()
        if (name.isEmpty()) return name
        val match = Regex("""^[（(]([^（）()]+)[）)]\s*""").find(name) ?: return name
        val rest = name.substring(match.value.length)
        if (rest.isEmpty()) return name
        return "$rest(${match.groupValues[1]})"
    }

    internal fun applyYangjiangCustomTime(course: ImportCourseJsonModel): ImportCourseJsonModel {
        val position = course.position
        val start = course.startSection
        val end = course.endSection
        val isOtherVenue34 = start == 3 && end == 4 &&
            position.isNotBlank() && !position.contains(GdouYangjiangTimetable.SPECIAL_VENUE)
        return if (isOtherVenue34) {
            course.copy(
                isCustomTime = true,
                customStartTime = GdouYangjiangTimetable.OTHER_VENUE_START,
                customEndTime = GdouYangjiangTimetable.OTHER_VENUE_END
            )
        } else {
            course.copy(isCustomTime = false, customStartTime = null, customEndTime = null)
        }
    }

    private fun parseRow(element: JsonElement): List<ImportCourseJsonModel> {
        val obj = element as? JsonObject ?: return emptyList()
        val name = obj.string("kcmc")?.let(::normalizeCourseName).orEmpty()
        val day = obj.string("xqj")?.toIntOrNull()
        val weeks = parseWeeks(obj.string("zcd"))
        val segments = parseSectionSegments(obj.string("jcs"))
        if (name.isBlank() || day == null || day !in 1..7 || weeks.isEmpty() || segments.isEmpty()) {
            return emptyList()
        }
        val teacher = obj.string("xm") ?: obj.string("jsxm").orEmpty()
        val position = obj.string("cdmc").orEmpty()
        // 一行可能携带多段节次（如 jcs="5-6,7-8"），拆成多门课程后由合并阶段拼回连续区间
        return segments.map { (start, end) ->
            ImportCourseJsonModel(
                name = name,
                teacher = teacher,
                position = position,
                day = day,
                startSection = start,
                endSection = end,
                weeks = weeks
            )
        }
    }

    internal fun mergeAndDistinct(courses: List<ImportCourseJsonModel>): List<ImportCourseJsonModel> {
        if (courses.size <= 1) return courses
        val list = courses.map {
            it.copy(weeks = it.weeks.sorted())
        }.sortedWith(
            compareBy<ImportCourseJsonModel> { it.name }
                .thenBy { it.teacher }
                .thenBy { it.position }
                .thenBy { it.day }
                .thenBy { it.weeks.joinToString(",") }
                .thenBy { it.startSection ?: 0 }
        )
        val step1 = mutableListOf<ImportCourseJsonModel>()
        var current = list.first()
        for (i in 1 until list.size) {
            val next = list[i]
            val same = current.name == next.name &&
                current.teacher == next.teacher &&
                current.position == next.position &&
                current.day == next.day &&
                current.weeks == next.weeks
            when {
                same && current.endSection != null && next.startSection != null &&
                    current.endSection!! + 1 == next.startSection -> {
                    current = current.copy(endSection = next.endSection)
                }
                same && current.startSection == next.startSection && current.endSection == next.endSection -> {
                    // duplicate
                }
                else -> {
                    step1.add(current)
                    current = next
                }
            }
        }
        step1.add(current)

        step1.sortWith(
            compareBy<ImportCourseJsonModel> { it.name }
                .thenBy { it.teacher }
                .thenBy { it.position }
                .thenBy { it.day }
                .thenBy { it.startSection ?: 0 }
                .thenBy { it.endSection ?: 0 }
        )
        val step2 = mutableListOf<ImportCourseJsonModel>()
        var cur = step1.first()
        for (i in 1 until step1.size) {
            val nxt = step1[i]
            val sameSection = cur.name == nxt.name &&
                cur.teacher == nxt.teacher &&
                cur.position == nxt.position &&
                cur.day == nxt.day &&
                cur.startSection == nxt.startSection &&
                cur.endSection == nxt.endSection
            if (sameSection) {
                cur = cur.copy(weeks = (cur.weeks + nxt.weeks).distinct().sorted())
            } else {
                step2.add(cur)
                cur = nxt
            }
        }
        step2.add(cur)
        return step2
    }

    private fun findKbList(root: JsonElement): JsonArray? {
        when (root) {
            is JsonArray -> return root
            is JsonObject -> {
                root["kbList"]?.let { if (it is JsonArray) return it }
                root.values.forEach { child ->
                    findKbList(child)?.let { return it }
                }
            }
            else -> {}
        }
        return null
    }

    internal fun normalizeStartDate(value: String?): String? {
        val match = Regex("""(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})""").find(value ?: "") ?: return null
        val month = match.groupValues[2].padStart(2, '0')
        val day = match.groupValues[3].padStart(2, '0')
        return "${match.groupValues[1]}-$month-$day"
    }

    internal fun findSemesterStartDate(value: JsonElement?): String? {
        if (value == null) return null
        when (value) {
            is JsonPrimitive -> return normalizeStartDate(value.contentOrNull)
            is JsonArray -> {
                val firstWeek = value.firstOrNull { item ->
                    val obj = item as? JsonObject ?: return@firstOrNull false
                    obj.string("zs") == "1" || obj.string("zsmc") == "1"
                } ?: value.firstOrNull()
                findSemesterStartDate(firstWeek)?.let { return it }
                value.forEach { findSemesterStartDate(it)?.let { date -> return date } }
            }
            is JsonObject -> {
                for (field in listOf("zrq", "zcrq", "rq", "ksrq")) {
                    normalizeStartDate(value.string(field))?.let { return it }
                }
                value.values.forEach { findSemesterStartDate(it)?.let { date -> return date } }
            }
        }
        return null
    }

    private fun flattenWeekItems(value: JsonElement): List<JsonObject> {
        return when (value) {
            is JsonArray -> value.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val nested = value["data"] ?: value["list"] ?: value["rows"]
                if (nested != null) flattenWeekItems(nested) else emptyList()
            }
            else -> emptyList()
        }.ifEmpty {
            if (value is JsonArray) value.mapNotNull { it as? JsonObject } else emptyList()
        }
    }

    private fun JsonObject.string(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
