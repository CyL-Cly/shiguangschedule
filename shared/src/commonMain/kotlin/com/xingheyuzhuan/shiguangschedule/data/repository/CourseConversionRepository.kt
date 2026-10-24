package com.xingheyuzhuan.shiguangschedule.data.repository

import androidx.room3.Transaction
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeekDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlotDao
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.CourseConfigJsonModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.CourseTableExportModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.CourseTableImportModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.ExportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.ImportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.TimeSlotJsonModel
import com.xingheyuzhuan.shiguangschedule.tool.CalendarAccountManager
import com.xingheyuzhuan.shiguangschedule.tool.IcsExportTool
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.core.annotation.Single
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 课表转换仓库，负责处理课程数据的导入、导出以及 ICS 生成等逻辑。
 */
@OptIn(ExperimentalUuidApi::class)
@Single
class CourseConversionRepository(
    private val courseDao: CourseDao,
    private val courseWeekDao: CourseWeekDao,
    private val timeSlotDao: TimeSlotDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) {
    private val timeRegex = Regex("^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")

    private fun parseToMinutes(timeStr: String): Int {
        val time = LocalTime.parse(timeStr)
        return time.hour * 60 + time.minute
    }

    private fun validateTimeSlotsOrThrow(timeSlots: List<TimeSlotJsonModel>) {
        if (timeSlots.isEmpty()) return

        val sortedSlots = timeSlots.sortedBy { it.number }
        var lastEndTimeInMinutes = -1

        sortedSlots.forEachIndexed { index, slot ->
            val expectedNumber = index + 1
            if (slot.number != expectedNumber) {
                throw IllegalArgumentException("时间段编号不连续或未从1开始")
            }

            if (!timeRegex.matches(slot.startTime) || !timeRegex.matches(slot.endTime)) {
                throw IllegalArgumentException("时间格式错误")
            }

            val startMinutes = parseToMinutes(slot.startTime)
            val endMinutes = parseToMinutes(slot.endTime)

            if (startMinutes >= endMinutes) {
                throw IllegalArgumentException("开始时间必须早于结束时间")
            }

            if (lastEndTimeInMinutes != -1 && startMinutes < lastEndTimeInMinutes) {
                throw IllegalArgumentException("时间段配置存在重叠")
            }

            lastEndTimeInMinutes = endMinutes
        }
    }

    private fun validateCustomCourseTimeOrThrow(course: ImportCourseJsonModel) {
        if (!course.isCustomTime) return

        val startTime = course.customStartTime
        val endTime = course.customEndTime

        if (startTime.isNullOrBlank() || endTime.isNullOrBlank()) {
            throw IllegalArgumentException("自定义时间不能为空")
        }

        if (!timeRegex.matches(startTime) || !timeRegex.matches(endTime)) {
            throw IllegalArgumentException("自定义时间格式错误")
        }

        val startMinutes = parseToMinutes(startTime)
        val endMinutes = parseToMinutes(endTime)

        if (startMinutes >= endMinutes) {
            throw IllegalArgumentException("自定义开始时间必须早于结束时间")
        }
    }

    private fun getOrAssignColorByName(
        jsonCourse: ImportCourseJsonModel,
        colorSize: Int,
        nameToColorMap: MutableMap<String, Int>,
        getNextAutoColor: () -> Int
    ): Int {
        val trimmedName = jsonCourse.name.trim()
        val existingColor = nameToColorMap[trimmedName]

        if (existingColor != null) return existingColor

        val importedColor = jsonCourse.color
        val finalColor = if (importedColor != null && importedColor in 0 until colorSize) {
            importedColor
        } else {
            getNextAutoColor()
        }

        nameToColorMap[trimmedName] = finalColor
        return finalColor
    }

    /**
     * 将超出时间段表范围的节次收敛到合法区间。
     *
     * 渲染层 `mergeCourses` 按 `number` 精确匹配时间段，匹配不到会直接丢弃该课程，
     * 且不会给出任何提示。导入阶段若放任越界节次入库，用户会看到「导入成功但课表少课」。
     *
     * 具体实现见 [CourseSectionClamp]，此处仅负责取边界并委托。
     *
     * @return 收敛后的 (start, end)；若时间段表为空则原样返回，交由渲染层兜底。
     */
    private suspend fun resolveSectionBounds(tableId: String): Pair<Int, Int>? {
        val slotNumbers = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()
            .map { it.number }
        if (slotNumbers.isEmpty()) return null
        return slotNumbers.min() to slotNumbers.max()
    }

    private fun clampSections(
        start: Int?,
        end: Int?,
        bounds: Pair<Int, Int>?
    ): Pair<Int?, Int?> = CourseSectionClamp.clamp(start, end, bounds)

    /**
     * 收敛节次，并在发生平移时产出一条说明。
     *
     * 平移后的课程会落在与教务公告不同的时间上（如 13-14 节落到 9-10 节），
     * 用户无从察觉，因此把差异回传给调用方写入同步日志。
     */
    private fun clampWithNote(
        course: ImportCourseJsonModel,
        bounds: Pair<Int, Int>?
    ): Pair<Pair<Int?, Int?>, String?> {
        val original = course.startSection to course.endSection
        val clamped = clampSections(course.startSection, course.endSection, bounds)
        // bounds 为空时 clamp 原样返回，因此发生变化必然意味着 bounds 非空
        if (clamped == original || bounds == null) return clamped to null
        val note = "课程《${course.name}》周${course.day} 节次 ${original.first}-${original.second} " +
            "超出时间段表 (${bounds.first}-${bounds.second})，已收敛为 ${clamped.first}-${clamped.second}"
        return clamped to note
    }

    @Transaction
    suspend fun importCoursesFromList(
        tableId: String,
        coursesJsonModel: List<ImportCourseJsonModel>
    ): List<String> {
        coursesJsonModel.forEach { validateCustomCourseTimeOrThrow(it) }

        val currentStyle = styleSettingsRepository.styleFlow.first()
        val colorSize = currentStyle.courseColorMaps.size

        courseDao.deleteCoursesByTableId(tableId)

        val courseEntities = ArrayList<Course>(coursesJsonModel.size)
        val courseWeekEntities = mutableListOf<CourseWeek>()
        val sectionBounds = resolveSectionBounds(tableId)
        val clampNotes = mutableListOf<String>()

        val nameToColorMap = mutableMapOf<String, Int>()
        var colorOffset = if (colorSize > 0) Random.nextInt(colorSize) else 0

        coursesJsonModel.forEach { jsonCourse ->
            val courseId = Uuid.random().toString()

            val courseIndex = getOrAssignColorByName(
                jsonCourse = jsonCourse,
                colorSize = colorSize,
                nameToColorMap = nameToColorMap,
                getNextAutoColor = {
                    val next = if (colorSize > 0) colorOffset % colorSize else 0
                    colorOffset++
                    next
                }
            )
            val (startSection, endSection) = if (jsonCourse.isCustomTime) {
                jsonCourse.startSection to jsonCourse.endSection
            } else {
                val (clamped, note) = clampWithNote(jsonCourse, sectionBounds)
                note?.let { clampNotes.add(it) }
                clamped
            }

            courseEntities.add(
                Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = jsonCourse.name,
                    teacher = jsonCourse.teacher,
                    position = jsonCourse.position,
                    day = jsonCourse.day,
                    startSection = startSection,
                    endSection = endSection,
                    isCustomTime = jsonCourse.isCustomTime,
                    customStartTime = jsonCourse.customStartTime,
                    customEndTime = jsonCourse.customEndTime,
                    colorInt = courseIndex,
                    remark = null
                )
            )

            jsonCourse.weeks.forEach { week ->
                courseWeekEntities.add(
                    CourseWeek(courseId = courseId, weekNumber = week)
                )
            }
        }

        if (courseEntities.isNotEmpty()) courseDao.insertAll(courseEntities)
        if (courseWeekEntities.isNotEmpty()) courseWeekDao.insertAll(courseWeekEntities)
        return clampNotes
    }

    /**
     * 从一个完整的 JSON 模型导入课表数据。
     * 逻辑说明：
     * 1. 课程数据（courses）：始终清空并重新导入。
     * 2. 时间段数据（timeSlots）：仅在 JSON 包含有效数据时覆盖，否则保留本地现状。
     * 3. 配置信息（config）：仅在 JSON 包含有效数据时覆盖，且会保留本地的 showWeekends 设置。
     *
     * @return 节次被收敛的课程说明，供调用方写入日志；无越界时为空列表。
     */
    @Transaction
    suspend fun importCourseTableFromJson(
        tableId: String,
        courseTableJsonModel: CourseTableImportModel
    ): List<String> {
        courseTableJsonModel.courses.forEach { validateCustomCourseTimeOrThrow(it) }
        courseTableJsonModel.timeSlots?.let { validateTimeSlotsOrThrow(it) }

        val currentStyle = styleSettingsRepository.styleFlow.first()
        val colorSize = currentStyle.courseColorMaps.size

        // 处理课程数据（始终清空原有课程）
        courseDao.deleteCoursesByTableId(tableId)

        val courseEntities = ArrayList<Course>(courseTableJsonModel.courses.size)
        val courseWeekEntities = mutableListOf<CourseWeek>()

        // 时间段可能随后被 JSON 覆盖，因此优先用 JSON 中的时间段计算边界，
        // 否则沿用库中现有的时间段，避免用旧边界去夹取即将被替换的课表。
        val sectionBounds = courseTableJsonModel.timeSlots
            ?.map { it.number }
            ?.takeIf { it.isNotEmpty() }
            ?.let { it.min() to it.max() }
            ?: resolveSectionBounds(tableId)
        val clampNotes = mutableListOf<String>()

        val nameToColorMap = mutableMapOf<String, Int>()
        var colorOffset = if (colorSize > 0) Random.nextInt(colorSize) else 0

        courseTableJsonModel.courses.forEach { jsonCourse ->
            val courseId = jsonCourse.id ?: Uuid.random().toString()

            val courseIndex = getOrAssignColorByName(
                jsonCourse = jsonCourse,
                colorSize = colorSize,
                nameToColorMap = nameToColorMap,
                getNextAutoColor = {
                    val next = if (colorSize > 0) colorOffset % colorSize else 0
                    colorOffset++
                    next
                }
            )
            val (startSection, endSection) = if (jsonCourse.isCustomTime) {
                jsonCourse.startSection to jsonCourse.endSection
            } else {
                val (clamped, note) = clampWithNote(jsonCourse, sectionBounds)
                note?.let { clampNotes.add(it) }
                clamped
            }

            courseEntities.add(
                Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = jsonCourse.name,
                    teacher = jsonCourse.teacher,
                    position = jsonCourse.position,
                    day = jsonCourse.day,
                    startSection = startSection,
                    endSection = endSection,
                    isCustomTime = jsonCourse.isCustomTime,
                    customStartTime = jsonCourse.customStartTime,
                    customEndTime = jsonCourse.customEndTime,
                    colorInt = courseIndex,
                    remark = jsonCourse.remark?.take(300)
                )
            )

            jsonCourse.weeks.forEach { week ->
                courseWeekEntities.add(
                    CourseWeek(courseId = courseId, weekNumber = week)
                )
            }
        }

        // 处理时间段数据（仅在有数据时覆盖）
        val jsonTimeSlots = courseTableJsonModel.timeSlots
        if (!jsonTimeSlots.isNullOrEmpty()) {
            timeSlotDao.deleteAllTimeSlotsByCourseTableId(tableId)

            val timeSlotEntities = jsonTimeSlots.map { jsonTimeSlot ->
                TimeSlot(
                    number = jsonTimeSlot.number,
                    startTime = jsonTimeSlot.startTime,
                    endTime = jsonTimeSlot.endTime,
                    courseTableId = tableId,
                    alias = jsonTimeSlot.alias?.take(5)
                )
            }
            timeSlotDao.insertAll(timeSlotEntities)
        }

        // 统一执行课程数据插入
        if (courseEntities.isNotEmpty()) courseDao.insertAll(courseEntities)
        if (courseWeekEntities.isNotEmpty()) courseWeekDao.insertAll(courseWeekEntities)

        // 处理配置数据
        val configJson = courseTableJsonModel.config
        if (configJson != null) {
            val currentConfig = appSettingsRepository.getCourseConfigOnce(tableId)
            val updatedConfig = CourseTableConfig(
                courseTableId = tableId,
                showWeekends = currentConfig?.showWeekends ?: false,
                semesterStartDate = configJson.semesterStartDate,
                semesterTotalWeeks = configJson.semesterTotalWeeks,
                defaultClassDuration = configJson.defaultClassDuration,
                defaultBreakDuration = configJson.defaultBreakDuration,
                firstDayOfWeek = configJson.firstDayOfWeek
            )
            appSettingsRepository.insertOrUpdateCourseConfig(updatedConfig)
        }
        return clampNotes
    }

    /**
     * 导入预设时间段
     */
    @Transaction
    suspend fun importTimeSlots(
        tableId: String,
        timeSlots: List<TimeSlotJsonModel>
    ) {
        validateTimeSlotsOrThrow(timeSlots)

        val timeSlotEntities = timeSlots.map { jsonModel ->
            TimeSlot(
                number = jsonModel.number,
                startTime = jsonModel.startTime,
                endTime = jsonModel.endTime,
                courseTableId = tableId
            )
        }
        timeSlotDao.deleteAllTimeSlotsByCourseTableId(tableId)
        if (timeSlotEntities.isNotEmpty()) {
            timeSlotDao.insertAll(timeSlotEntities)
        }
    }

    /**
     * 从 JSON 模型更新指定课表的配置。
     */
    @Transaction
    suspend fun importCourseConfig(
        tableId: String,
        configJsonModel: CourseConfigJsonModel
    ) {
        val currentConfig = appSettingsRepository.getCourseConfigOnce(tableId)

        val updatedConfig = CourseTableConfig(
            courseTableId = tableId,
            showWeekends = currentConfig?.showWeekends ?: false,
            semesterStartDate = configJsonModel.semesterStartDate,
            semesterTotalWeeks = configJsonModel.semesterTotalWeeks,
            defaultClassDuration = configJsonModel.defaultClassDuration,
            defaultBreakDuration = configJsonModel.defaultBreakDuration,
            firstDayOfWeek = configJsonModel.firstDayOfWeek
        )

        appSettingsRepository.insertOrUpdateCourseConfig(updatedConfig)
    }

    /**
     * 将指定课表下的所有数据导出为一个完整的 JSON 模型。
     */
    suspend fun exportCourseTableToJson(tableId: String): CourseTableExportModel? {
        val coursesWithWeeks = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        if (coursesWithWeeks.isEmpty() && appSettingsRepository.getCourseConfigOnce(tableId) == null) {
            return null
        }

        val exportCourses = coursesWithWeeks.map { courseWithWeeks ->
            val course = courseWithWeeks.course
            val weeks = courseWithWeeks.weeks.map { it.weekNumber }
            val colorIndex = course.colorInt

            ExportCourseJsonModel(
                id = course.id,
                name = course.name,
                teacher = course.teacher,
                position = course.position,
                day = course.day,
                startSection = course.startSection,
                endSection = course.endSection,
                color = colorIndex,
                weeks = weeks,
                isCustomTime = course.isCustomTime,
                customStartTime = course.customStartTime,
                customEndTime = course.customEndTime,
                remark = course.remark
            )
        }

        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()
        val exportTimeSlots = timeSlots.map { timeSlot ->
            TimeSlotJsonModel(
                number = timeSlot.number,
                startTime = timeSlot.startTime,
                endTime = timeSlot.endTime,
                alias = timeSlot.alias?.take(5)
            )
        }

        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)
        val configToExport = courseConfig ?: CourseTableConfig(courseTableId = tableId)

        val exportConfig = CourseConfigJsonModel(
            semesterStartDate = configToExport.semesterStartDate,
            semesterTotalWeeks = configToExport.semesterTotalWeeks,
            defaultClassDuration = configToExport.defaultClassDuration,
            defaultBreakDuration = configToExport.defaultBreakDuration,
            firstDayOfWeek = configToExport.firstDayOfWeek
        )

        return CourseTableExportModel(
            courses = exportCourses,
            timeSlots = exportTimeSlots,
            config = exportConfig
        )
    }

    /**
     * 将指定课表下的所有课程数据导出为 ICS 日历文件的内容字符串。
     */
    suspend fun exportToIcsString(tableId: String, alarmMinutes: Int?): String? {
        val courses = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()

        val appSettings = appSettingsRepository.getAppSettingsOnce()
        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)
        val semesterStartDate = courseConfig?.semesterStartDate?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        }

        if (semesterStartDate == null || courseConfig.semesterTotalWeeks <= 0) {
            return null
        }

        return IcsExportTool.generateIcsFileContent(
            courses = courses,
            timeSlots = timeSlots,
            semesterStartDate = semesterStartDate,
            semesterTotalWeeks = courseConfig.semesterTotalWeeks,
            firstDayOfWeekInt = courseConfig.firstDayOfWeek,
            alarmMinutes = alarmMinutes,
            skippedDates = appSettings.skippedDates
        )
    }

    /**
     * 一键同步当前课表到系统日历
     */
    suspend fun syncCurrentTableToSystemCalendar(): Boolean {
        val appSettings = appSettingsRepository.getAppSettingsOnce()
        val currentTableId = appSettings.currentCourseTableId
        if (currentTableId.isEmpty()) return true

        val courses = courseDao.getCoursesWithWeeksByTableId(currentTableId).first()
        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(currentTableId).first()
        val alarmMinutes = appSettings.remindBeforeMinutes
        val courseConfig = appSettingsRepository.getCourseConfigOnce(currentTableId)

        val semesterStartDate = courseConfig?.semesterStartDate?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        } ?: return true

        if (courseConfig.semesterTotalWeeks <= 0 || courses.isEmpty()) {
            return true
        }

        return CalendarAccountManager.syncCurrentTableToSystemCalendar(
            courses = courses,
            timeSlots = timeSlots,
            semesterStartDate = semesterStartDate,
            semesterTotalWeeks = courseConfig.semesterTotalWeeks,
            firstDayOfWeekInt = courseConfig.firstDayOfWeek,
            alarmMinutes = alarmMinutes,
            skippedDates = appSettings.skippedDates
        )
    }
}