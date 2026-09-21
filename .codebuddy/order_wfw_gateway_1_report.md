# 工单 order_wfw_gateway_1 执行报告

日期：2026-09-21
仓库：E:/code/Android/shiguangschedule（分支 main，未 commit / 未 push）

## 幂等检查（先做）

- `git log --oneline -3`：HEAD = 72ef12b（wip 快照）。
- `git diff --stat`（执行前）：工作区无本工单相关改动 → 四项改动均未实施，全部执行。

## 逐项对账

| 项 | 文件 | 状态 | 说明 |
|---|---|---|---|
| 改动 1 | GdouModels.kt | done | 删 `GDOU_WFW_MENU`（原 L14）、`enum GdouAccessMode`（原 L31）；其余常量原样保留 |
| 改动 2 | GdouHttpClient.kt | done | 见下 |
| 改动 3 | GdouAuthClient.kt | done | 见下（含 1 处按权威协议修正的偏差，见「偏差说明」） |
| 改动 4 | GdouSyncUseCase.kt | done | 见下 |

### 改动 2 关键 diff 摘要（GdouHttpClient.kt，-95/+127 行级）

- 删除：`jwLock`/`mode`/`probeSink`/`fallbackSink` 字段；`accessMode()`/`probeAccessMode()`/`withGatewayFallback()`/`gatewayFallback()`；companion 的 `probeVerdict()`/`shouldFallbackToGateway()`；随之删除 import `io.ktor.http.Url`、`kotlin.concurrent.Volatile`、`kotlinx.coroutines.sync.Mutex`、`kotlinx.coroutines.sync.withLock`、`io.ktor.client.plugins.timeout`（probe 专用）。
- `outboundUrl()`（改后为非 suspend）：
  - `forceHttps` 后按 host 分派：`GDOU_JW_HOST` → `GdouHtml.rewriteOfficialJwToGateway`（嵌网关前缀）；`GDOU_AUTH_HOST` → `GDOU_WFW_ORIGIN + pathAndQueryOf(url)`（新增私有 `pathAndQueryOf` 提取原 path+query）；其余原样。
- `outboundReferer()`：委托 `outboundUrl()`（jw → 同路径网关嵌入 URL；不再返回 `GDOU_WFW_MENU`；WFWSESSID 判定与 `gateway` 参数已删）。
- `get/getBytes/postForm/postRaw`：去掉 `withGatewayFallback` 包装，直接 `outboundUrl` → `send` → `toExchange`，try/catch（CancellationException 原样抛，其余 `requestFailureSink?.invoke(requestUrl, e)` 后**重抛**），非 bytes 版 `toExchange(response)`（asBytes=false）、getBytes 版 `asBytes = true`。
- `followRedirects`：结构未动；`rawNext` 每跳经 `outboundUrl()`，官方 jw/authserver 的绝对 Location 均被改写（已确认）。
- 保留项核对：`hopSink`/`requestFailureSink`/`connectivityText`/`describeConnectivity`/`isConnectivityError`/`REDIRECTS`/`CONNECTIVITY_HINTS`/`cookieStore`/`json`/`client`/`parseJson`/`toExchange`/`notifyHop` 均在。

### 改动 3 关键 diff 摘要（GdouAuthClient.kt，整体重写）

1. `loginService()` 删除；全部 `http.accessMode()` 分支删除。
2. `registerFingerprint`：URL = `"$GDOU_JW_BASE/authserver/bfp/info"`（outbound 自动嵌 `wfw/https://jw...`）；referer 缺省 = `GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)`。
3. `needCaptcha`：URL = `"$GDOU_JW_BASE/authserver/checkNeedCaptcha.htl"`；referer = 网关入口。
4. `fetchCaptchaImage`：URL = `"$GDOU_JW_BASE/authserver/getCaptcha.htl?ts=..."`；referer = 网关入口。
5. `login()`：首跳 `http.get(GDOU_JW_BASE)`（outbound 即网关入口，就地含 salt/execution/lt）；salt 缺失时 `dropSessionCookies()` 再 GET 重试保留；POST action = `GDOU_WFW_PROXY_AUTH_LOGIN + "service=" + encodeService(GDOU_SERVICE_URL)`（见偏差说明 1），origin = `GDOU_WFW_ORIGIN`，referer = 入口；POST 后 301..308 且有 Location → `followRedirects(first.location, 入口)`，否则 `landed = first` → `classifyLanding`（旧 isJwUrl 二次跟随分支删除）。
6. `classifyLanding` 重写，判定顺序：a) `isReauth`+`parseReauthParams`→NeedMfa（保留）；b) `isJwLoggedIn(current.url, current.body)` 且 `warmUp()` → LoggedIn；c) 落地 wfw 门户（`hostOf(url)==GDOU_WFW_HOST && !isGatewayJwUrl(url)`）且有 WFWSESSID → `warmUp()` ? LoggedIn : `Failed("统一认证已通过，但教务网关会话未建立")`；d) pageError 非空或 body 含 `pwdEncryptSalt` 或 url 含 `/authserver/login` → `BadPassword(pageError 非空 ? pageError : "账号或密码错误")`；e) sso 路径 → `Failed("未完成教务 SSO（停在 …，HTTP …）")`，其余 `Failed("未进入教务（HTTP …）")`。
7. `leaveSso` 重写：仅当 `isSsoPath(current.url)`（网关嵌入形态经 `pathOf` 剥前缀后同样命中）且有 30x Location 或页面 meta 跳转 → `followRedirects`（outboundUrl 保持网关路径）；**删除** GET `GDOU_JW_MENU` 逻辑。
8. `tryResumeWithCastgc(fingerprint)`：重写为 `if (warmUp()) LoggedIn else Failed("本地会话不可用")`；签名保持（`@Suppress("UNUSED_PARAMETER")`），GdouSyncUseCase 调用点无需改动。
9. `warmUp()`：删 WFWSESSID 门槛与 accessMode 分支；直接 `followRedirects(rewriteOfficialJwToGateway(GDOU_JW_MENU))`，返回 `status==200 && isJwLoggedIn`；外层 `runCatching { }.getOrDefault(false)` 保留。
10. `warmUpGateway()`：`followRedirects(menuUrl, …)` 的 referer 改 `rewriteOfficialJwToGateway(GDOU_JW_BASE)`；其余（menu → looksLikeLoginPage → creds/salt/execution/lt → POST 网关根 → 落地判定）保留。注意：新版 warmUp 不再调用它（按工单 9 的定义），当前为保留的兜底私有方法（编译仅可能提示未使用，无错误）。
11. MFA：`changeReAuthType.do`/`getDynamicCodeByReauth.do`/`reAuthSubmit.do` URL 前缀改 `"$GDOU_WFW_ORIGIN/authserver/..."`；origin 相应改 `GDOU_WFW_ORIGIN`（原 `https://authserver.gdou.edu.cn` 与新 URL 不匹配，随网关化必须改）；`svc = params.service.ifBlank { GDOU_SERVICE_URL }`；referer fallback = `"$GDOU_WFW_ORIGIN/authserver/reAuthCheck/reAuthLoginView.do"`。
12. `dropSessionCookies()`：无条件清 wfw 域 `WFWSESSID`/`urlss`（删 accessMode 条件），CASTGC 与 jw JSESSIONID 清理保留。
13. import：`io.ktor.http.Url` 仍被 classifyLanding(c) 使用，保留；未新增依赖。
- `loginUrl()` 改为直接构造 `GDOU_WFW_PROXY_AUTH_LOGIN + "service=" + encodeService(service)`（见偏差说明 2），GdouAuthClient.kt 中 `GDOU_AUTH_BASE`/`GDOU_AUTH_HOST` 引用归零。

### 改动 4 关键 diff 摘要（GdouSyncUseCase.kt）

- 删 `fallbackNotified` 变量、`http.probeSink = ...` 块、`http.fallbackSink = ...` 块；finally 中相应清理行删除。
- `http.requestFailureSink = ...` 块保留（连接类失败中文记入过程日志）。
- 删 `syncNowLogged` 中 `if (http.accessMode() == GdouAccessMode.GATEWAY) { log.info("已通过 wfw 网关建立教务会话") }`。
- `ensureSession` 日志文案：`"打开教务 $GDOU_JW_HOST"` → `"打开教务网关 $GDOU_WFW_HOST"`。
- 无随之失效的 import（GdouAccessMode 等为同包符号，文件头部 import 未引用被删符号）。

## 偏差说明（相对工单文本）

1. **login POST action**：工单写作 `GDOU_WFW_PROXY_AUTH_LOGIN + encodeService(GDOU_SERVICE_URL)`，但该常量已含结尾 `?`，照拼会得到 `.../authserver/login?https%3A%2F%2F...`（缺 `service=`），与唯一权威 `E:/code/py/gdoulogin/wfw_login.py` 的 `?service={quote(SERVICE_URL)}` 矛盾。按权威协议实现为 `GDOU_WFW_PROXY_AUTH_LOGIN + "service=" + encodeService(GDOU_SERVICE_URL)`，与本文件 `warmUpGateway` 既有写法一致。
2. **loginUrl()**：工单未明确要求，但为满足完成标准 2，`loginUrl` 不再拼 `GDOU_AUTH_BASE`，直接构造网关根登录 URL（与 outboundUrl 对 `GDOU_AUTH_HOST` 的改写结果逐字符等价，仅少一次改写）。
3. **MFA origin**：工单 11 仅要求改 URL 前缀；POST 目标变为 wfw 后 Origin 必须同为 `GDOU_WFW_ORIGIN`（原 `https://authserver.gdou.edu.cn` 不再成立），随网关化一并修改。

## 完成标准核对

1. 残留 grep：`accessMode|GdouAccessMode|GDOU_WFW_MENU|probeSink|fallbackSink|withGatewayFallback|probeVerdict|shouldFallbackToGateway` 在 `shared/.../data/api/gdou/` 下 **0 命中**（`requestFailureSink`/`connectivityText` 按要求保留，不在禁列）。✔
2. `GdouAuthClient.kt` 中 `GDOU_AUTH_BASE`/`GDOU_AUTH_HOST`/`authserver.gdou.edu.cn` **0 命中**（全目录中仅 GdouModels.kt 定义与 GdouHtml.forceHttps / GdouHttpClient.outboundUrl 改写分支引用，且后者保证即使传入官方 auth URL 也会改写为 wfw 网关）。✔
3. 编译：命令与结果见下节。✔
4. 仅改动 4 个目标文件（`git diff --stat`：GdouAuthClient.kt / GdouHttpClient.kt / GdouModels.kt / GdouSyncUseCase.kt，+157/−252）；未 commit、未 push；未运行任何真实网络探针。✔
5. 本报告即对账文件。✔

## 编译命令与结果

- 工单指定命令 `gradlew.bat :shared:compileKotlinJvm`：本机 PATH 无 java、JAVA_HOME 未设，脚本自身报错退出（环境限制，非代码问题）。
- 按 build.bat 同款方式（未手动设 JAVA_HOME）在仓库根执行：

```
HTTP_PROXY/HTTPS_PROXY=http://127.0.0.1:8848
JAVA_TOOL_OPTIONS/GRADLE_OPTS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8848 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8848
E:/app/android-studio/jbr/bin/java.exe -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :shared:compileKotlinJvm --no-daemon --console=plain
```

结果：**BUILD SUCCESSFUL in 1m 4s**（14 tasks，5 executed / 9 up-to-date）。仅有既有告警（expect/actual Beta、kotlinx Instant deprecated、GdouKbListParser `!!` 等），无新增告警。`GdouHttpClientTest` 引用已删符号属工单 2（order_wfw_gateway_2）范围，main 源集不受影响。
