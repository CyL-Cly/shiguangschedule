# GDOU 登录链实证结论（2026-09-21，Playwright + 纯 HTTP 双重复核）

## 已证实的完整链路
1. wfw 未登录首页 200（无跳转），仅下发 WFWSESSID（未登录态）
2. CAS 登录页 = https://authserver.gdou.edu.cn/authserver/login?service=https%3A%2F%2Fwfw.gdou.edu.cn%2F
   - authserver 校外直连可达（本机校内），login 页 200
   - 表单字段：username / password(AES) / captcha / _eventId=submit / cllt=userNameLogin / dllt=generalLogin / lt(空) / execution(e1s1)
   - salt: input#pwdEncryptSalt（每会话随机）；AES-CBC-PKCS7(key=salt, iv=随机16字符)，明文=随机64字符+密码 —— 与现有 GdouPasswordEncryptor 完全一致
   - 验证码：checkNeedCaptcha.htl?username=xxx → {"isNeed":false}（当前账号不需要）；图片 /authserver/getCaptcha.htl
   - 提交是**原生 form POST**（pwdFromId，action=login URL），非 ajax
3. POST 成功 → 302 reAuthLoginView.do（该账号强制 MFA，reAuthParams: reAuthType=4 企业微信, isMultifactor=true, service=https://wfw.gdou.edu.cn/, reAuthUserId=202544021201, pwdEncryptSalt 存在）
   - changeReAuthType.do → {"code":"1"}
   - getDynamicCodeByReauth.do → {"res":"wechat_success","returnMessage":"验证码发送成功"}
   - 后续 reAuthSubmit.do + dynamicCode（Kotlin 已有实现）
4. （浏览器完成 MFA 后）302 → //wfw.gdou.edu.cn/?ticket=ST-...platformserver2
   - wfw 校验 ticket：302 自跳两次 → 302 Location=/ → 200 首页
   - 浏览器随后 POST /sso-token.php 与 /api/auth/sso-broker（页面 JS 发起）
   - **登录成功的 wfw cookie 集：WFWSESSID + urlss + urlss_file + db_agent_token**
5. STEP4 直接 GET 网关 jw 菜单 → 返回的是**内嵌 CAS 登录页**（service=jziotlogin），说明仅 WFWSESSID 不够，jw 网关会话需经 CAS ticket 建立：
   - 内嵌页 form action=https://wfw.gdou.edu.cn/authserver/login?service=https%3A%2F%2Fjw.gdou.edu.cn%2Fsso%2Fjziotlogin
   - 即：**wfw 域下 CAS 再走一遍 service=jziotlogin**（CASTGC 已有则免密 302 直发 ticket）
   - 最终 step4/5 cookie 出现 urlss_jw（jw 网关通道标记）——注意 urlss_jw 是在(浏览器内)访问 authserver 网关代理后出现

## 对 Kotlin 现状的映射
- GdouAuthClient.login 的 service 必须为 https://wfw.gdou.edu.cn/（GATEWAY 模式已如此）✔
- CAS 表单字段与加密与现有实现一致 ✔（lt 为空也下发 execution）
- MFA 链路与现有实现一致 ✔
- 缺失/需改：
  1. 登录 wfw 后没有对 **jw 网关的 jziotlogin SSO**（现 warmUp 只 GET 菜单，若 jw 会话未建立会拿到 CAS 登录页）——需要实现：GATEWAY 下 followRedirects(https://wfw.gdou.edu.cn/authserver/login?service=<jziotlogin>)（CASTGC 免密）→ ticket 回流网关 → urlss_jw/JSESSIONID 建立
  2. wfw ticket 回流后页面 JS 调 sso-token.php/sso-broker —— 纯 HTTP 需实测是否必须（可能 WFWSESSID+urlss 已由 302 链下发）
  3. classifyLanding 的 GATEWAY 判定已用 WFWSESSID ✔
  4. isJwLoggedIn 需识别"内嵌 CAS 登录页"为未登录（含 pwdEncryptSalt 已判 false）✔

## 待真机/后续验证
- reAuthSubmit 成功后 302 链是否直达 wfw?ticket=（预计是）
- wfw ticket 回流是否必须 sso-token.php/sso-broker 才发 urlss
- jziotlogin 经网关后 ticket 参数是否被网关转交 jw 上游（urlss_jw 出现说明网关在代持 jw 会话）

## 补充验证（第二轮）
- state.json 的 cookie 不可跨 UA/IP 复用：urlss/urlss_jw 在但网关菜单仍返回内嵌 CAS 登录页 → jw 网关会话由网关代持，绑定 WFWSESSID，urlss_jw 只是通道记录
- CASTGC 在 state.json 里已消失（浏览器关闭前有，导出时 wfw 域的 route/JSESSIONID 残留，authserver CASTGC 不在）——说明导出时机在会话清理后或 CASTGC httpOnly 未入 storage?（实测 playwright Cookies() 含 httpOnly，此处是浏览器端 CASTGC 被票据消费后清理）
- 未登录 GET authserver login?service=jziotlogin → 200 登录页 ✔（CAS 标准行为）
- 结论：**wfw 登录成功后，Kotlin 需要：a) 用 CASTGC(直连 authserver 或 wfw 代理 /authserver/) 请求 service=jziotlogin 的 login URL；b) 跟 302 ticket 回流（wfw 域 /authserver/... 或 jw 直连）建立网关 jw 会话；c) 用 warmUp 菜单验证 sessionUserKey**

## 第三轮校外验证（MFA 人工完成，决定性发现）
- MFA 完成回流 wfw（WFWSESSID+urlss+urlss_file+db_agent_token 下发）✔
- STEP4a 直连 authserver login?service=jziotlogin：**CASTGC(TGT-1323) 免密 302 发 ticket 到 jw.gdou.edu.cn/sso/jziotlogin?ticket=ST-...** ✔（假设证实：MFA 完成后 jziotlogin 免密成立）
- 浏览器随后 GET jw.gdou.edu.cn/sso/jziotlogin?ticket=... → **ERR_CONNECTION_REFUSED（jw 校外拒绝，预期）** → Chrome 错误页
- 但注意：前两轮纯 HTTP 测试（python，同 cookie）对 login?service=wfw/jziotlogin 都拿到 200 登录页而非 302 —— 与浏览器行为矛盾。
  解释：CAS 校验 bfp/UA/fingerprint（MULTIFACTOR_BROWSER_FINGERPRINT + happyVoyage），纯 HTTP 客户端没带 browser_fingerprint localStorage/UA 不一致 → CAS 不认 TGT 免密。**Kotlin 客户端需带一致的 fingerprint 体系（bfp/info + MULTIFACTOR_BROWSER_FINGERPRINT cookie）**
- STEP4a 代理路径（wfw/authserver/login?service=jziotlogin）返回登录页：wfw 代理的 authserver 会话与直连 CASTGC 体系独立（JSESSIONID(wfw域 /authserver 路径)），此路径不可用于免密
- 关键结论：**校外正确流程 = CAS 直连登录+MFA → 直连 authserver 免密换 jziotlogin ticket → ticket 必须交给 wfw 网关代为消费（jw 直连校外 refused，浏览器 GET jw/sso/jziotlogin?ticket= 会失败）**
- 待最终验证：**把 ticket URL 交给 wfw 网关**（GET https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn/sso/jziotlogin?ticket=ST-...）是否由网关代持建立 jw 会话（urlss_jw 下发）——这是 Kotlin 侧最后一步

## 第四轮（决定性）：网关 jw 会话的正确建立方式 ✔ 已实证
- MFA 完成回流 wfw → GET 网关菜单 → 返回**内嵌 CAS 登录页**（service=jziotlogin，wfw 代理 CAS）
- 在该页**第二次提交 CAS 表单**（同账号密码，service=jziotlogin）：
  - POST wfw.gdou.edu.cn/authserver/login?service=<jziotlogin>
  - **不再触发 MFA**（1.6 秒直接 302！同一浏览器/CAS 会话已过 MFA，二因子免重复）
  - 302 → wfw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs（jw 菜单，网关嵌入路径下）
  - 会话标记 sessionUserKey=true gnmkdm=true ✔
  - 随后 STEP5 网关课表页 200 且含 sessionUserKey ✔✔
- **完整校外链路（最终版）**：
  1. CAS 直连 login?service=wfw（AES 密码，MFA 企业微信/短信）
  2. ticket 回流 wfw → WFWSESSID+urlss（wfw 门户登录完成）
  3. GET 网关 jw 菜单 → 得内嵌 CAS 登录页
  4. 在内嵌页第二次 POST CAS（service=jziotlogin，wfw 代理域）→ 免 MFA 直发 → 网关 jw 会话建立（sessionUserKey）
  5. 之后网关下所有 jw 请求（课表 POST xskbcx_cxXsgrkb 等）直接可用
- ticket 直发 jw（直连）校外 refused 不可用；CASTGC 免密换 ticket 后交网关消费也不行（网关把 jw 的 CAS 重定向也代理了，链路断在内嵌登录页）→ **第二次 CAS 提交是唯一正确路径**
