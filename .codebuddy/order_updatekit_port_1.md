# 工单 order_updatekit_port_1:UpdateKit 移植替换检查更新模块

仓库:E:/code/Android/shiguangschedule(Kotlin Multiplatform,Android 主 target)
参考项目:E:/code/Android/SmsForwarder-new(core/updatekit 模块)

## 背景(必读)

时光课程表当前"检查更新"功能有必现 Bug:下载 APK 完成后首次点「安装」必闪退,重开 App 再下载一次才能装。现实现(shared/src/.../tool/update/ 下 Ktor 下载链路)无完整性校验且状态机缠绕。决策:不做修补,把 SmsForwarder-new 中已验证可用的 UpdateKit 整体移植过来替换。

计划文件(权威,已获用户批准):D:/Users/NULL/.claude/plans/mighty-drifting-squid.md —— 先读它。

## 第 0 步:幂等检查

先 `git log --oneline -3` 与 `git status`,若已存在 "refactor: replace update module with UpdateKit port" 类提交或 tool/update/ 下已是 UpdateKit 文件,跳过已完成项并只做剩余部分。

## 改动清单

包名统一为 `com.xingheyuzhuan.shiguangschedule.tool.update`。

### A. 删除旧文件(shared 模块)
- shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/AppUpdateController.kt(旧实现)
- shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/UpdateChecker.kt
- shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/PlatformApk.kt
- shared/src/androidMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/PlatformApk.android.kt
- shared/src/iosMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/PlatformApk.ios.kt
- shared/src/jvmMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/PlatformApk.jvm.kt
- shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/settings/additional/AppUpdateOverlay.kt(旧实现,将被 B-3 重写)

### B. 从 E:/code/Android/SmsForwarder-new/core/updatekit/src/main/kotlin/com/smessage/updatekit/ 移植(只改包名与必要适配,逻辑逐行保留,注释保留)

1. UpdateManifest.kt → shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/UpdateManifest.kt
2. UpdateVersionPolicy.kt(含 UpdateDecision)→ commonMain 同目录
3. UpdateKitState.kt → commonMain 同目录
4. UpdateKit.kt → shared/src/androidMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/UpdateKit.kt
5. UpdateKitConfig.kt → androidMain 同目录
6. UpdateKitHttp.kt → androidMain 同目录
7. ApkDownloader.kt → androidMain 同目录
8. ApkInstaller.kt → androidMain 同目录
9. UpdateManifestFetcher.kt(含 kitJson)→ androidMain 同目录

适配点(仅这几处,其余不动):
- UpdateKit.destFile() 保持用 getExternalFilesDir(null)/apk/shiguangschedule.apk(apkFileName 配置为 "shiguangschedule.apk")
- 所有 import 的 com.smessage.updatekit → com.xingheyuzhuan.shiguangschedule.tool.update

### C. 新门面(替换旧 AppUpdateController)

- shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/update/AppUpdateController.kt 重写为 interface:
  ```kotlin
  interface AppUpdateController {
      val state: StateFlow<UpdateKitState>
      fun checkOnStartup()
      fun checkManual()
      fun download(manifest: UpdateManifest)
      fun downloadLatest(): Boolean
      fun dismiss()
      fun resetDownload()
  }
  ```
- shared/src/androidMain/kotlin/.../tool/update/AndroidUpdateController.kt 新建:@Single 类,构造注入 Context,内部持有一个 UpdateKit 实例(config:manifestUrl = "https://refree.rth1.xyz/shiguangschedule.json",userAgent 用 UpdateKitConfig 默认 UA,apkFileName = "shiguangschedule.apk",其余默认),全部方法转发 kit。checkOnStartup/checkManual/downloadLatest 语义与 UpdateKit 一致。
- shared/src/iosMain/kotlin/.../tool/update/StubUpdateController.ios.kt 与 shared/src/jvmMain/kotlin/.../tool/update/StubUpdateController.jvm.kt 新建:@Single 实现 interface;state = MutableStateFlow(UpdateKitState.Idle);checkOnStartup 空操作;checkManual 置 Error;download/downloadLatest/dismiss/resetDownload 空操作。
- Koin 绑定:在 shared/src/androidMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/di/PlatformServices.android.kt、PlatformServices.ios.kt、PlatformServices.jvm.kt 各加一个 @Single 函数 `fun bindAppUpdateController(impl: XxxController): AppUpdateController = impl`(Koin 注解风格与该文件现有 provideAppVersionCode 一致)。注意 AppUpdateController 是 interface,bind 函数返回类型写接口。

### D. 重写 AppUpdateOverlay

- shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/settings/additional/AppUpdateOverlay.kt 重写:
  - 参数:`state: UpdateKitState, onStartDownload: (UpdateManifest) -> Unit, onRetryDownload: () -> Unit, onDismiss: () -> Unit, onReset: () -> Unit`
  - 渲染逻辑照抄 E:/code/Android/SmsForwarder-new/feature/ui/settings/src/main/java/com/smessage/forwarder/ui/settings/SettingsScreen.kt 第 498-660 行的结构,但用本项目既有文案 stringResource(旧文件里已有的 key 全部复用:dialog_new_version_found/btn_update_now/action_later/action_cancel/dialog_downloading_update/update_downloaded_bytes(_of)/update_retrying/dialog_download_failed(_msg)/action_retry/action_close/dialog_apk_ready(_msg)/action_install/action_install_now/dialog_need_unknown_sources(_msg)/action_grant_unknown_sources/toast_install_failed/dialog_current_version_latest/dialog_update_check_failed/label_error_message/update_server_unreachable/update_fallback_notes/tip_please_wait/formatBytes 私有函数一并保留)。不再有 fromManual/silent 分支:Available 的 dismissButton 一律 action_later。
  - UpToDate/Error 态:不弹 AlertDialog,用 LaunchedEffect + 项目既有 ToastManager.show()(shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/components/ToastManager.kt)提示后回调 onDismiss 复位。文案:UpToDate 用 dialog_current_version_latest 的文案文本,Error 用 update_server_unreachable。
  - ReadyToInstall:点「安装」→ PlatformApk.canRequestInstall 等价逻辑改由 androidMain expect/actual 提供;未授权时 grantInstallPrompt 子弹窗 → 「去授权」跳系统页,返回后已授权则直接 launchInstall,成功后 onReset()。参考旧 AppUpdateOverlay.kt(git show HEAD:路径可取)的 rememberRequestUnknownSources 机制,保留 expect/actual:`shared/src/commonMain/.../tool/update/RememberRequestUnknownSources.kt` 写 `@Composable expect fun rememberRequestUnknownSources(onReturned: () -> Unit): () -> Unit`;androidMain actual 内部用 rememberLauncherForActivityResult(StartActivityForResult) + Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:packageName");ios/jvm actual 返回空 lambda。
  - canRequestInstall/launchInstall:在 androidMain 新建小 object `UpdateInstaller`(或直接放在 AndroidUpdateController.kt 内),内容 = SmsForwarder ApkInstaller 的 canRequestInstall + launchInstall 逐行移植(反射双签名那段必须保留,compileSdk 37 兼容坑)。commonMain 不暴露这两个能力,Overlay 通过参数回调穿透:给 Overlay 增加 `onInstall: (filePath: String) -> Boolean` 与 `canInstall: () -> Boolean` 两个参数?——不行,Overlay 在 commonMain 无法拿 Context。正确做法:AndroidUpdateController 增加 `fun canRequestInstall(): Boolean` 与 `fun launchInstall(filePath: String): Boolean` 两个方法(Stub 返回 false),interface 与 Overlay 通过 controller 直接调用。App.kt 已有 koinInject<AppUpdateController>(),Overlay 增加参数 `controller: AppUpdateController` 或直接在 App.kt 传方法引用,选后者(参数名 onInstallRequest/canRequestInstall)。
  - DownloadFailed:confirmButton「重试」→ onRetryDownload(),dismissButton「关闭」→ onReset()。
  - Available:confirmButton「立即更新」→ onStartDownload(manifest) 并立即 onDismiss()(先关弹窗再进下载,同 SmsForwarder);dismissButton「以后再说」→ onDismiss()。
  - Downloading:onDismissRequest 禁止关闭,不可 dismiss。

### E. 挂载点 App.kt

- shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/App.kt 第 75-83 行区域:类型从 AppUpdateState 改 UpdateKitState;onStartDownload = { updateController.download(it) };LaunchedEffect(Unit) { updateController.checkOnStartup() } 保留;把 controller 的 canRequestInstall/launchInstall 以方法引用传给 Overlay。

### F. Gradle

- gradle/libs.versions.toml:[versions] 加 okhttp = "4.12.0";[libraries] 加 okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
- shared/build.gradle.kts:androidMain sourceSet 的 dependencies 加 implementation(libs.okhttp)。先读该文件找到 androidMain deps 的现有写法(ktor-client-* 应该在 androidMain 或 commonMain),保持同区块。ios/jvm target 不加 okhttp。

### G. 清理

- 全仓库 Grep "AppUpdateState|PlatformApk|UpdateChecker",确保无残留引用未删干净(尤其 MoreOptionsScreen.kt 第 185-190 行 checkManual 调用——它调用的是 controller.checkManual(),接口不变则无需动;但 import 若引用已删类型必须修)。
- ios/jvm 的 PlatformServices.*.kt 若原引用了 PlatformApk,一并清理。

## 禁止事项

- 禁止改动 androidApp/src/main/AndroidManifest.xml、file_paths.xml(已对齐)。
- 禁止改动 shiguangschedule.json、版本号。
- 禁止 git commit / push,只改工作区。
- 禁止引入 armeabi-v7a/x86 依赖或新第三方库(okhttp 除外)。
- SmsForwarder 源文件的 KDoc 注释保留,包名/路径相关注释可同步改。

## 完成标准

1. 全部文件按清单落位,`git status` 只含预期改动(删除+新增+修改 E/F/G 涉及文件)。
2. 产出对账文件 E:/code/Android/shiguangschedule/.codebuddy/order_updatekit_port_1_report.md:逐项 A~G 标 done/partial/todo + 简述。
3. 运行 `E:/code/Android/shiguangschedule/build.bat` 构建成功(该脚本内部用 AS 自带 JDK,勿手动设 JAVA_HOME);若失败,修复后重跑至成功,把最终 BUILD SUCCESSFUL 证据(末尾若干行)写进对账文件。APK 产物路径也写入。
4. StructuredOutput 返回 ok + summary(含 APK 路径与对账文件路径)。
