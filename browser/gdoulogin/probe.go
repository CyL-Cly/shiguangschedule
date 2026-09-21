package main

// GDOU 登录链实证脚本（Go + Playwright + 系统 Edge）：
//   1. 打开 wfw 首页 → 观察未登录跳转
//   2. wfw 跳转 authserver CAS（service=https://wfw.gdou.edu.cn/）→ 填表单登录
//   3. 记录 ticket 回流、MFA（reAuth）出现形态
//   4. 登录成功后从 wfw 进入教务网关 URL，观察网关下 jw 会话是否自动建立
// 输出全部跳转链 + 每步 cookie，供 Kotlin 侧固化协议。
//
// 用法: go run probe.go   （GDOU_USERNAME / GDOU_PASSWORD 来自环境变量）

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"
	"sync"

	"github.com/mxschmitt/playwright-go"
)

const (
	wfwHome  = "https://wfw.gdou.edu.cn/"
	jwHost   = "jw.gdou.edu.cn"
	authBase = "https://authserver.gdou.edu.cn/authserver"
)

var browserPriority = []string{
	`D:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`,
	`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`,
}

func resolveBrowser() (string, string) {
	if p := os.Getenv("BROWSER_PATH"); p != "" {
		if _, err := os.Stat(p); err == nil {
			return p, ""
		}
	}
	for _, p := range browserPriority {
		if _, err := os.Stat(p); err == nil {
			return p, ""
		}
	}
	return "", "msedge"
}

func logf(format string, a ...any) {
	fmt.Printf("[%s] %s\n", time.Now().Format("15:04:05.000"), fmt.Sprintf(format, a...))
}

func main() {
	username := strings.TrimSpace(os.Getenv("GDOU_USERNAME"))
	password := os.Getenv("GDOU_PASSWORD")
	if username == "" || password == "" {
		fatal("需要 GDOU_USERNAME / GDOU_PASSWORD 环境变量")
	}

	// 保证运行目录固定，dump 文件落在本目录
	_ = os.Chdir(mustWd())

	pw, err := playwright.Run()
	fatalIf(err, "无法启动 Playwright driver")
	defer pw.Stop()

	execPath, channel := resolveBrowser()
	opts := playwright.BrowserTypeLaunchOptions{
		Headless:          playwright.Bool(false),
		IgnoreDefaultArgs: []string{"--enable-automation"},
		Args: []string{
			"--disable-blink-features=AutomationControlled",
			"--no-first-run",
			"--no-default-browser-check",
			"--lang=zh-CN",
		},
	}
	if execPath != "" {
		logf("使用浏览器: %s", execPath)
		opts.ExecutablePath = &execPath
	} else {
		logf("使用 channel: %s", channel)
		opts.Channel = &channel
	}
	browser, err := pw.Chromium.Launch(opts)
	fatalIf(err, "启动浏览器失败")
	defer browser.Close()

	context, err := browser.NewContext(playwright.BrowserNewContextOptions{
		Locale:     playwright.String("zh-CN"),
		TimezoneId: playwright.String("Asia/Shanghai"),
		ExtraHttpHeaders: map[string]string{
			"Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
		},
	})
	fatalIf(err, "创建 context 失败")

	page, err := context.NewPage()
	fatalIf(err, "创建 page 失败")

	// 请求/响应全链记录（含重定向每一跳）；sso-broker/sso-token 打印请求体与响应
	page.On("request", func(r playwright.Request) {
		logf("REQ  %-4s %s", r.Method(), shortURL(r.URL()))
		if strings.Contains(r.URL(), "sso-broker") || strings.Contains(r.URL(), "sso-token") {
			if pd, err := r.PostData(); err == nil {
				logf("  BROKER-POST-DATA: %s", pd)
			}
		}
	})
	page.On("response", func(r playwright.Response) {
		if strings.Contains(r.URL(), "sso-broker") || strings.Contains(r.URL(), "sso-token") {
			if b, err := r.Text(); err == nil {
				n := len(b)
				if n > 400 {
					n = 400
				}
				logf("  BROKER-RESP: %s", b[:n])
			}
		}
		loc := r.Headers()["location"]
		if loc != "" {
			logf("RESP %-4d %s -> %s", r.Status(), shortURL(r.URL()), shortURL(loc))
		} else {
			logf("RESP %-4d %s", r.Status(), shortURL(r.URL()))
		}
	})

	// ---------- 第 1 步：打开 wfw 首页 ----------
	logf("===== STEP1 打开 wfw 首页 =====")
	if _, err := page.Goto(wfwHome, playwright.PageGotoOptions{WaitUntil: playwright.WaitUntilStateDomcontentloaded}); err != nil {
		logf("goto wfw err: %v", err)
	}
	waitStable(page)
	logf("落地 URL: %s", page.URL())
	dumpCookies(context, "step1")

	// ---------- 第 2 步：进入 CAS 登录页 ----------
	logf("===== STEP2 前往 CAS 登录页 (service=wfw) =====")
	loginURL := authBase + "/login?service=" + urlEncode("https://wfw.gdou.edu.cn/")
	if _, err := page.Goto(loginURL, playwright.PageGotoOptions{WaitUntil: playwright.WaitUntilStateDomcontentloaded}); err != nil {
		logf("goto cas err: %v", err)
	}
	waitStable(page)
	logf("CAS 页 URL: %s", page.URL())
	dumpForm(page)

	// ---------- 第 3 步：填写并提交登录表单 ----------
	logf("===== STEP3 提交登录 =====")
	if err := fillLogin(page, username, password); err != nil {
		fatal("填表失败: " + err.Error())
	}

	// 提交后等待回流（ticket → wfw）；若停在 MFA reauth 页，等人工完成（最多 120 秒）
	logf("等待登录回流 ...")
	deadline := time.Now().Add(120 * time.Second)
	for i := 0; i < 30 || time.Now().Before(deadline); i++ {
		time.Sleep(1 * time.Second)
		u := page.URL()
		if strings.HasPrefix(u, "https://wfw.gdou.edu.cn") && !strings.Contains(u, "authserver") {
			logf("  回流完成: %s", shortURL(u))
			break
		}
		if i < 30 {
			logf("  当前 URL: %s", shortURL(u))
		}
		if i == 29 {
			logf(">>> 停在 MFA/中间页，请在浏览器完成二次认证（企业微信确认或验证码）…")
		}
	}
	waitStable(page)
	logf("回流后 URL: %s", page.URL())
	dumpCookies(context, "step3-回流后")
	_ = dumpPage(page, "step3_landing")

	if cnt, _ := page.Locator("#msg, .auth_error, #showErrorTip").Count(); cnt > 0 {
		if txt, e := page.Locator("#msg, .auth_error, #showErrorTip").First().TextContent(); e == nil && strings.TrimSpace(txt) != "" {
			logf(">>> 页面错误提示: %s", strings.TrimSpace(txt))
		}
	}

	// ---------- 第 4 步：wfw 登录态下访问教务网关 ----------
	logf("===== STEP4 wfw 网关访问教务 =====")
	// 实证：仅 wfw 登录后直接访问网关 jw 会返回内嵌 CAS 登录页（service=jziotlogin）。
	// 先经 wfw 代理的 authserver 走一遍 jziotlogin SSO（CASTGC 免密 302 发 ticket）建立网关 jw 会话。
	logf("STEP4a jziotlogin SSO：直连 CAS 换 ticket → 网关消费")
	jziot := urlEncode("https://jw.gdou.edu.cn/sso/jziotlogin")
	// a1: 截获直连 CAS 的 302 Location（免密 ticket），不真去 jw（校外 refused）
	var ticketLoc string
	once := sync.Once{}
	page.Once("response", func(r playwright.Response) {
		if r.Status() == 302 {
			loc := r.Headers()["location"]
			if strings.Contains(loc, "ticket=") {
				once.Do(func() {
					ticketLoc = loc
					logf("截获 ticket 302: %s", shortURL(loc))
				})
			}
		}
	})
	if _, err := page.Goto("https://authserver.gdou.edu.cn/authserver/login?service="+jziot,
		playwright.PageGotoOptions{WaitUntil: playwright.WaitUntilStateCommit}); err != nil {
		logf("goto direct jziotlogin err(预期，ticket 后 refused): %v", err)
	}
	if ticketLoc == "" {
		waitStable(page)
		bodyErr := dumpPage(page, "step4a_direct")
		logf("未截获 ticket，落地: %s login=%v", shortURL(page.URL()), strings.Contains(bodyErr, "pwdEncryptSalt"))
	}
	// a2: 把 ticket URL 改写成 wfw 网关形态，在同一浏览器会话中 GET（网关代为消费 ticket）
	if ticketLoc != "" {
		gwTicket := strings.Replace(ticketLoc, "https://jw.gdou.edu.cn/", "https://wfw.gdou.edu.cn/https://jw.gdou.edu.cn/", 1)
		logf("网关消费 ticket: %s", shortURL(gwTicket))
		resp, err := page.Goto(gwTicket, playwright.PageGotoOptions{WaitUntil: playwright.WaitUntilStateDomcontentloaded})
		if err != nil {
			logf("goto 网关 ticket err: %v", err)
		} else {
			logf("网关 ticket 响应: %d %s", resp.Status(), shortURL(page.URL()))
		}
		waitStable(page)
		body4a := dumpPage(page, "step4a_gateway_ticket")
		logf("网关消费后: login=%v jw会话(sessionUserKey)=%v gnmkdm=%v",
			strings.Contains(body4a, "pwdEncryptSalt"), strings.Contains(body4a, "sessionUserKey"),
			strings.Contains(body4a, "gnmkdm"))
		dumpCookies(context, "step4a-gateway-ticket")
	}

	gwMenu := "https://wfw.gdou.edu.cn/https://" + jwHost + "/xtgl/index_initMenu.html?jsdm=xs"
	if _, err := page.Goto(gwMenu, playwright.PageGotoOptions{WaitUntil: playwright.WaitUntilStateDomcontentloaded}); err != nil {
		logf("goto 网关菜单 err: %v", err)
	}
	waitStable(page)
	logf("网关菜单 URL: %s", page.URL())
	body := dumpPage(page, "step4_gateway")
	if strings.Contains(body, "pwdEncryptSalt") {
		logf(">>> 网关菜单返回内嵌 CAS 登录页（service=jziotlogin），执行第二次 CAS 提交")
		if err := fillLogin(page, username, password); err != nil {
			logf("第二次填表失败: %v", err)
		} else if cnt, _ := page.Locator("#login_submit").Count(); cnt > 0 {
			_ = page.Locator("#login_submit").First().Click()
		}
		// 等 MFA（若再次触发）或回流
		deadline2 := time.Now().Add(120 * time.Second)
		for {
			time.Sleep(1 * time.Second)
			u := page.URL()
			b, _ := page.Content()
			hasSession := strings.Contains(b, "sessionUserKey") || strings.Contains(b, "gnmkdm")
			if hasSession || (strings.Contains(u, "xtgl/index_initMenu") && !strings.Contains(b, "pwdEncryptSalt")) {
				logf("第二次提交完成: %s 会话=%v", shortURL(u), hasSession)
				break
			}
			if time.Now().After(deadline2) {
				logf("第二次提交超时，停在: %s", shortURL(u))
				break
			}
		}
		body = dumpPage(page, "step4_gateway_after")
	}
	logf("网关页面含会话标记: sessionUserKey=%v gnmkdm=%v",
		strings.Contains(body, "sessionUserKey"), strings.Contains(body, "gnmkdm"))
	dumpCookies(context, "step4")

	// ---------- 第 5 步：网关下课表页（GET index） ----------
	logf("===== STEP5 网关下课表页 =====")
	kb := "https://wfw.gdou.edu.cn/https://" + jwHost + "/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
	if _, err := page.Goto(kb, playwright.PageGotoOptions{WaitUntil: playwright.WaitUntilStateDomcontentloaded}); err != nil {
		logf("goto 课表页 err: %v", err)
	}
	waitStable(page)
	logf("课表页 URL: %s", page.URL())
	_ = dumpPage(page, "step5_kb")
	dumpCookies(context, "step5")

	// 保存完整 storage state 供比对
	state, err := context.StorageState()
	fatalIf(err, "取 storage state 失败")
	data, _ := json.MarshalIndent(state, "", "  ")
	_ = os.WriteFile("state.json", data, 0o600)
	logf("已保存 state.json；脚本结束（浏览器关闭）")
}

// ---------- helpers ----------

func fillLogin(page playwright.Page, username, password string) error {
	// CAS 表单：#username / #password，验证码 #captchaResponse（若出现），提交 #login_submit
	selUser := "#username"
	selPass := "#password"
	if cnt, _ := page.Locator(selUser).Count(); cnt == 0 {
		return fmt.Errorf("找不到 %s（页面非登录表单），URL=%s", selUser, page.URL())
	}
	// 页面 JS 给所有 input 加了 readonly，focus 时才移除；Playwright Fill 对 readonly 静默失效，
	// 必须先 click 激活再填，填完校验 value 落地。
	_ = page.Locator(selUser).Click()
	_ = page.Locator(selUser).Fill(username)
	_ = page.Locator(selPass).Click()
	_ = page.Locator(selPass).Fill(password)
	gotUser, _ := page.Locator(selUser).InputValue()
	gotPass, _ := page.Locator(selPass).InputValue()
	if gotUser != username || gotPass == "" {
		return fmt.Errorf("填表未生效：username=%q passLen=%d（readonly 未解除？）", gotUser, len(gotPass))
	}
	logf("填表成功：username=%s passLen=%d", gotUser, len(gotPass))

	// 验证码检测
	if cnt, _ := page.Locator("#captchaImg, #captcha, img[src*='captcha']").Count(); cnt > 0 {
		logf(">>> CAS 需要验证码，请在浏览器手动输入后按回车继续提交")
		_ = dumpPage(page, "captcha")
		fmt.Scanln()
	}
	// 提交按钮
	for _, sel := range []string{"#idchecklogin", "button[type=submit]", "input[type=submit]", "#login_submit", "a.auth_login_btn"} {
		if cnt, _ := page.Locator(sel).Count(); cnt > 0 {
			logf("点击提交按钮: %s", sel)
			if err := page.Locator(sel).First().Click(); err != nil {
				return err
			}
			return nil
		}
	}
	// 兜底：表单内回车
	logf("未找到提交按钮，用回车提交")
	return page.Locator(selPass).Press("Enter")
}

func dumpForm(page playwright.Page) {
	for _, sel := range []string{"#username", "#password", "#captchaResponse", "#idchecklogin", "form"} {
		cnt, _ := page.Locator(sel).Count()
		logf("  form 检测 %-22s count=%d", sel, cnt)
	}
}

func waitStable(page playwright.Page) {
	// 等待网络空闲（最多 8 秒）
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if ok, _ := page.Evaluate("() => document.readyState === 'complete'"); ok == true {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	time.Sleep(500 * time.Millisecond)
}

func dumpPage(page playwright.Page, tag string) string {
	body, err := page.Content()
	if err != nil {
		logf("dumpPage err: %v", err)
		return ""
	}
	name := fmt.Sprintf("dump_%s.html", tag)
	if err := os.WriteFile(name, []byte(body), 0o600); err == nil {
		logf("页面已存 %s（%d 字节）", name, len(body))
	}
	return body
}

func dumpCookies(context playwright.BrowserContext, tag string) {
	cookies, err := context.Cookies()
	if err != nil {
		logf("cookies err: %v", err)
		return
	}
	logf("---- cookies @%s (%d) ----", tag, len(cookies))
	for _, c := range cookies {
		v := c.Value
		if len(v) > 14 {
			v = v[:8] + "..." + v[len(v)-4:]
		}
		logf("  %-16s domain=%-30s path=%-4s %s", c.Name, c.Domain, c.Path, v)
	}
}

func shortURL(u string) string {
	u = strings.ReplaceAll(u, "https://", "//")
	if len(u) > 110 {
		return u[:107] + "..."
	}
	return u
}

func urlEncode(s string) string {
	const hex = "0123456789ABCDEF"
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
			c == '-' || c == '_' || c == '.' || c == '~' {
			b.WriteByte(c)
		} else {
			b.WriteByte('%')
			b.WriteByte(hex[c>>4])
			b.WriteByte(hex[c&0xF])
		}
	}
	return b.String()
}

func mustWd() string {
	if wd, err := os.Getwd(); err == nil {
		return wd
	}
	if wd, err := exec.Command("cd").Output(); err == nil {
		return strings.TrimSpace(string(wd))
	}
	return "."
}

func fatalIf(err error, what string) {
	if err != nil {
		fatal(what + ": " + err.Error())
	}
}

func fatal(msg string) {
	fmt.Fprintln(os.Stderr, "FATAL:", msg)
	os.Exit(1)
}
