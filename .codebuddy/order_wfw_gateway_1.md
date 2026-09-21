# 工单 order_wfw_gateway_1：GDOU 删 jw 直连，HTTP/认证层改走 WebVPN 网关

仓库：E:/code/Android/shiguangschedule（分支 main，勿 commit、勿 push）

## 幂等检查（先做）
先 `git log --oneline -3` 与 `git diff --stat` 检查下列目标是否已实现；已完成的项跳过并在报告中说明。

## 背景与权威协议

校外 `jw.gdou.edu.cn` TCP 直连被拒。权威实证协议在 **E:/code/py/gdoulogin/wfw_login.py**（先完整读它，它是唯一权威，已跑通拿到课表）。要点：

1. 登录入口 `GET https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn` → 未登录时 **URL 不变、body 就是 CAS 登录页**（含 `pwdEncryptSalt` / `execution` / `lt`）。
2. bfp / checkNeedCaptcha / getCaptcha 走**嵌套路径** `wfw/https://jw.gdou.edu.cn/authserver/...`（指纹 Cookie 落 wfw 域）。
3. 登录 POST 走**网关根** `https://wfw.gdou.edu.cn/authserver/login?service=<urlencode(https://jw.gdou.edu.cn/sso/jziotlogin)>`，`Origin: https://wfw.gdou.edu.cn`，`Referer: 入口`，表单字段不变。
4. 成功 302 → `https://wfw.gdou.edu.cn/` → 200；cookie 全在 wfw 域（WFWSESSID / urlss / urlss_jw / MULTIFACTOR_BROWSER_FINGERPRINT / JSESSIONID(path=/authserver)）。
5. 全程**禁止**访问 `jw.gdou.edu.cn` 与 `authserver.gdou.edu.cn`（校外 TCP 被拒）。

现有代码位置：`shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/`。当前实现仍在探测 jw 直连（`GdouAccessMode` / `probeAccessMode` / `probeVerdict` / `withGatewayFallback`），CAS 打 `authserver.gdou.edu.cn`，登录 service 写成 wfw 门户 —— 全部要删/改。

## 改动 1：GdouModels.kt

- 删除 `const val GDOU_WFW_MENU`（约第 14 行，`wfw/xtgl/index_initMenu.html` 未嵌 jw，是错误路径）。
- 删除 `enum class GdouAccessMode`（约第 31 行）。
- 其余常量一律保留：`GDOU_WFW_ORIGIN` / `GDOU_WFW_HOME` / `GDOU_WFW_GATEWAY_PREFIX` / `GDOU_WFW_PROXY_AUTH_LOGIN` / `GDOU_AUTH_BASE` / `GDOU_JW_BASE` / `GDOU_SERVICE_URL` / `GDOU_JW_MENU` 等仍被引用。

## 改动 2：GdouHttpClient.kt

**删除**：
- 字段 `jwLock`、`mode`、`probeSink`、`fallbackSink`；随之无用的 import（`Mutex`/`withLock`/`Volatile`）。
- 方法 `accessMode()`、`probeAccessMode()`、`withGatewayFallback()`、`gatewayFallback()`、单参 `outboundReferer(url)` 旧实现。
- companion 的 `probeVerdict()`、`shouldFallbackToGateway()`。
- 类头与注释里所有「DIRECT→GATEWAY 降级」KDoc。

**保留**：`hopSink`、`requestFailureSink`、`connectivityText`、`describeConnectivity`、`isConnectivityError`、`REDIRECTS`、`CONNECTIVITY_HINTS`、`cookieStore`、`json`、`client`、`get/getBytes/postForm/postRaw`、`followRedirects`、`parseJson`、`toExchange`、`notifyHop`。

**修改**：
- `outboundUrl(url)`：`forceHttps` 后：
  - host == `GDOU_JW_HOST` → `GdouHtml.rewriteOfficialJwToGateway(url)`（嵌网关前缀）；
  - host == `GDOU_AUTH_HOST` → `"https://wfw.gdou.edu.cn"` + 原 path+query（防 authserver 绝对 Location 直连被拒）；
  - 其余原样。删除 accessMode 分支。
- `outboundReferer(url)`：与 `outboundUrl` 同规则改写（jw → **同一路径**的网关嵌入 URL；**禁止**再返回 `GDOU_WFW_MENU`）。删除 WFWSESSID 判定与 `gateway` 参数。
- `get/getBytes/postForm/postRaw`：去掉 `withGatewayFallback` 包装，直接 `send(outboundUrl(url), referer?.let { outboundReferer(it) })`，并保留统一的失败记录包装：

  ```kotlin
  try {
      val response = send(requestUrl, refererUrl)
      return toExchange(response, asBytes)  // 非 bytes 版 asBytes=false
  } catch (e: CancellationException) {
      throw e
  } catch (e: Throwable) {
      requestFailureSink?.invoke(requestUrl, e)
      throw e
  }
  ```

  即：记录失败后**重新抛出**，不再降级重试、不再吞异常。
- `followRedirects`：结构不变；循环里 `rawNext` 已经过 `outboundUrl()`（保证 Location 指向官方 jw/authserver 时也被改写），确认即可。

## 改动 3：GdouAuthClient.kt（对齐 wfw_login.py）

1. 删 `loginService()`；删所有 `http.accessMode()` 分支。
2. `registerFingerprint(fingerprint, referer)`：URL 改 `"$GDOU_JW_BASE/authserver/bfp/info"`（outboundUrl 自动嵌成 `wfw/https://jw.gdou.edu.cn/authserver/bfp/info`）；referer 缺省用 `GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)`。
3. `needCaptcha`：URL 改 `"$GDOU_JW_BASE/authserver/checkNeedCaptcha.htl"`；referer 用入口 `GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)`。
4. `fetchCaptchaImage`：URL 改 `"$GDOU_JW_BASE/authserver/getCaptcha.htl?ts=..."`；referer 同上。
5. `login()`：
   - 登录页第一跳改 `http.get(GDOU_JW_BASE)`（outbound 后即网关入口，页面就地含 salt/execution/lt）；salt 缺失时 `dropSessionCookies()` 再 GET 一次的重试逻辑保留。
   - POST action = `GDOU_WFW_PROXY_AUTH_LOGIN + encodeService(GDOU_SERVICE_URL)`；`origin = GDOU_WFW_ORIGIN`；`referer = GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)`。
   - POST 后：`first.status in 301..308 && !first.location.isNullOrBlank()` → `landed = http.followRedirects(first.location, 入口)`；否则 `landed = first`。然后 `classifyLanding(landed)`。
6. `classifyLanding` 重写（判定顺序）：
   a. reAuth 判定：保留现有 `isReauth` + `parseReauthParams` + `NeedMfa` 分支（parseReauthParams 的 service 默认值已是 `GDOU_SERVICE_URL`，不动）。
   b. `GdouHtml.isJwLoggedIn(current.url, current.body)` → `warmUp()` 确认 → `LoggedIn`。
   c. 落地为 wfw 门户（`GdouHtml.hostOf(url) == GDOU_WFW_HOST` 且非网关嵌入路径 `isGatewayJwUrl`）且 cookieStore 有 `WFWSESSID` → `warmUp()` 为 true → `LoggedIn`；false → `Failed("统一认证已通过，但教务网关会话未建立")`。
   d. body 含 `pwdEncryptSalt` 或 url 含 `/authserver/login` → `BadPassword(pageError 非空 ? pageError : "账号或密码错误")`。
   e. 其余 `Failed`（沿用现有中文文案风格，严禁输出英文异常原文）。
7. `leaveSso` 重写：**禁止**再 GET `GDOU_JW_MENU`（官方 jw，校外被拒）。仅当 current 停在 sso 路径（`isSsoPath`，含网关嵌入形态）且 30x/页面 meta 跳转时继续 `followRedirects`（outboundUrl 保持网关路径）；否则原样返回。
8. `tryResumeWithCastgc(fingerprint)` 重写为本地 cookie 恢复探测：`return if (warmUp()) GdouAuthResult.LoggedIn else GdouAuthResult.Failed("本地会话不可用")`。删 `registerFingerprint` 调用与 `followRedirects(loginUrl(...))`。参数 `fingerprint` 保留签名但可不再使用（加 `@Suppress("UNUSED_PARAMETER")` 或改名 `_` 视调用方而定，调用方在 GdouSyncUseCase 传了 fingerprint，保持签名不变）。
9. `warmUp()` 重写：
   - 删「无 WFWSESSID return false」门槛与 accessMode 分支（现在 warmUp 本身就是探测，未登录会拿到 CAS 登录页 → `isJwLoggedIn=false`，无副作用）。
   - 直接 `http.followRedirects(GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_MENU))`；`return resp.status == 200 && GdouHtml.isJwLoggedIn(resp.url, resp.body)`。外层 `runCatching { ... }.getOrDefault(false)` 保留。
10. `warmUpGateway()`（兜底：仅菜单返回 CAS 页时第二次 POST）：
    - `followRedirects(menuUrl, GDOU_WFW_MENU)` 的 referer 参数改 `GdouHtml.rewriteOfficialJwToGateway(GDOU_JW_BASE)`。
    - 其余逻辑（menu → `looksLikeLoginPage` → 读 creds/salt/execution/lt → POST `GDOU_WFW_PROXY_AUTH_LOGIN` → 落地判定）保留。
11. MFA `sendMfaCode` / `submitMfaCode`：
    - URL 前缀 `"$GDOU_AUTH_BASE/..."` 全部改 `"https://wfw.gdou.edu.cn/authserver/..."`（即 `GDOU_WFW_ORIGIN + "/authserver/..."`），否则 path=/authserver 的 JSESSIONID 带不上。
    - `svc` 判定删 accessMode 分支：`val svc = params.service.ifBlank { GDOU_SERVICE_URL }`。
    - referer fallback 改 `"https://wfw.gdou.edu.cn/authserver/reAuthCheck/reAuthLoginView.do"`。
12. `dropSessionCookies()`：删 `accessMode()` 条件，无条件清除 wfw 域 `WFWSESSID`/`urlss`。
13. 清理 import：`io.ktor.http.Url` 若无使用则删；不新增其它依赖。

## 改动 4：GdouSyncUseCase.kt

- 删 `http.probeSink = ...` 与 `http.fallbackSink = ...` 赋值块（约 85-99 行）及 `fallbackNotified` 变量；**保留** `http.requestFailureSink = ...` 块（约 100-105 行）。
- 删 `syncNowLogged` 中 `if (http.accessMode() == GdouAccessMode.GATEWAY) { log.info("已通过 wfw 网关建立教务会话") }`（约 135-137 行）。
- `ensureSession` 文案：`"打开教务 $GDOU_JW_HOST"` → `"打开教务网关 $GDOU_WFW_HOST"`。
- 随之无用的 import 清理。

## 完成标准

1. 四个文件改动完成；`grep -n "accessMode\|GdouAccessMode\|GDOU_WFW_MENU\|probeSink\|fallbackSink\|withGatewayFallback\|probeVerdict\|shouldFallbackToGateway" shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/` 无残留（`requestFailureSink`/`connectivityText` 除外）。
2. grep 确认 GdouAuthClient.kt 不再向 `GDOU_AUTH_BASE`（authserver.gdou.edu.cn）发任何请求（该常量允许存在于 GdouModels.kt 定义与 GdouHtml.forceHttps）。
3. 仓库根运行 `gradlew.bat :shared:compileKotlinJvm` 编译通过（不要手动设置 JAVA_HOME）。注意：`GdouHttpClientTest` 仍引用已删符号属下一工单范围，main 源集编译通过即可。
4. 禁止改动工单未列出的文件；禁止 commit/push；禁止运行任何真实网络探针（不得访问 gdou.edu.cn 任何域名）。
5. 产出对账文件 `E:/code/Android/shiguangschedule/.codebuddy/order_wfw_gateway_1_report.md`：逐项列 done/partial/todo 与关键 diff 摘要、编译命令与结果。
