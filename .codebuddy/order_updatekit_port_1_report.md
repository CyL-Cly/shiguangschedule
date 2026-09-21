# 工单 order_updatekit_port_1 对账报告

执行日期:2026-09-21
计划文件:D:/Users/NULL/.claude/plans/mighty-drifting-squid.md
结论:**全部完成(A~G done),构建成功**

## 第 0 步:幂等检查 — done

`git log --oneline -3` 顶部为 `d6f1be7 chore: bump version to 2.1.1 (47)`,无 UpdateKit 相关提交;`tool/update/` 下为旧实现(AppUpdateController/UpdateChecker/PlatformApk×4),无已完成项,全量执行。
另:commonMain 已存在旧版 UpdateManifest.kt / UpdateVersionPolicy.kt(缺 sha256 字段与 KDoc,为先前部分移植产物),按工单 B 用 SmsForwarder 版本覆盖替换。

## A. 删除旧文件 — done

- 删除:`UpdateChecker.kt`、`PlatformApk.kt`(commonMain)与 `PlatformApk.android.kt` / `.ios.kt` / `.jvm.kt`(git rm)。
- `AppUpdateController.kt` 与 `AppUpdateOverlay.kt` 按 C/D 条重写覆盖(旧 sealed class AppUpdateState 一并消失)。

## B. 移植 UpdateKit — done

包名统一 `com.xingheyuzhuan.shiguangschedule.tool.update`,逻辑逐行保留,KDoc 注释保留。

commonMain:
- `UpdateManifest.kt`(含 sha256 字段、SHA256_REGEX、isValid/isNewerThan)
- `UpdateVersionPolicy.kt`(含 UpdateDecision)
- `UpdateKitState.kt`
- `RememberRequestUnknownSources.kt`(expect,新)

androidMain:
- `UpdateKit.kt`、`UpdateKitConfig.kt`、`UpdateKitHttp.kt`、`ApkDownloader.kt`(sha256/fileSize 校验 + downloadWithRetry)、`ApkInstaller.kt`(canRequestInstall 反射双签名保留)、`UpdateManifestFetcher.kt`(含 kitJson)
- `RememberRequestUnknownSources.android.kt`(actual)

必要适配(仅两处,其余逐行一致):
1. `UpdateKitState.ReadyToInstall(file: File)` → `ReadyToInstall(filePath: String)`:java.io.File 在 iOS 目标不可用,而该 sealed interface 必须留在 commonMain 供门面 interface 使用;`UpdateKit.startDownloadJob` 内 `ReadyToInstall(it.absolutePath)` 转换,KDoc 中 [ApkInstaller.launchInstall] 链接改为纯文字(类型已不在 commonMain 可见域)。
2. import 包名替换。

## C. 新门面 — done

- commonMain `AppUpdateController.kt` 重写为 interface:state/checkOnStartup/checkManual/download/downloadLatest/dismiss/resetDownload,另按工单 D 条决定追加 `canRequestInstall(): Boolean` 与 `launchInstall(filePath: String): Boolean`(Overlay 经参数方法引用穿透调用,commonMain 不依赖 Context)。
- androidMain `AndroidUpdateController.kt`:@Single,构造注入 Context,内部 `UpdateKitConfig(manifestUrl = "https://refree.rth1.xyz/shiguangschedule.json", apkFileName = "shiguangschedule.apk")`(UA/超时/重试均用默认),持有唯一 `UpdateKit` 实例,全方法转发;canRequestInstall/launchInstall 委托 `ApkInstaller`(authority 走 config.fileProviderAuthority,默认 null → `${packageName}.fileprovider`)。
- iosMain / jvmMain `StubUpdateController.{ios,jvm}.kt`:@Single,state 恒 Idle,checkManual 置 Error,其余空操作,canRequestInstall/launchInstall 恒 false。
- Koin 绑定:三个 `PlatformServices.{android,ios,jvm}.kt` 各加 `@Single fun bindAppUpdateController(impl: XxxController): AppUpdateController = impl`(风格同 provideAppVersionCode);SharedModule 的 `@ComponentScan("com.xingheyuzhuan.shiguangschedule")` 自动收录。

## D. 重写 AppUpdateOverlay — done

参数:`state, onStartDownload: (UpdateManifest) -> Unit, onRetryDownload, onDismiss, onReset, canRequestInstall: () -> Boolean, onInstallRequest: (String) -> Boolean`(按工单 D 最终决定:方法引用穿透,不传 controller)。

渲染结构照抄 SmsForwarder SettingsScreen.kt 498-660,文案全用本项目既有 key:
- Available:「立即更新」= 先 onDismiss() 再 onStartDownload(manifest)(同 SmsForwarder 先关弹窗再下载);dismissButton 一律 action_later(不再有 fromManual/silent 分支)。
- Downloading:onDismissRequest 空,不可关闭;进度条 + update_downloaded_bytes(_of) + attempt>1 时 update_retrying。
- DownloadFailed:「重试」→ onRetryDownload();「关闭」→ onReset();onDismissRequest 亦 onReset。
- ReadyToInstall:点「安装」→ canRequestInstall() ? onInstallRequest(filePath)(成功 onReset() / 失败 toast_install_failed + onReset()):grantInstallPrompt 引导弹窗 →「去授权」跳系统页,返回后重新判定,已授权直接 launchInstall;dismissButton action_later → onReset。
- UpToDate / Error:不弹 AlertDialog,LaunchedEffect + ToastManager.show()(UpToDate 用 dialog_current_version_latest 文案,Error 用 update_server_unreachable)后 onDismiss() 复位。
- Checking / Idle:不渲染(Checking 无 silent 概念)。
- formatBytes 私有函数保留;rememberRequestUnknownSources expect/actual(见 B/C)。

## E. App.kt 挂载 — done

App.kt:75-83 区域:`updateState` 类型随 interface 变为 UpdateKitState;`LaunchedEffect(Unit) { checkOnStartup() }` 保留;Overlay 传 `onStartDownload = updateController::download`、`onRetryDownload = { updateController.downloadLatest() }`、`onDismiss/onReset = ::dismiss / ::resetDownload`、`canRequestInstall / onInstallRequest = ::canRequestInstall / ::launchInstall` 方法引用。MoreOptionsScreen.kt:189 `updateController.checkManual()` 接口不变,无需改动。

## F. Gradle — done

- `gradle/libs.versions.toml`:[versions] 加 `okhttp = "4.12.0"`;[libraries] 加 `okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }`(紧随 ktor 条目)。
- `shared/build.gradle.kts`:androidMain.dependencies 加 `implementation(libs.okhttp)`(与 ktor-client-cio 同区块);ios/jvm 未加。

## G. 清理 — done

- 全仓 Grep `AppUpdateState|PlatformApk|UpdateChecker|startDownload`:0 命中,无残留引用。
- ios/jvm PlatformServices.*.kt 原本未引用 PlatformApk,无需清理。
- 只读核对(未改动):AndroidManifest 已含 REQUEST_INSTALL_PACKAGES 与 FileProvider `${applicationId}.fileprovider`;file_paths.xml `external-files-path path="apk/"` 与 UpdateKitConfig 默认 `apkDownloadDir="apk"` 一致。

## 禁止事项核查

- 未改动 AndroidManifest.xml、file_paths.xml、shiguangschedule.json、版本号。
- 未 git commit / push,仅工作区改动。
- 未引入 armeabi-v7a/x86 依赖;新第三方库仅 okhttp 4.12.0。

## 构建证据

命令:`build.bat release`(AS 自带 JBR,内部代理)。

```
--- build.log tail ---
[Incubating] Problems report is available at: file:///E:/code/Android/shiguangschedule/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/9.7.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 3m 35s
94 actionable tasks: 93 executed, 1 up-to-date
Configuration cache entry stored.
```

APK 产物:`E:/code/Android/shiguangschedule/shiguangschedule-v2.1.1-arm64-v8a-release.apk`(4 070 795 字节,arm64-v8a release)。

## 遗留(超出本工单范围,属计划文件验证步骤)

- 真机安装验证:检查更新 → 下载 → 首次点「安装」应直接跳系统安装器不闪退(计划文件验证 2/3,需真机,执行方无法完成)。
