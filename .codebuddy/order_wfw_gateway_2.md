# 工单 order_wfw_gateway_2：ScheduleClient 901 处理 + 测试清理对齐

仓库：E:/code/Android/shiguangschedule（分支 main，勿 commit、勿 push）

**前置**：工单 order_wfw_gateway_1 已完成（`.codebuddy/order_wfw_gateway_1_report.md` 可参考）。main 源集已删除 `GdouAccessMode` / `probeVerdict` / `shouldFallbackToGateway` / `withGatewayFallback` / `GDOU_WFW_MENU` / `probeSink` / `fallbackSink`。

## 幂等检查（先做）
先读 `git log --oneline -5`、`git diff --stat` 与 `.codebuddy/order_wfw_gateway_1_report.md`，核对工单 1 已完成项；已完成的项跳过并说明。

## 改动 1：GdouScheduleClient.kt

路径 `shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/GdouScheduleClient.kt`：

- `fetch(term)` 中两处过期判定（`looksLikeLoginPage` / `!isJwLoggedIn` 与 `courseResp.status != 200` 分支）已有「教务会话已过期」处理，保留。
- **新增**：POST `courseResp.status == 901` 时（网关会话未认证的特征码，参考 E:/code/py/gdoulogin/wfw_login.py `fetch_kb`）也 error「教务会话已过期（网关未认证）」。现有代码在 status != 200 分支里已有 `|| courseResp.status == 901` 追加「登录已失效」，确认其存在且文案中文，若已满足则不动。
- 不改其它逻辑；业务 URL 仍写官方 jw 常量（HTTP 层自动嵌网关前缀）。

## 改动 2：GdouHttpClientTest.kt

路径 `shared/src/commonTest/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/GdouHttpClientTest.kt`：

- 删除测试：`probeVerdict_directWhenRedirectPointsToOfficialJwPath`、`probeVerdict_directWhenRedirectPointsToAuthServer`、`probeVerdict_gatewayWhenRedirectGoesElsewhere`、`probeVerdict_directOnlyForJwSessionPageOn200`、`shouldFallbackToGateway_onlyOfficialJwDirectRequests`（被测符号已删）。
- 保留测试：`isConnectivityError_matchesRefusedDnsTimeoutAcrossCauseChain`、`isConnectivityError_matchesRefusedInsideEngineWrapper`、`isConnectivityError_stopsAtCauseChainDepth8`、`connectivityText_mapsRefusedTimeoutUnreachableToChinese`。
- 清理随之无用的 import（如 `io.ktor.client.network.sockets.ConnectTimeoutException` 若仅被删测试使用）。

## 改动 3：GdouCioRefusedShapeTest.kt

路径 `shared/src/jvmTest/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/GdouCioRefusedShapeTest.kt`：

- 类 KDoc 里「withGatewayFallback 的 DIRECT→GATEWAY 降级判定依赖它」一句改为「requestFailureSink 的连接类失败判定（中文映射 connectivityText）依赖它」。
- 其余（本机回环 refused 实证、断言）不动。

## 改动 4：全仓残留清查

- grep `GdouAccessMode|probeVerdict|shouldFallbackToGateway|withGatewayFallback|GDOU_WFW_MENU|probeSink|fallbackSink` 于 `shared/src/`（含 androidMain/iosMain/jvmMain/commonTest/jvmTest）：
  - 命中且属测试/注释/KDoc → 一并清理或改写。
  - 命中且属其它业务调用点（如 UI/ViewModel 引用）→ 报告（不擅自改），在报告文件中列出文件+行号+代码摘录。
- 确认 `shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/api/gdou/GdouModels.kt` 中 `GDOU_WFW_MENU` 与 `GdouAccessMode` 已不存在（工单 1 范围，若残留则补删）。

## 验证

仓库根运行 `gradlew.bat :shared:jvmTest --tests "com.xingheyuzhuan.shiguangschedule.data.api.gdou.*"`（不要手动设置 JAVA_HOME），全部通过。

## 禁止事项

- 禁止 commit/push；禁止运行任何真实网络探针（不得访问 gdou.edu.cn 任何域名）。
- 禁止改动工单未列出的文件（除「残留清查」报告的只读 grep）。

## 产出

对账文件 `E:/code/Android/shiguangschedule/.codebuddy/order_wfw_gateway_2_report.md`：逐项 done/partial/todo、测试命令与结果、残留清查发现（若有）。
