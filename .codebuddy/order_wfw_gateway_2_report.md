# 工单 order_wfw_gateway_2 执行报告

日期：2026-09-21
仓库：E:/code/Android/shiguangschedule（分支 main，未 commit / 未 push）

## 幂等检查（先做）

- `git log --oneline -5`：HEAD = 72ef12b（wip 快照），与工单 1 报告一致，无新提交。
- `git diff --stat`（执行前）：仅工单 1 的 4 个文件（GdouAuthClient / GdouHttpClient / GdouModels / GdouSyncUseCase）为未提交状态；本工单 3 个目标文件均无改动 → 全部执行。
- 已核对 `.codebuddy/order_wfw_gateway_1_report.md`：工单 1 四项改动均 done，本工单无重复项需要跳过。

## 逐项对账

| 项 | 文件 | 状态 | 说明 |
|---|---|---|---|
| 改动 1 | GdouScheduleClient.kt | done（零改动，已满足） | 见下 |
| 改动 2 | GdouHttpClientTest.kt | done | 见下 |
| 改动 3 | GdouCioRefusedShapeTest.kt | done | 见下 |
| 改动 4 | 全仓残留清查 | done | 0 残留，无业务调用点 |

### 改动 1（GdouScheduleClient.kt）

- 两处过期判定（`looksLikeLoginPage` / `!isJwLoggedIn` 分支 L23-27，POST 后 L50-52）已有「教务会话已过期」文案，按工单保留，未动。
- 901 处理：`fetch()` 中 `courseResp.status != 200` 分支（现 L40-49）已存在 `if (loginLost || courseResp.status == 901) append("，登录已失效")`，文案中文。已对照权威参考 `E:/code/py/gdoulogin/wfw_login.py` L162-163（`resp.status_code == 901` → 网关会话过期，需重新登录）确认语义一致 → 按工单「若已满足则不动」，**未修改该文件**。
- 业务 URL 仍写官方 jw 常量（`GDOU_KB_INDEX` / `GDOU_KB_JSON` / `GDOU_JW_BASE`），HTTP 层自动嵌网关前缀，未改。

### 改动 2（GdouHttpClientTest.kt，-73 行）

- 已删除 5 个测试（被测符号 `GdouAccessMode` / `probeVerdict` / `shouldFallbackToGateway` 已在工单 1 删除）：
  - `probeVerdict_directWhenRedirectPointsToOfficialJwPath`（原 L10-25）
  - `probeVerdict_directWhenRedirectPointsToAuthServer`（原 L27-33）
  - `probeVerdict_gatewayWhenRedirectGoesElsewhere`（原 L35-47）
  - `probeVerdict_directOnlyForJwSessionPageOn200`（原 L49-65）
  - `shouldFallbackToGateway_onlyOfficialJwDirectRequests`（原 L141-155）
- 已保留 4 个测试且全部执行通过：
  - `isConnectivityError_matchesRefusedDnsTimeoutAcrossCauseChain`
  - `isConnectivityError_matchesRefusedInsideEngineWrapper`
  - `isConnectivityError_stopsAtCauseChainDepth8`
  - `connectivityText_mapsRefusedTimeoutUnreachableToChinese`
- import 清理核对：`ConnectTimeoutException` 被保留测试 L18/L73 使用；`assertEquals` / `assertFalse` / `assertTrue` 均被保留测试使用 → **无无用 import，未删任何 import**。

### 改动 3（GdouCioRefusedShapeTest.kt，1 行）

- KDoc 原「withGatewayFallback 的 DIRECT→GATEWAY 降级判定依赖它。」→ 改为「requestFailureSink 的连接类失败判定（中文映射 connectivityText）依赖它。」
- 测试体（本机回环 refused 实证、isConnectivityError / connectivityText 断言）未动。

### 改动 4（全仓残留清查）

- grep `GdouAccessMode|probeVerdict|shouldFallbackToGateway|withGatewayFallback|GDOU_WFW_MENU|probeSink|fallbackSink` 于 `shared/src/`（覆盖 androidMain/iosMain/jvmMain/commonMain/commonTest/jvmTest）：
  - 改动前命中仅 2 个文件，均属本工单清理范围：`GdouHttpClientTest.kt`（被删测试引用）与 `GdouCioRefusedShapeTest.kt` L15（KDoc）。
  - 改动后 **0 命中**。
  - 无 UI / ViewModel 等业务调用点命中，无需报告项。
- `GdouModels.kt`：grep 确认 `GDOU_WFW_MENU` 与 `GdouAccessMode` 均已不存在（工单 1 已删，无残留）。

## 验证（测试命令与结果）

- 工单字面命令 `gradlew.bat :shared:jvmTest --tests "com.xingheyuzhuan.shiguangschedule.data.api.gdou.*"`：本机 PATH 无 java、JAVA_HOME 未设，脚本自身报错退出（环境限制，非代码问题；与工单 1 报告记录一致）。
- 按 build.bat 同款方式（未手动设置 JAVA_HOME，仅设代理，与 build.bat 一致）在仓库根执行：

```
HTTP_PROXY/HTTPS_PROXY=http://127.0.0.1:8848
JAVA_TOOL_OPTIONS/GRADLE_OPTS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8848 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8848
E:/app/android-studio/jbr/bin/java.exe -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :shared:jvmTest --tests "com.xingheyuzhuan.shiguangschedule.data.api.gdou.*" --no-daemon --console=plain
```

结果：**BUILD SUCCESSFUL in 29s**（23 tasks，10 executed / 13 up-to-date），0 失败。

`jvmTest` 实际执行的 gdou 包测试（XML 结果核对）：
- `GdouHttpClientTest`：4 个保留测试 ✔（5 个被删测试已不在结果中）
- `GdouCioRefusedShapeTest.cioRefusedExceptionMatchesConnectivityHints` ✔
- 既有测试回归通过：`GdouCookieStoreTest`（7）、`GdouHtmlTest`（10）、`GdouKbListParserTest`（8）、`GdouPasswordEncryptorTest`（3）、`GdouSyncLogCollectorTest`（4）、`GdouSyncLogEntryCompatTest`（3）。
- `GdouLiveLoginProbe.loginAndFetchSchedule` 亦有执行，但该测试带环境变量门槛（`GDOU_USERNAME`/`GDOU_PASSWORD` 未设时仅打印 skip 即返回，见该文件 L15-20）；本机未设这些变量，实际走 skip 分支，**未发起任何真实网络请求**。

## 禁止事项遵守

- 未 commit、未 push。✔
- 未运行任何真实网络探针；唯一网络访问为本机回环 refused 实证（127.0.0.1 临时端口）与 Gradle 依赖代理，未访问 gdou.edu.cn 任何域名。✔
- 本工单实际改动仅 2 个文件：`GdouHttpClientTest.kt`（-73）、`GdouCioRefusedShapeTest.kt`（1 行改写）；`git diff --stat` 中其余 4 个文件为工单 1 遗留改动，本工单未触碰。未改动工单未列出的文件。✔

## 遗留观察（不属本工单范围，仅记录）

- `GdouLiveLoginProbe.kt` L32 存在 `TODO("live probe: 需注入凭据仓库")`，仅在设置了 GDOU 凭据环境变量时才会触达；本工单按「禁止改动工单未列出的文件」未处理。
