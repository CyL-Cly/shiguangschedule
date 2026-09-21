# 工单 order_wfw_gateway_3：独立验收（GDOU WebVPN 网关改造）

仓库：E:/code/Android/shiguangschedule（分支 main）。**只读验收 + 真实测试，禁止修改任何源文件、禁止 commit/push。**

## 背景

工单 1/2 已完成「GDOU 删 jw 直连，一律走 WebVPN 网关」改造。权威协议：**E:/code/py/gdoulogin/wfw_login.py**（已跑通）。两份执行报告：`.codebuddy/order_wfw_gateway_1_report.md`、`.codebuddy/order_wfw_gateway_2_report.md`。

## 验收项（逐项给出 pass/fail + 证据）

### A. 协议一致性（对照 wfw_login.py 逐条核对 Kotlin）

核对 `shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/` 四文件（GdouModels/GdouHttpClient/GdouAuthClient/GdouSyncUseCase）：

1. 登录首跳 = `GET GDOU_JW_BASE`（outbound 后为 `https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn`），页面就地取 salt/execution/lt ✔/✘
2. POST = `https://wfw.gdou.edu.cn/authserver/login?service=<urlencode(GDOU_SERVICE_URL)>`，Origin=wfw 根，Referer=入口 ✔/✘
3. bfp / checkNeedCaptcha / getCaptcha = 嵌套路径 `GDOU_JW_BASE/authserver/...` ✔/✘
4. MFA 三接口（changeReAuthType/getDynamicCodeByReauth/reAuthSubmit）= `https://wfw.gdou.edu.cn/authserver/...`，svc 用 GDOU_SERVICE_URL ✔/✘
5. `outboundUrl`：jw→网关嵌入；authserver→wfw 根同 path；wfw/其它原样；`outboundReferer` 同规则且**无** GDOU_WFW_MENU 残留 ✔/✘
6. `followRedirects` 循环内 Location 经 `outboundUrl` 改写（无直连 jw/authserver 的路径）✔/✘
7. 全仓（shared/src 全部 source set + 测试）grep `GdouAccessMode|probeVerdict|shouldFallbackToGateway|withGatewayFallback|GDOU_WFW_MENU|probeSink|fallbackSink` 0 命中 ✔/✘
8. GdouAuthClient.kt 无任何对 `GDOU_AUTH_BASE`（authserver.gdou.edu.cn）的请求 ✔/✘

### B. 潜在缺陷排查（重点，发现问题要给文件+行号+证据，不改码）

1. `GdouCookieStore` 域匹配：登录 POST 后 `Set-Cookie`（domain=wfw.gdou.edu.cn）能否正确存取；`route`/`JSESSIONID` path=/authserver 与 `isGatewayJwUrl` 请求（path=/https://jw.../authserver/bfp/info）的 cookie path 匹配是否正确——推演并给出结论。
2. `classifyLanding` 重写后：POST 成功落地 `https://wfw.gdou.edu.cn/`（非 jw URL）时能否正确判 LoggedIn（经 warmUp 网关菜单）；落到 CAS 登录页时是否正确判 BadPassword。
3. `GdouHtml.resolveUrl` 对网关嵌入页面的相对 Location（`/xtgl/...`、`/sso/jziotlogin?ticket=`）是否拼回嵌入前缀。
4. `tryResumeWithCastgc` 改为纯 warmUp 后，`GdouSyncUseCase.ensureSession` 的调用序列（isJwSessionAlive → tryResume → login）是否存在重复请求网关菜单的浪费或逻辑死路。
5. 首跳 `http.get(GDOU_JW_BASE)` 在**已登录**（restore cookie 后）场景：返回教务页面而非登录页 → salt 为空 → dropSessionCookies 再试 → 仍空 → Failed「登录页缺少 salt/execution」。确认该场景实际不会走到（ensureSession 前置 isJwSessionAlive 已拦截），若无问题给出推演证据。

### C. 真实测试

1. 用 build.bat 同款方式（JBR java + gradle-wrapper，勿手动设 JAVA_HOME）跑 `:shared:jvmTest --tests "com.xingheyuzhuan.shiguangschedule.data.api.gdou.*"`，记录结果。
2. 跑 `:shared:compileKotlinJvm`，记录结果。
3. （可选加分）若存在 GdouLiveLoginProbe 类似入口且环境变量未设，确认其走 skip 分支、未发真实请求。

### D. 工作区核查

`git status` + `git diff --stat`：改动文件集应恰为 GdouModels/GdouHttpClient/GdouAuthClient/GdouSyncUseCase/GdouScheduleClient(0 改动允许)/GdouHttpClientTest/GdouCioRefusedShapeTest + .codebuddy 报告文件。**发现工单外改动立即列为 fail。**

## 产出

验收报告 `E:/code/Android/shiguangschedule/.codebuddy/order_wfw_gateway_3_report.md`：逐项 pass/fail、证据（路径+行号+代码摘录）、测试命令与输出摘要、结论（整体 pass / 有条件 pass / fail）。

## 禁止

修改源文件、commit/push、访问 gdou.edu.cn 任何真实域名、删除或覆盖已有报告。
