package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.koin.core.annotation.Single
import kotlin.time.Clock

const val GDOU_EXAM_INDEX =
    "$GDOU_JW_BASE/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default"
const val GDOU_EXAM_QUERY =
    "$GDOU_JW_BASE/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"

internal const val EXAM_PAGE_SIZE = 15
private const val MAX_EXAM_PAGES = 100

@Serializable
data class GdouExam(
    val courseName: String,
    val examName: String,
    val date: String,
    val time: String,
    val location: String,
    val campus: String,
    val seat: String,
    val note: String
)

internal data class GdouExamPage(
    val exams: List<GdouExam>,
    val rowCount: Int,
    val records: Int?,
    val totalPages: Int?
)

@Single
class GdouExamClient(
    private val http: GdouHttpClient
) {
    suspend fun fetch(term: GdouTerm): List<GdouExam> {
        val route = http.jwRoute
        val index = http.followRedirects(GDOU_EXAM_INDEX, route = route)
        checkResponse(index)
        return fetchAllExamPages { page -> fetchPage(term, index.url, page, route) }
    }

    private suspend fun fetchPage(term: GdouTerm, referer: String, page: Int, route: GdouRoute): GdouExamPage {
        val response = http.postForm(
            url = GDOU_EXAM_QUERY,
            fields = mapOf(
                "xnm" to term.xnm,
                "xqm" to term.xqm,
                "ksmcdmb_id" to "",
                "kch" to "",
                "kc" to "",
                "ksrq" to "",
                "kkbm_id" to "",
                "_search" to "false",
                "nd" to Clock.System.now().toEpochMilliseconds().toString(),
                "queryModel.showCount" to EXAM_PAGE_SIZE.toString(),
                "queryModel.currentPage" to page.toString(),
                "queryModel.sortName" to " ",
                "queryModel.sortOrder" to "asc",
                "time" to "1"
            ),
            referer = referer,
            origin = route.origin,
            ajax = true,
            route = route
        )
        checkResponse(response)
        return GdouExamParser.parsePage(response.body)
    }

    private fun checkResponse(response: GdouHttpExchange) {
        val loginPage = GdouHtml.looksLikeLoginPage(response.url, response.body)
        if (response.status != 200 || loginPage) {
            error(if (response.status == 901 || loginPage) {
                "教务会话已过期"
            } else {
                "考试信息请求失败：HTTP ${response.status}"
            })
        }
    }
}

/** 按服务端 records/total 续页；元数据缺失时以短页作为结束信号。 */
internal suspend fun fetchAllExamPages(
    fetchPage: suspend (page: Int) -> GdouExamPage
): List<GdouExam> {
    val allExams = mutableListOf<GdouExam>()
    var fetchedRows = 0

    for (page in 1..MAX_EXAM_PAGES) {
        val current = fetchPage(page)
        allExams += current.exams
        fetchedRows += current.rowCount

        val hasMore = when {
            current.records != null && fetchedRows < current.records -> true
            current.totalPages != null && page < current.totalPages -> true
            current.records == null && current.totalPages == null &&
                current.rowCount == EXAM_PAGE_SIZE -> true
            else -> false
        }
        if (!hasMore) return allExams.sortedForDisplay()
        if (current.rowCount == 0) {
            error("考试接口分页数据不完整：第 $page 页为空")
        }
    }
    error("考试记录超过 ${MAX_EXAM_PAGES * EXAM_PAGE_SIZE} 条，已停止分页")
}

private fun List<GdouExam>.sortedForDisplay(): List<GdouExam> = sortedWith(
    compareBy<GdouExam> { it.date.ifBlank { "9999-99-99" } }.thenBy { it.time }
)

object GdouExamParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dateRegex = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")

    fun parse(rawJson: String): List<GdouExam> = parsePage(rawJson).exams.sortedForDisplay()

    internal fun parsePage(rawJson: String): GdouExamPage {
        val root = json.parseToJsonElement(rawJson)
        val rows = findRows(root) ?: error("考试接口未返回考试列表")
        val exams = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val courseName = row.text("kcmc").ifBlank { row.text("kch") }
            if (courseName.isBlank()) return@mapNotNull null

            val rawTime = row.text("kssj").ifBlank { row.text("sksj") }
            val date = normalizeDate(row.text("ksrq")).ifBlank { normalizeDate(rawTime) }
            GdouExam(
                courseName = courseName,
                examName = row.text("ksmc"),
                date = date,
                time = withoutDate(rawTime),
                location = row.text("cdmc").ifBlank { row.text("cdjc") },
                campus = row.text("cdxqmc").ifBlank { row.text("xqmc") },
                seat = row.text("zwh"),
                note = row.text("ksbz").ifBlank { row.text("bzxx") }
            )
        }
        val metadata = root as? JsonObject
        return GdouExamPage(
            exams = exams,
            rowCount = rows.size,
            records = metadata?.metadataInt("totalResult") ?: metadata?.metadataInt("totalCount")
                ?: metadata?.metadataInt("records"),
            totalPages = metadata?.metadataInt("totalPage") ?: metadata?.metadataInt("total")
        )
    }

    private fun findRows(root: JsonElement): JsonArray? = when (root) {
        is JsonArray -> root
        is JsonObject -> {
            listOf("items", "rows", "list").firstNotNullOfOrNull { key -> root[key] as? JsonArray }
                ?: (root["data"]?.let(::findRows))
        }
        else -> null
    }

    private fun JsonObject.metadataInt(key: String): Int? {
        val direct = (this[key] as? JsonPrimitive)?.intOrNull
        val nested = ((this["data"] as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
        return (direct ?: nested)?.takeIf { it >= 0 }
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun normalizeDate(value: String): String {
        val match = dateRegex.find(value) ?: return ""
        val (year, month, day) = match.destructured
        return "$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}"
    }

    private fun withoutDate(value: String): String = value
        .replace(dateRegex, "")
        .trim()
        .removePrefix(" ")
        .trimStart('-', '—', '–', ':', '：')
        .trim()
}
