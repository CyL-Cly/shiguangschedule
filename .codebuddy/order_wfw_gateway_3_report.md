# 工单 order_wfw_gateway_3 独立验收报告

日期：2026-09-21
仓库：E:/code/Android/shiguangschedule（分支 main，HEAD 与远端 cyl/main 同步）
验收方式：**只读验收 + 真实测试**。未修改任何源文件，未 commit/push，未访问 gdou.edu.cn 任何真实域名，未删除/覆盖已有报告。
权威协议：E:/code/py/gdoulogin/wfw_login.py（逐条对照）。

## 总结论：整体 PASS（含 2 条非阻断观察项，见 B2/B4）

| 验收组 | 结论 |
|---|---|
| A. 协议一致性（8 项） | 全部 pass |
| B. 潜在缺陷排查（5 项） | 全部 pass，另记 2 条非阻断观察 |
| C. 真实测试（3 项） | 全部 pass |
| D. 工作区核查 | pass，无工单外改动 |

---

## A. 协议一致性（对照 wfw_login.py 逐条核对）

核对对象：`shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/` 下 GdouModels.kt / GdouHttpClient.kt / GdouAuthClient.kt / GdouSyncUseCase.kt。

### A1. 登录首跳 = GET GDOU_JW_BASE（outbound 后为网关入口），就地取 salt/execution/lt —— **pass**

- GdouAuthClient.kt:54-58：
  ```kotlin
  val entry = GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)
  var page = http.get(GDOU_JW_BASE)
  var salt = GdouHtml.attrValue(page.body, "pwdEncryptSalt")
  var execution = GdouHtml.attrValue(page.body, "execution")
  var lt = GdouHtml.attrValue(page.body, "lt")
  ```
- 改写链：GdouHttpClient.get（GdouHttpClient.kt:67）→ outboundUrl（:201-208）→ host==GDOU_JW_HOST → GdouHtml.rewriteOfficialJwToGateway（GdouHtml.kt:78-81）→ `https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn`。与 py `ENTRY_URL = f"{WFW}{JW_PATH}"`（wfw_login.py:40-41）逐字符等价。
- salt 缺失时 dropSessionCookies+重试一次（GdouAuthClient.kt:59-65），仍缺则 `Failed("登录页缺少 salt/execution")`（:66-68），对应 py wfw_login.py:86-90 的 fail-fast 语义。

### A2. POST = wfw 根 `/authserver/login?service=<urlencode>`，Origin=wfw 根，Referer=入口 —— **pass**

- GdouAuthClient.kt:85-90：
  ```kotlin
  val first = http.postForm(
      url = GDOU_WFW_PROXY_AUTH_LOGIN + "service=" + encodeService(GDOU_SERVICE_URL),
      fields = payload, referer = entry, origin = GDOU_WFW_ORIGIN)
  ```
- 常量 GdouModels.kt:17 `GDOU_WFW_PROXY_AUTH_LOGIN = "$GDOU_WFW_ORIGIN/authserver/login?"` → POST URL = `https://wfw.gdou.edu.cn/authserver/login?service=https%3A%2F%2Fjw.gdou.edu.cn%2Fsso%2Fjziotlogin`。
- encodeService（GdouAuthClient.kt:316-327）仅编码 `:`→%3A、`/`→%2F；对 SERVICE_URL（只含字母数字与 `:/`）结果与 py `quote(SERVICE_URL, safe='')`（wfw_login.py:43）一致。
- Origin=`GDOU_WFW_ORIGIN`（=py `Origin: WFW`，wfw_login.py:130）、Referer=entry 网关入口（=py `Referer: ENTRY_URL`，:131）。✔

### A3. bfp / checkNeedCaptcha / getCaptcha = 嵌套路径 GDOU_JW_BASE/authserver/... —— **pass**

- registerFingerprint GdouAuthClient.kt:32：`http.get("$GDOU_JW_BASE/authserver/bfp/info", effectiveReferer)`，referer 缺省 = 网关入口（:31）；→ outbound 后 `wfw/https://jw.gdou.edu.cn/authserver/bfp/info`，与 py `BFP_URL = f"{WFW}{JW_PATH}/authserver/bfp/info"`（wfw_login.py:44）一致。
- needCaptcha GdouAuthClient.kt:192：`"$GDOU_JW_BASE/authserver/checkNeedCaptcha.htl"` + `parameter("username", username)`，referer=网关入口；与 py CHECK_CAPTCHA_URL（:45）+ `params={"username"}`（:93-98）一致。
- fetchCaptchaImage GdouAuthClient.kt:203：`"$GDOU_JW_BASE/authserver/getCaptcha.htl?ts=$ts"`，嵌套路径 ✔。

### A4. MFA 三接口 = `https://wfw.gdou.edu.cn/authserver/...`，svc 用 GDOU_SERVICE_URL —— **pass**

- changeReAuthType：GdouAuthClient.kt:107 `"$GDOU_WFW_ORIGIN/authserver/reAuthCheck/changeReAuthType.do"`，fields 含 `service=svc`，origin=GDOU_WFW_ORIGIN。
- getDynamicCodeByReauth：:121 `"$GDOU_WFW_ORIGIN/authserver/dynamicCode/getDynamicCodeByReauth.do"`。
- reAuthSubmit：:152 `"$GDOU_WFW_ORIGIN/authserver/reAuthCheck/reAuthSubmit.do"`，fields 含 `service=svc`。
- `svc = params.service.ifBlank { GDOU_SERVICE_URL }`（:103、:149）✔；三处 origin 均为 GDOU_WFW_ORIGIN（:114、:127、:166）。

### A5. outboundUrl / outboundReferer 改写规则，无 GDOU_WFW_MENU 残留 —— **pass**

- outboundUrl GdouHttpClient.kt:201-208：jw → `rewriteOfficialJwToGateway`（网关嵌入）；authserver → `GDOU_WFW_ORIGIN + pathAndQueryOf(url)`（wfw 根同 path+query，私有 pathAndQueryOf :210-214）；其余原样。
- outboundReferer :216 `= outboundUrl(url)`，同规则；`GDOU_WFW_MENU` 常量已在 GdouModels.kt 删除，全仓 grep 0 命中（见 A7）。

### A6. followRedirects 循环内 Location 经 outboundUrl 改写 —— **pass**

- GdouHttpClient.kt:164-191：首跳 `current = outboundUrl(startUrl)`（:165）；每跳 `rawNext = GdouHtml.resolveUrl(current, headerLoc)`（:173）→ `val next = outboundUrl(rawNext)`（:181）→ `last = get(current, ...)`（:187，get 内部再做一次幂等 outboundUrl）。
- resolveUrl 产生的绝对 Location（仅 forceHttps，GdouHtml.kt:152）若指向官方 jw/authserver 域，一律在 :181 被改写；`//`-协议相对与相对路径先拼回 base（仍为 wfw host）再改写。**循环内不存在直连 jw/authserver 的请求路径。**

### A7. 全仓残留 grep 0 命中 —— **pass**

- 对 `shared/src/`（androidMain/iosMain/jvmMain/commonMain/commonTest/jvmTest 全 source set）grep `GdouAccessMode|probeVerdict|shouldFallbackToGateway|withGatewayFallback|GDOU_WFW_MENU|probeSink|fallbackSink`：**No matches found**。

### A8. GdouAuthClient.kt 无对 GDOU_AUTH_BASE（authserver.gdou.edu.cn）的请求 —— **pass**

- grep `GDOU_AUTH_BASE|authserver\.gdou\.edu\.cn|GDOU_AUTH_HOST` 于 GdouAuthClient.kt：**No matches found**。
- 逐点核对全部出站 URL 字面量（bfp/checkNeedCaptcha/getCaptcha/登录 POST/MFA×3/reAuthLoginView referer/loginUrl）：全部基于 GDOU_JW_BASE（嵌套，outbound 自动改写）或 GDOU_WFW_ORIGIN/GDOU_WFW_PROXY_AUTH_LOGIN，均经 http.get/postForm/getBytes → outboundUrl 改写，无任何官方 authserver 域请求。

---

## B. 潜在缺陷排查（推演，未改码）

### B1. GdouCookieStore 域匹配 —— **pass（推演结论：无缺陷）**

- 存：`addCookie`（GdouCookieStore.kt:58-69）。登录 POST 响应 URL host=wfw.gdou.edu.cn；CAS 关键 cookie（CASTGC/TGC/WFWSESSID 等）标准 `Path=/` → 存 `(name, wfw.gdou.edu.cn, /)`；domain 属性缺省时取 requestUrl.host，与 py requests 行为一致。
- 取：`get`（:71-81）按 `matchesDomain` + `pathMatches` 过滤。对网关嵌入请求 `https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn/...`：host 仍是 wfw.gdou.edu.cn → wfw 域 cookie 可读；path=/ 的 cookie `pathMatches("/", 任何路径)=true`（:103）→ 登录态 cookie 全部正确携带。
- 重点推演（工单点名）：jw 侧 `JSESSIONID`/`route` 若带 `Path=/authserver`，存为 `(JSESSIONID, wfw.gdou.edu.cn, /authserver)`；后续网关嵌入请求的 requestPath=`/https://jw.gdou.edu.cn/authserver/bfp/info` 不以 `/authserver` 开头 → **该 cookie 不被附加**。这与权威 Python requests 的 cookie jar 行为完全一致（同样按响应 URL host=wfw + path 前缀匹配、同样不发送），且 wfw_login.py 实证跑通证明网关上游会话靠 WFWSESSID 维持、不依赖嵌套 path cookie。**判定：该「不匹配」是网关协议的正确表现，非缺陷。**

### B2. classifyLanding 重写后两场景 —— **pass（附 1 条低危观察）**

- 场景 1（POST 成功落地 `https://wfw.gdou.edu.cn/`）：`followRedirects(first.location, entry)` 取回 wfw 门户页（GdouAuthClient.kt:91-95）→ classifyLanding（:207-248）：a 非 reauth；b `isJwLoggedIn(wfw 根 URL)=false`（GdouHtml.kt:76，非网关嵌入）；c hostOf==wfw ✓ 且非网关嵌入 ✓ 且 WFWSESSID 存在（登录成功必有）→ `warmUp()`（:266-271，GET 网关菜单）→ 教务菜单页 `isJwLoggedIn=true` → **LoggedIn** ✔（正是「经 warmUp 网关菜单」路径）。
- 场景 2（落地 CAS 登录页）：b 被 GdouHtml.kt:97（url 含 `/authserver/` 即 false）排除；WFWSESSID 不存在时 c 不命中 → d `pageError` 非空 / body 含 `pwdEncryptSalt` / url 含 `/authserver/login`（GdouAuthClient.kt:236-242）→ **BadPassword** ✔。
- **观察项 O1（低危，当前不可达）**：若存在残留旧 WFWSESSID 且密码错误，c 先命中（GdouAuthClient.kt:225-234）→ warmUp 失败 → 返回 `Failed("统一认证已通过，但教务网关会话未建立")`，文案掩盖真实原因（密码错误）。当前生产不可达：auth.login 唯一调用点 GdouSyncUseCase.ensureSession 在调用前必 `repo.clearCookies()`（GdouSyncUseCase.kt:46-49），UI 仅经 useCase（GdouYangjiangViewModel.kt:174/195），无旁路。仅在未来新增直连 auth.login 入口时需注意。

### B3. GdouHtml.resolveUrl 对网关嵌入页相对 Location —— **pass**

- GdouHtml.kt:144-149 `gatewayEmbedPrefix(base)`：isGatewayJwUrl(base) 时提取 origin（`https://wfw.gdou.edu.cn`）并拼回 `/https://jw.gdou.edu.cn` 前缀。
- GdouHtml.kt:155-156：根相对 Location（`/xtgl/...`、`/sso/jziotlogin?ticket=...`）→ `(gatewayEmbedPrefix(base) ?: origin) + location` → `https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn/xtgl/...`，**正确拼回嵌入前缀**。随后 followRedirects :181 的 outboundUrl 对已是 wfw host 的结果幂等原样，无直连。

### B4. ensureSession 调用序列（isJwSessionAlive → tryResume → login）—— **pass（附 1 条冗余观察）**

- 序列（GdouSyncUseCase.kt:21-61）：`isJwSessionAlive()`（=warmUp，GdouAuthClient.kt:28）失败 → `tryResumeWithCastgc`（=纯 warmUp，:41-42）失败 → `clearCookies()` → `login()`。
- 无逻辑死路：两道探测相互独立，失败后正常落入全新登录，login 首跳为网关入口，链路闭合。
- **观察项 O2（轻微冗余，非缺陷）**：本地会话失效时 warmUp 会被连续调用两次（isJwSessionAlive 与 tryResumeWithCastgc 各一），同一 URL 同一 cookie 状态 → 第二次除网络抖动重试外结果必然相同，多耗 1 次网关菜单 GET。可接受成本；如需优化可在 isJwSessionAlive 失败后直接清 cookie 跳过第二道，或让 tryResume 前置 CASTGC 存在性检查。不阻断。

### B5. 首跳「已登录」场景是否可达 —— **pass（确认不可达，推演证据如下）**

1. auth.login 生产唯一调用点 = GdouSyncUseCase.ensureSession（GdouSyncUseCase.kt:49；全仓 grep `.login(` 仅另命中 jvmTest 的 GdouLiveLoginProbe，其默认 skip）。
2. ensureSession 前置两道拦截：`auth.isJwSessionAlive()`（:30，warmUp 已登录即 LoggedIn 提前 return）→ `tryResumeWithCastgc`（:36，同样 warmUp）。
3. 走到 login 时 cookie 必已清空（:46 clearCookies：CASTGC + jw JSESSIONID + wfw WFWSESSID/urlss，GdouAuthClient.kt:179-189），restoreCookiesIfEmpty 恢复的有效会话会被第 2 步拦截。
4. 因此 login 首跳（网关入口）必然落在未认证 CAS 登录页，salt/execution 非空，「登录页缺少 salt/execution」分支（GdouAuthClient.kt:66-68）在既有调用图下**不可达**，仅作为防御性兜底存在。✔

---

## C. 真实测试

环境（与 build.bat 完全同款，未手动设 JAVA_HOME，AS JBR + gradle-wrapper + 127.0.0.1:8848 代理）：

```
HTTP_PROXY/HTTPS_PROXY=http://127.0.0.1:8848
JAVA_TOOL_OPTIONS/GRADLE_OPTS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8848 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8848
E:/app/android-studio/jbr/bin/java.exe -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <task> --no-daemon --console=plain
```

### C1. `:shared:jvmTest --tests "com.xingheyuzhuan.shiguangschedule.data.api.gdu.*"`（gdou 包）—— **pass**

- 首跑 UP-TO-DATE（复用缓存，BUILD SUCCESSFUL in 9s）；为取证加 `--rerun` 强制重跑：**BUILD SUCCESSFUL in 24s，EXIT_CODE=0**。
- XML 结果统计（shared/build/test-results/jvmTest/）：**9 个测试类、41 用例、failures=0、errors=0、skipped=0**：
  - GdouCioRefusedShapeTest 1、GdouCookieStoreTest 7、GdouHtmlTest 11、GdouHttpClientTest 4、GdouKbListParserTest 7、GdouLiveLoginProbe 1、GdouPasswordEncryptorTest 3、GdouSyncLogCollectorTest 4、GdouSyncLogEntryCompatTest 3。

### C2. `:shared:compileKotlinJvm` —— **pass**

- 常规跑 BUILD SUCCESSFUL in 10s（UP-TO-DATE，EXIT_CODE=0）；加 `--rerun` 强制重编译：**BUILD SUCCESSFUL in 1m 1s，EXIT_CODE=0**。无新增告警。

### C3. GdouLiveLoginProbe skip 分支 —— **pass**

- 源码门槛：GdouLiveLoginProbe.kt:14-20，`GDOU_USERNAME`/`GDOU_PASSWORD` 未设时 `println("skip: ...")` 后 return。
- 本机两变量均未设置；测试 XML system-out 实证：`skip: set GDOU_USERNAME and GDOU_PASSWORD to run live probe` → 走 skip 分支，**未发起任何真实网络请求**（本次会话唯一网络访问为本机 Gradle 依赖代理）。

## D. 工作区核查 —— **pass**

- `git status`：6 个 modified 文件 = GdouAuthClient.kt / GdouHttpClient.kt / GdouModels.kt / GdouSyncUseCase.kt（commonMain）+ GdouHttpClientTest.kt（commonTest）+ GdouCioRefusedShapeTest.kt（jvmTest）；untracked 仅 .codebuddy 工单与报告文件（order_wfw_gateway_1/2/3.md 及 1/2 报告）。
- `git diff --stat`：6 files changed, 158 insertions(+), 326 deletions(-)；GdouScheduleClient.kt **0 改动**（工单允许）。
- 改动文件集与工单 D 项白名单**完全吻合，无工单外改动**。未 commit、未 push。

## 禁止事项遵守

- 未修改任何源文件；未 commit/push；未访问 gdou.edu.cn 任何真实域名（测试仅经本机代理拉取 Gradle 依赖 + 回环 refused 实证复用既有用例）；本报告为新建文件，未覆盖任何已有报告。✔

## 遗留观察汇总（均非阻断，不改码）

1. **O1**（GdouAuthClient.kt:225-234）：残留旧 WFWSESSID + 密码错误时错误文案被 c 分支掩盖（报「教务网关会话未建立」而非「账号或密码错误」）。当前调用图不可达（ensureSession 先 clearCookies），未来新增直连 auth.login 入口时需留意。
2. **O2**（GdouAuthClient.kt:28/41-42 + GdouSyncUseCase.kt:30/36）：本地会话失效时 warmUp 连发两次（isJwSessionAlive 与 tryResumeWithCastgc 等价重复），多 1 次网关菜单 GET，属可接受冗余；可选优化为合并/短路。
3. GdouLiveLoginProbe.kt:32 `TODO("live probe: 需注入凭据仓库")` 仍在（仅设凭据环境变量时触达），沿承工单 2 报告记录。
