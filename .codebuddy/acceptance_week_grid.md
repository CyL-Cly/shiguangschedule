# 周视图网格小组件（WeekGrid）验收核对表

验收日期：2026-09-20 | 范围：未提交变更（git status/diff + 新文件）| 构建已通过（不重复构建）

| # | 检查项 | 结论 | 证据 (file:line) | 摘录 / 说明 |
|---|--------|------|------------------|-------------|
| 1.1 | Manifest receiver 与类 FQN 一致 | PASS | androidApp/src/main/AndroidManifest.xml:103-113; androidApp/build.gradle.kts:15; WeekGridNativeProvider.kt:1,15 | `android:name=".widget.week_grid.WeekGridNativeProvider"` + namespace `com.xingheyuzhuan.shiguangschedule` = 实际包 `com.xingheyuzhuan.shiguangschedule.widget.week_grid` |
| 1.2 | meta-data xml 存在且 initialLayout 有效 | PASS | AndroidManifest.xml:111-113; xml/shiguangschedule_week_grid_widget.xml:8; layout/widget_loading_placeholder.xml 存在 | `android:resource="@xml/shiguangschedule_week_grid_widget"`; `initialLayout="@layout/widget_loading_placeholder"` |
| 1.3 | nativeConfigs 条目（provider + render 方法引用） | PASS | WidgetUpdateHelper.kt:109 | `WeekGridNativeProvider::class.java to WeekGridNativeRenderer::render`，签名 (Context, WidgetSnapshot) -> RemoteViews 与其余 4 项一致 |
| 2.1 | week_start_date 安全解析回退 | PASS | WeekGridNativeRenderer.kt:51,155-160 | `parseWeekStart` try/catch，失败回退 previousOrSame(MONDAY)（备注：回退未用配置的 firstDayOfWeek，非阻塞） |
| 2.2 | 时间轴 (start,end) 去重、排序、cap 10 | PASS | WeekGridNativeRenderer.kt:65-69; 常量 :18 | `.map{...}.distinct().sortedBy{it.first}.take(MAX_PERIODS)`，MAX_PERIODS=10 |
| 2.3 | 周一~周五恒 5 列，周六/日有课才追加 | PASS | WeekGridNativeRenderer.kt:72-74 | `weekDays.take(5) + weekDays.drop(5).filter { weekCourses.any {...} }` |
| 2.4 | 过滤 is_skipped | PASS | WeekGridNativeRenderer.kt:56 | `!course.is_skipped && course.date.isNotBlank()` |
| 2.5 | 颜色映射 light/dark，setColorFilter 同时作用于两个 id | PASS | WeekGridNativeRenderer.kt:132-140; layout/widget_week_grid_card.xml:9; layout-night/widget_week_grid_card.xml:9 | `course_color_maps[color_int]`；亮色 id `course_indicator`、暗色 id `course_indicator_dark` 与 layout/layout-night 变体一一对应 |
| 2.6 | 今天高亮仅在日期匹配时 | PASS | WeekGridNativeRenderer.kt:90,99-104 | `if (day.toString() == todayStr)` → `setInt(tv_day_name,"setBackgroundResource", R.drawable.widget_day_header_bg_today)` |
| 2.7 | PendingIntent IMMUTABLE | PASS | WeekGridNativeRenderer.kt:27-30 | `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` |
| 2.8 | current_week<=0 显示 container_full_status | PASS | WeekGridNativeRenderer.kt:34-46 | 假期遮罩 + title_vacation / widget_vacation_expecting |
| 2.9 | 无课空状态处理 | **FAIL** | WeekGridNativeRenderer.kt:110-113; layout/widget_week_grid.xml:48-58 | 仅 `setViewVisibility(tv_empty_status, VISIBLE)`，从未 `setTextViewText`；布局无 `android:text`（仅 tools:text）→ 空状态渲染为**空白** |
| 2.10 | addView 循环前 removeAllViews | PASS | WeekGridNativeRenderer.kt:87 | `rv.removeAllViews(R.id.container_rows)` 先于表头/行添加；每次渲染均为全新 RemoteViews 实例 |
| 2.11 | **快照数据覆盖整周** | **FAIL** | WidgetUpdateHelper.kt:53-55,95-100,109 | `dbCourses = getWidgetCoursesByDateRange(today, tomorrow)` 仅含今明两天 → snapshot.courses 喂给 WeekGrid 后，周一~周五大多为空、周六/日列**永不出现**；Synchronizer 的 14 天同步形同虚设。应改为 weekStart..weekStart+13（其余 4 个渲染器均按 `it.date == todayStr/tomorrowStr` 过滤，加宽范围对它们安全，已核实 Tiny:45 / Compact:64-68 / DoubleDays:56-70 / ListVertical:49-55） |
| 3.1 | RemoteViews 兼容视图类型 | PASS | widget_week_grid.xml / row / header_cell / cell / card | 仅 FrameLayout/LinearLayout/RelativeLayout/TextView/ImageView；tools: 属性运行时忽略 |
| 3.2 | setTextViewText / setInt 方法名有效 | PASS | WeekGridNativeRenderer.kt:100-103,134-139 | `"setBackgroundResource"`(View, TextView 可用)、`"setColorFilter"`(ImageView)；与既有 ListVerticalNativeRenderer.kt:114-119 同模式 |
| 4.1 | 新布局全部资源引用可解析 | PASS | widget_week_grid.xml:12,26,36...; header_cell:18,28; card:13,26,42 | @drawable/widget_bg_rounded(存在)、@color/widget_text_primary/secondary/hint(values/colors.xml:11-13)、@drawable/widget_course_card_shape、@drawable/widget_day_header_bg_today、@color/widget_bg(preview 用, colors.xml:11) |
| 4.2 | layout-night 卡片使用独立 id | PASS | layout-night/widget_week_grid_card.xml:9 vs layout/widget_week_grid_card.xml:9 | `course_indicator_dark` / `course_indicator`，setColorFilter 各命中对应变体 |
| 4.3 | 4 个 locale 同 key | PASS | values/strings.xml:126, values-en:126, values-zh-rCN:126, values-zh-rTW:126 | `widget_name_week_grid` 四处均添加 |
| 4.4 | provider xml 属性有效 | PASS | xml/shiguangschedule_week_grid_widget.xml:3-11 | minWidth/minHeight 存在；targetCellWidth/Height 仅 API31+ 生效（可接受）；updatePeriodMillis/resizeMode/widgetCategory 正常 |
| 5.1 | 周起始算法与 WidgetRepository 一致（previousOrSame） | PASS | WidgetDataSynchronizer.kt:283-290; WidgetRepository.kt:120-127; WidgetUpdateHelper.kt:141-142 | 三处均为“回退到 firstDayOfWeek 当天（含当天）”语义；firstDayOfWeekInt 均为 ISO 1-7（WidgetRepository.kt:91） |
| 5.2 | 开学前钳制保留 | PASS | WidgetDataSynchronizer.kt:207-216 | `if (weekStartDate < alignedSemesterStartDate) alignedSemesterStartDate` |
| 5.3 | 14 天窗口覆盖完整本周 | PASS | WidgetDataSynchronizer.kt:58,210-219 | 起点即本周起始日，14>=7；firstDayOfWeek=周日：窗口周日..次周六；=周一：周一..次周日，均覆盖全周 |
| 6.1 | R 引用/包名与导入无编译隐患 | PASS | WeekGridNativeProvider.kt:1-9; WeekGridNativeRenderer.kt:1-13 | 包与导入模式与 ListVerticalNativeProvider/Renderer 完全一致（MainActivity/R/WidgetSnapshot/WorkManagerHelper/updateAllWidgets）；构建已通过 |

## 必须修复（阻塞 READY）

1. **快照数据范围**（WidgetUpdateHelper.kt:53-55）：将 `getWidgetCoursesByDateRange(today, tomorrow)` 改为覆盖本周（建议 `weekStartDate` 至 `weekStartDate.plusDays(13)`，并把 weekStartDate 计算提到查询之前）。否则周视图除今明两列外全部空白、周末列永不出现。
2. **空状态无文案**（WeekGridNativeRenderer.kt:110-113 + widget_week_grid.xml:48-58）：`tv_empty_status` 从未设置文本，无课周显示空白区域。渲染时需 `setTextViewText(R.id.tv_empty_status, ...)`。

## 非阻塞备注

- `parseWeekStart` 回退路径硬编码周一（WeekGridNativeRenderer.kt:159），与可配置 firstDayOfWeek 不一致；仅快照缺失/损坏时触发。
- Synchronizer 14 天窗口的意义依赖于修复项 1；若仅修复渲染端而不改查询范围，14 天数据无法进入快照。
