# 拾光课程表（shiguangschedule）仓库结构与文件作用说明

> 基于 `main` 分支（HEAD: `39672e7`）阅读整理。
> 项目是 **Kotlin Multiplatform + Compose Multiplatform** 应用：UI 与业务逻辑几乎全部在 `shared` 模块，`androidApp` / `desktopApp` / `iosApp` 只是各平台的薄壳入口。

---

## 一、技术栈一览

| 领域 | 选型 | 版本（见 `gradle/libs.versions.toml`） |
| --- | --- | --- |
| 语言 / 构建 | Kotlin + Gradle KTS（Version Catalog） | Kotlin 2.4.20、AGP 9.4.0、KSP 2.3.12 |
| UI | Compose Multiplatform + Material3 + Material3 Adaptive | CMP 1.12.0、material3 1.9.0 |
| 导航 | Navigation 3（`navigation3-ui`）+ SavedState | 1.1.1 |
| 依赖注入 | Koin（注解 / compiler plugin） | 4.2.2 / koin-plugin 1.2.1 |
| 数据库 | Room 3（多平台，KSP）+ SQLite（bundled/framework） | room3 3.0.3 |
| 键值/配置 | DataStore Preferences + Proto DataStore（Okio 存储） | 1.2.1 |
| 序列化 | kotlinx.serialization（json/cbor）+ Wire Protobuf | 1.11.0 / wire 6.4.7 |
| 网络 | Ktor Client（CIO / Darwin） | 3.5.2 |
| 文件 IO | Okio（含 openZip 虚拟文件系统） | 3.18.2 |
| Git（适配仓库同步） | kgit（项目自研） | 1.0.2 |
| 图片 | Coil 3 | 3.6.2 |
| 后台/桌面部件 | WorkManager + AppWidget + RemoteViews | work 2.11.2 |
| SDK | minSdk 26（Android 8.0）/ compileSdk 37 | |

---

## 二、顶层目录与文件

| 路径 | 作用 |
| --- | --- |
| `settings.gradle.kts` | 默认声明三个模块：`:androidApp`、`:shared`、`:desktopApp`；传入 `-PandroidOnly` 时不 include `:desktopApp`。统一仓库源（google / mavenCentral）。 |
| `build.gradle.kts`（根） | 根工程配置（插件声明等）。 |
| `gradle/libs.versions.toml` | **全部依赖版本唯一来源**，改依赖先看这里。 |
| `gradlew` / `gradlew.bat` | Gradle Wrapper 脚本。 |
| `README.md` | 功能介绍、版本说明、贡献方式、相关生态仓库链接。 |
| `LICENSE` | 开源协议。 |
| `shared/` | **核心**：跨平台 UI + 数据 + 业务，KMP 模块。 |
| `androidApp/` | Android 壳工程：Activity、Application、桌面小部件、通知/勿扰服务。 |
| `desktopApp/` | 桌面（JVM）入口：`main.kt` 用 `Window { App() }`。 |
| `iosApp/` | iOS 壳工程（Xcode 工程 + SwiftUI `ContentView` 承载 `MainViewController`）。 |
| `picture/` | README 用的截图。 |
| `fastlane/` | 应用商店元数据（标题/描述/截图等）。 |
| `.github/` | CI：`android-build.yml`（构建）、`android-release.yml`（发布）、`check-pr-source.yml`（校验 PR 必须发到 dev）、`actions/changelog-generator`（自动生成更新日志）。 |
| `.github/scripts/build_contributors.sh` | 生成贡献者列表。 |

---

## 三、`shared` 模块（核心）

源码集：`commonMain`（跨平台）+ `androidMain` / `jvmMain` / `iosMain`（`expect` 的 `actual` 实现）。

### 3.1 入口与导航

| 文件 | 作用 |
| --- | --- |
| `App.kt` | 应用根 Composable：等待 `SettingsViewModel` 就绪 → 套 `ShiguangScheduleTheme` → 进入 `AppNavigation`；`AppNavigation` 用 Navigation3 的 `NavDisplay` 管理返回栈与转场动画（主页之间无动画，二级页横向滑动）；`ScreenContent` 负责 `Destination → 具体 Screen` 的映射。 |
| `Navigation.kt` | 定义 `Destination` 密封接口（`NavKey`）、一级页面子接口 `MainDestination`、动态传参页（`AdapterSelection` / `WebView` / `AddEditCourse` / `CourseManagementDetail`）、导航元数据 `IsMainScreenKey`、SavedState 序列化模块。 |
| `navigation/AppChannels.kt` | `AddEditCourseChannel`：用 `Channel` 一次性把「预设课程数据」从课表页传给「添加/编辑课程」页（跨页传参的补充手段）。 |

### 3.2 `data/` 数据层

**db/main（主业务库 `MainAppDatabase`，version 5）**

| 文件 | 作用 |
| --- | --- |
| `CourseTable.kt` | 课表实体（一张课表，如「大一上」）。 |
| `Course.kt` | 课程实体，外键关联课表；支持「节次」与「自定义 HH:MM 时间」两种模式，含颜色、老师、地点、备注。 |
| `CourseWeek.kt` | 课程 ↔ 周次的多对多关联表（决定课程在哪几周出现）。 |
| `TimeSlot.kt` | 作息时间节次表（每节课起止时间、别名），与课表绑定。 |
| `CourseTableConfig.kt` / `CourseTableConfigDao.kt` | 课表级配置（开学日期、总周数、周起始日等）。 |
| `CourseDao/CourseTableDao/CourseWeekDao/TimeSlotDao` | 各实体的 DAO 查询接口。 |
| `CourseWithWeeks.kt` | 课程 + 周次的聚合结果（Room Relation）。 |
| `MainAppDatabase.kt` | Room 数据库声明、DAO 出口、3→4→5 自动迁移（v5 删除 `app_settings` 表，设置已迁到 DataStore）。 |
| `DatabaseMigrations.kt` | 迁移相关辅助定义。 |
| `MainAppDatabase.android/jvm/ios.kt` | 各平台 `actual` 实现（不同 SQLite 驱动）。 |

**db/widget（小部件专用库 `WidgetDatabase`，version 3）**

| 文件 | 作用 |
| --- | --- |
| `WidgetCourse.kt` | 展平后的小部件课程数据（按具体日期存储，直接可渲染）。 |
| `WidgetAppSettings.kt` | 小部件所需的应用设置快照（当前周等）。 |
| `WidgetCourseDao/WidgetAppSettingsDao` | 对应 DAO。 |
| `WidgetDatabase.kt` + 各平台 actual | 小部件库实例，Android 端由 RemoteViews 直接读取。 |

**di（Koin 依赖注入）**

| 文件 | 作用 |
| --- | --- |
| `SharedModule.kt` | 顶层模块：组合 `DatabaseModule`、`DataStoreModule`，`@ComponentScan` 自动扫描全包；提供 `FileSystem` 与 `FilesDir` / `CacheDir` 路径。 |
| `DatabaseModule.kt` | 提供两个数据库与各 DAO。 |
| `DataStoreModule.kt` | 提供 4 个 DataStore：课表样式（Proto）、`school_history`、`app_settings`、`api_config`。 |
| `PlatformServices.kt`（+ android/jvm/ios） | 平台服务装配点（`AppStorage` 等 expect/actual）。 |

**model（数据模型）**

| 文件 | 作用 |
| --- | --- |
| `AppSettingsModel.kt` | 全局设置模型：主题、启动页（`StartScreen`）、勿扰自动化模式（`AutoControlMode`）、跳过日期（节假日）等。 |
| `ScheduleGridStyle.kt` | 课表网格样式的 Kotlin 侧模型（`DualColor` 明暗双色、格子尺寸/圆角/透明度、显示开关等）。 |
| `CourseImportExport.kt` | JSON 课表导入导出模型与转换。 |
| `SchoolHistoryModel.kt` | 教务导入历史（最近使用的学校/分类）。 |
| `RepoInfo.kt` | 适配仓库信息（`RepositoryInfo` + `RepoType`：在线 Git / 离线内置）。 |
| `ContributionList.kt` | 贡献者列表模型。 |

**repository（业务仓库，ViewModel 只与它打交道）**

| 文件 | 作用 |
| --- | --- |
| `CourseTableRepository.kt` | 课表 + 课程 + 周次的增删改查与组合查询，课表页数据源。 |
| `TimeSlotRepository.kt` | 作息节次的读取与整表替换。 |
| `AppSettingsRepository.kt` | 应用设置的读写（DataStore）。 |
| `StyleSettingsRepository.kt` | 课表样式读写（Proto DataStore）+ 样式备份信封 `StyleBackupEnvelope` + 序列化器 `ScheduleStyleSerializer`；改动会发 `styleUpdatedFlow`。 |
| `CourseConversionRepository.kt` | **教务导入结果 → 课程数据**的转换落库（识别课程、作息、开学日期等）。 |
| `SchoolRepository.kt` | 学校/适配脚本索引的读取；`SchoolHistoryRepository` 管理导入历史。 |
| `BackupRepository.kt` | 备份与恢复：按模块（`BackupModule`）打包/解包 `AppBackupPackage`（数据库 + 样式 + 设置）。 |
| `WidgetRepository.kt` | 小部件库的读写。 |
| `ApiConfigRepository.kt` | API 配置（如节假日接口）持久化。 |
| `GitRepository.kt` | Git 操作抽象（拉取适配仓库）。 |
| `ContributionRepository.kt` | 拉取贡献者列表。 |

**api（外部接口）**

| 文件 | 作用 |
| --- | --- |
| `api/date/ApiDateImporter.kt` | 通过 `timor.tech` 节假日 API 拉取全年节假日，写入 `skippedDates`，避免假期误提醒。 |
| `api/webdav/WebDavClient.kt`、`WebDavConfig.kt` | WebDAV 云备份/同步客户端与配置。 |

**sync**

| 文件 | 作用 |
| --- | --- |
| `sync/WidgetDataSynchronizer.kt` | **核心同步器**：监听设置/课表/节次变化 → 计算未来 7 天的课程 → 写入 Widget 数据库 → 发 `syncCompletedFlow`（Android 端据此刷新桌面部件与定时任务）。 |

### 3.3 `tool/` 工具层

| 文件 | 作用 |
| --- | --- |
| `GitUpdater.kt` | 基于 kgit 从 Git 仓库增量更新「学校适配脚本 + index 索引」，带进度监听；本地目录 `filesDir/repo/{index,schools}`。 |
| `ResourceInitializerManager.kt` | 启动时把内置离线适配资源 ZIP（`offline_schools.zip`）解压到 `filesDir/repo`，并清理分享/下载临时缓存。 |
| `UpdateTool.kt` | 应用自身更新检查：`UpdatePlatform`、`UpdateStatus`、GitHub Release API 模型，各平台 `PlatformUpdateStrategy`。 |
| `IcsExportTool.kt` | 生成通用 `.ics` 日历文件供其他设备/日历应用导入。 |
| `CalendarAccountManager.kt`（android/jvm） | 系统日历账户同步（Android 端真实实现，JVM 端空实现）。 |
| `FileManager.kt`（android） | 文件选择/保存/分享的跨平台接口与回调。 |
| `SecureCrypto.kt`（android/jvm） | 加密存储（如 WebDAV 凭据）。 |
| `ZipUtils.kt`（各平台） | ZIP 解压/打包。 |

### 3.4 `ui/` 界面层

| 目录 / 文件 | 作用 |
| --- | --- |
| `schedule/WeeklyScheduleScreen.kt` + `WeeklyScheduleViewModel.kt` | **周课表主页**：左右切换周次、长按拖动课程块。 |
| `schedule/components/CourseBlock.kt` | 单个课程块渲染。 |
| `schedule/components/ScheduleGrid.kt`、`ScheduleGridComponents.kt`、`ScheduleGridStyleComposed.kt` | 课表网格绘制、节次/时间列、样式应用。 |
| `schedule/components/CourseDetailBottomSheet.kt`、`WeekSelectorBottomSheet.kt`、`FloatingCourseBar.kt` | 课程详情底部弹窗、周次快速选择器、悬浮课程条。 |
| `today/TodayScheduleScreen.kt` + `ViewModel` | 今日课表页。 |
| `settings/SettingsScreen.kt` + `AppSettingsViewModel.kt` | 「我的/设置」主页与全局设置 VM。 |
| `settings/course/AddEditCourseScreen.kt`、`AddEditCourseViewModel.kt`、`CourseTimeDialogs.kt`、`CourseSchemeCard.kt`、`CourseOtherSelectors.kt` | 添加/编辑单条课程（节次或自定义时间、周次、颜色）。 |
| `settings/coursemanagement/CourseNameList*`、`CourseInstanceList*` | 全局课程管理：按课程名聚合 → 查看该课程的所有实例。 |
| `settings/coursetables/ManageCourseTables*` | 多课表管理（新建/重命名/删除/切换）。 |
| `settings/time/TimeSlotManagementScreen.kt` + `TimeSlotViewModel.kt` | 作息时间（节次）管理。 |
| `settings/style/StyleSettings*` | 课表个性化：格子高度/圆角/间距/透明度、背景图、文字样式、显示开关。 |
| `settings/themesettings/ThemeSettingsScreen.kt` | 主题与动态取色。 |
| `settings/notification/*` | 课程提醒与自动化：通用卡片 `GeneralSettingsCard`、高级项 `AdvancedSettingsCard`、各类弹窗 `NotificationDialogs`、工具 `NotificationSettingUtils`、Android 专属 `NotificationSettingsScreen.android.kt`。 |
| `settings/backup/BackupScreen.kt` + `BackupViewModel.kt` | 备份/恢复（JSON、WebDAV）。 |
| `settings/conversion/CourseTableConversion*` | 课表转换（教务数据 → 课表）。 |
| `settings/quickactions/QuickActionsScreen.kt`、`tweaks/TweakSchedule*`、`delete/QuickDelete*` | 快捷操作：批量微调课表、按条件快速删课。 |
| `settings/update/UpdateRepoScreen.kt` + `ViewModel` | 适配仓库更新（Git / 离线）。 |
| `settings/additional/MoreOptions*`、`DeveloperAndIconComponents.kt`、`OpenSourceLicensesScreen.kt`、`LanguageSettingScreen.kt` | 更多页面：开发者选项（连点图标 5 次开启）、开源许可、语言设置。 |
| `settings/contribution/Contribution*` | 贡献者展示。 |
| `schoolselection/list/*` | 选校列表（按字母索引 `AlphabetIndexerList`）与适配器选择页。 |
| `schoolselection/web/*` | **教务导入 WebView**：`WebViewScreen` 承载，`WebBridgeProtocol` 定义 JS↔Native 消息外壳与 Payload，`WebBridgeHandler` 处理动作，`WebModelsAndConstants` 常量，`PlatformWebSpec` 平台差异；Android 侧 `WebView.android.kt`、`WebCompatDelegate`、`WebViewRequestInterceptor`、`AndroidWebConstants`。 |
| `components/*` | 通用组件：`AdvancedColorPicker`（取色）、`CourseTablePickerDialog`（课表选择）、`DatePickerModal`、`NumberPicker`、`ImageCropper`（背景裁剪，各平台 actual）、`ShareManager`、`ToastManager`、`NavigationComponents`（导航栏/导航套件）。 |
| `theme/Color.kt`、`Theme.kt`、`Type.kt`（+ 各平台 `Theme.*.kt`） | Material3 主题、配色（MaterialKolor 动态色）、字体排版；平台 actual 处理状态栏/动态色等。 |

### 3.5 资源与协议

| 路径 | 作用 |
| --- | --- |
| `src/commonMain/proto/schedule_style.proto` | 课表样式的 Protobuf 定义（尺寸、颜色对、边框类型、节次/24H 模式）。 |
| `src/commonMain/proto/school_index.proto` | 学校适配索引（`school_index.pb` 的结构）。 |
| `src/commonMain/proto/widget_snapshot.proto` | 小部件快照：课程列表 + 当前周 + 样式。 |
| `src/commonMain/composeResources/drawable/*` | 全平台矢量图标（Material Symbols XML）。 |
| `src/commonMain/composeResources/values*/*` | 多语言字符串（默认/简体/繁体/英文）与 `languages.xml`。 |
| `src/commonMain/composeResources/files/git_repos.json` | 内置在线适配仓库地址清单。 |
| `assets/offline_repo/` | 内置离线适配资源（`index/school_index.pb`、`schools/resources/GLOBAL_TOOLS/school.js`），构建时由 `packSchoolsZip` 打包成 `offline_schools.zip`。 |
| `schemas/` | Room 数据库 schema（v3/v4/v5），用于迁移校验。 |

### 3.6 关键构建配置（`shared/build.gradle.kts`）

- `packSchoolsZip` 任务：`assets/offline_repo` → `composeResources/files/offline_schools.zip`，并挂到资源生成任务之前。
- `aboutLibraries`：导出第三方许可到 `composeResources/files/aboutlibraries.json`。
- `wire`：编译 `src/commonMain/proto` 为 Kotlin；`room3`：导出 schema 到 `schemas/`。

---

## 四、`androidApp` 模块

| 文件 | 作用 |
| --- | --- |
| `MyApplication.kt` | 启动 Koin（`@KoinApplication` + `@ComponentScan`）、配置 WorkManager Factory。 |
| `MainActivity.kt` | 唯一 Activity，`setContent { App() }`，Edge-to-Edge。 |
| `data/sync/SyncManager.kt` | 桥接共享层与 Android：收到 `syncCompletedFlow` → 触发通知 Worker、勿扰调度、刷新全部小部件；监听样式更新 → 刷新小部件。 |
| `service/CourseNotificationWorker.kt` | 计算并发布上课提醒通知。 |
| `service/CourseAlarmReceiver.kt` | 广播接收器（精确闹钟 / 开机恢复），拉起提醒逻辑。 |
| `service/DndSchedulerWorker.kt` | 上课自动勿扰/静音的调度。 |
| `widget/WorkManagerHelper.kt` | 小部件刷新任务的统一调度入口。 |
| `widget/WidgetSyncWorkers.kt` | `WidgetUiUpdateWorker`（轻量刷新 UI）、`FullDataSyncWorker`（全量同步数据）。 |
| `widget/WidgetUpdateHelper.kt` | `updateAllWidgets()`：遍历刷新所有小部件。 |
| `widget/tiny/`、`compact/`、`double_days/`、`list_vertical/` | 四种桌面小部件，每种含 `Provider`（`AppWidgetProvider` 生命周期）+ `Renderer`（RemoteViews 渲染）。 |
| `res/layout*/`、`res/drawable*/` | 小部件 RemoteViews 布局与形状/背景（含 `-night` 深色版）。 |
| `res/xml/shiguangschedule_*_widget.xml` | 小部件 provider 配置（尺寸、预览图、更新周期）。 |
| `res/values*/strings.xml` | 小部件名称等 Android 原生字符串（多语言）。 |
| `res/xml/network_security_config.xml`、`file_paths.xml`、`backup_rules.xml`、`data_extraction_rules.xml` | 网络安全策略（WebView 抓教务）、FileProvider 路径、备份规则。 |
| `AndroidManifest.xml` | 权限（通知、精确闹钟、勿扰、日历、开机启动）与 4 个小部件 receiver、闹钟 receiver 注册。 |
| `proguard-rules.pro` | 混淆规则。 |

---

## 五、`desktopApp` / `iosApp`

| 文件 | 作用 |
| --- | --- |
| `desktopApp/src/main/kotlin/.../main.kt` | JVM 桌面入口：`application { Window { App() } }`，复用同一份 `shared` UI。 |
| `iosApp/iosApp/iOSApp.swift`、`ContentView.swift` | SwiftUI 壳，嵌入 `MainViewController`。 |
| `iosApp/iosApp.xcodeproj/` | Xcode 工程（打包 Shared.framework）。 |
| `iosApp/Configuration/Config.xcconfig` | 构建配置（版本号等）。 |
| `shared/src/iosMain/.../MainViewController.kt` | 把 `App()` 暴露为 UIViewController。 |

---

## 六、四条核心数据流（理解项目的关键）

1. **课表展示**：Room（`Course + CourseWeek + TimeSlot`）→ `CourseTableRepository` → `WeeklyScheduleViewModel` → `ScheduleGrid` 渲染；样式来自 `StyleSettingsRepository`（Proto DataStore）。
2. **教务导入**：选校列表 → WebView 打开教务页 → 注入 `school.js` 适配脚本 → JS 通过 `WebBridge*` 回传课程 JSON → `CourseConversionRepository` 落库。
3. **桌面小部件**：`WidgetDataSynchronizer` 监听共享层数据变化 → 写入 `WidgetDatabase`（未来 7 天展平数据）→ 发 `syncCompletedFlow` → Android `SyncManager` → `updateAllWidgets()` → 各 `Renderer` 用 RemoteViews 渲染。
4. **适配仓库更新**：`ResourceInitializerManager` 启动时解压内置离线仓库 → `GitUpdater`（kgit）在线增量更新 `filesDir/repo` → `SchoolRepository` 读取索引供选校页使用。

---

## 七、建议阅读顺序

1. `settings.gradle.kts` + `gradle/libs.versions.toml`（弄清模块与技术栈）
2. `shared/.../App.kt` + `Navigation.kt`（全局页面结构）
3. `data/db/main/*` → `data/repository/CourseTableRepository.kt`（数据模型）
4. `ui/schedule/WeeklyScheduleScreen.kt` + `components/ScheduleGrid*.kt`（主界面）
5. `ui/schoolselection/web/*` + `data/repository/CourseConversionRepository.kt`（教务导入，本项目最有特色的部分）
6. `data/sync/WidgetDataSynchronizer.kt` + `androidApp/widget/*`（跨进程/跨模块同步）
7. `tool/GitUpdater.kt` + `ResourceInitializerManager.kt`（适配仓库生态）

---

## 八、构建命令

本机无全局 `JAVA_HOME` 时，Gradle Wrapper 用 JBR 17 启动，源码由 `jvmToolchain(21)` 编译。直接跑 `gradlew` 需自行带代理（`build.bat` 已内置 `127.0.0.1:8848`）。

```powershell
# 桌面端（最快验证编译；需要全平台目标）
.\gradlew :desktopApp:run
# Android 调试 / 发布包（默认全平台配置，与 build.bat / CI 一致）
.\gradlew :androidApp:assembleDebug
.\gradlew :androidApp:assembleRelease
# 仅编译共享模块（Android 目标）
.\gradlew :shared:compileAndroidMainKotlin
```

### 仅 Android 快速路径（`-PandroidOnly`）

日常只出 Android APK 时，显式传入该属性：跳过 `:desktopApp`，且 `shared` 不声明 iOS / JVM 目标及对应 `kspJvm` / `kspIos*`。默认关闭，不传属性时行为与改造前相同。`build.bat` 与 CI **不要**加这个开关。

```powershell
.\gradlew :androidApp:assembleDebug -PandroidOnly
.\gradlew :androidApp:assembleRelease -PandroidOnly
```

属性语义是 **存在即开启**（`providers.gradleProperty("androidOnly").isPresent`）。`-PandroidOnly=false` 仍会裁剪；不要把 `androidOnly=` 写进 `gradle.properties`。切换有/无该属性会 miss configuration cache，这是预期行为。

`build.bat` 仍使用 `--no-daemon` 且不传 `-PandroidOnly`。每次调用都会冷启动 Gradle JVM，这是脚本层的提速天花板；配置缓存命中与 `-PandroidOnly` 只对直接跑 `gradlew` 的日常构建生效。

### 实测（2026-09-12，本机热 daemon）

产物均为 `androidApp/build/outputs/apk/release/shiguangschedule-v2.0.2-arm64-v8a-release.apk`（约 3.8 MB，R8 minify + shrinkResources，仅 arm64-v8a）。

| 场景 | 配置项目 | 依赖解析 | 总耗时 |
| --- | --- | --- | --- |
| 改造前 `30fdfde` 全平台 dry-run（无 CC） | 0.53s（含 desktop） | 0.97s | 5.5s |
| 改造后全平台 dry-run（CC miss） | 0.30s（含 desktop） | 0.55s | 3.1s |
| 改造后 `-PandroidOnly` dry-run（CC miss） | 0.39s（无 desktop） | 0.38s | 2.8s |
| 改造后全平台增量 `assembleRelease`（CC hit） | 0s | — | 1.3s |
| 改造后 `-PandroidOnly` 增量 `assembleRelease`（CC hit） | 0s | — | 1.7s |

冷配置（无 configuration cache、需解析 iOS/JVM）：全平台约 **1m43s**，其中依赖解析 **1m21s** 几乎全是 `iosArm64` / `iosSimulatorArm64` / JVM classpath。`-PandroidOnly` 不再解析这些目标，首次配置约 **3s**。增量构建两边都在 1–2s。

CMP 1.12.0 配套的 material3 / adaptive / nav3 更新版仍是 alpha/beta，保持现有稳定号；Wire 7.0 是主版本升级，仍钉在 6.4.7。
