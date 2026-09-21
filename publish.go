// publish.go — 热帖(热铁盒) update.json 全自动发布工具
//
// 用法:
//
//	go run publish.go -config publish.config.json [-file shiguangschedule.json] [-key shiguangschedule.json]
//	                   [-cr-apk auto] [-gradle androidApp/build.gradle.kts] [-notes release notes]
//
// 流程:
//
//	启动时读回登录态缓存 publish.session.json(与配置同目录), 有缓存直接用
//	POST /backend/account-v2/session          登录(密码 RSA 加密) -> 拿 cookie, 回写缓存
//	GET  /backend/init?appName=host          带 cookie -> 拿 Authorization token + username, 回写缓存
//	GET  /host/site/content?domain=...       拿 hashes -> originalHash (海外站)
//	POST /host/site/changes                  提交新 update.json -> 校验 hashes
//	以上接口调用失败时自动重新登录(回写新缓存)并重试一次
//
// 配置 publish.config.json(不入库, 见 .gitignore):
//
//	{
//	  "email":   "vipoooo2024@163.com",
//	  "password": "你的明文密码",
//	  "domain":  "refree.rth1.xyz",
//	  "cloudreve": {
//	    "base":     "https://a8aa4e60c8d63f44b.gz5.agentos-app.net",
//	    "email":    "seewo@seewo.cn",
//	    "password": "fanshu",
//	    "dir":      ""
//	  }
//	}
//
// 密码也可用环境变量 RETIEHE_PASSWORD 提供(优先于配置文件)。
package main

import (
	"bytes"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	apiBase             = "https://api.retiehe.com/backend"
	apiOverseas         = "https://api-overseas.retiehe.com/backend"
	apiOverseasFallback = "https://api-eo.retiehe.com/backend"
)

// hostContentBases 站点内容只在海外站。主站 /host/site/* 返回 501。
var hostContentBases = []string{apiOverseas, apiOverseasFallback}

type httpStatusError struct {
	status int
	body   string
}

func (e *httpStatusError) Error() string {
	return fmt.Sprintf("HTTP %d: %s", e.status, trunc(e.body, 200))
}

func isAuthFailure(err error) bool {
	if err == nil {
		return false
	}
	var he *httpStatusError
	if errors.As(err, &he) {
		return he.status == 401 || he.status == 403
	}
	msg := err.Error()
	return strings.Contains(msg, "登录态失效") || strings.Contains(msg, "init 未返回 login")
}

func isMissingRoute(status int) bool {
	return status == 404 || status == 501
}

func isFileConflict(err error, body string) bool {
	if strings.Contains(body, "fileConflict") {
		return true
	}
	if err == nil {
		return false
	}
	return strings.Contains(err.Error(), "fileConflict")
}

// RSA public key from account.retiehe.com login JS (1024-bit)
const publicKeyPEM = `-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCxKxfpmroVBhtUjwoLK64188R7
Bsh+sCZTdH+zeu8OLR6tg/FHn7vIc0urqQ/lv8+gvmgbZ549BV80iOpXj/2vgpaj
vvkKJN6qgU0LLaoCEmZeJA79caY5Cym/qAS5VHRhzX4aHNinZtiwPUfaJwpW+4U2
h3xPsg5Wpd0CJDAtQwIDAQAB
-----END PUBLIC KEY-----`

type Config struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Domain   string `json:"domain"`
	// 亿方云网盘配置(可选，仅 -ysepan-apk 时需要)
	Ysepan *YsepanCfg `json:"ysepan"`
	// Cloudreve 网盘配置(可选，仅 -cr-apk 时需要)
	Cloudreve *CloudreveCfg `json:"cloudreve"`
}

// YsepanCfg 亿方云个人网盘上传配置
//
//	loginName: 网盘子域名，如 yku5pfb2u4zxkse
//	jwt:       旧 JWT(有效期内的)，用于 yzglmm 换新 token
//	glmm:      管理密码(登录页"管理区"的密码)
//	mlbh:      上传目标目录编号(0=自动取第一个目录)
type YsepanCfg struct {
	LoginName string `json:"loginName"`
	JWT       string `json:"jwt"`
	GLMM      string `json:"glmm"`
	MLBH      int    `json:"mlbh"`
}

// CloudreveCfg Cloudreve v4 网盘上传配置(仅 -cr-apk 时需要)。
//
//	base:     站点地址，如 https://xxx.agentos-app.net(不含 /api/v4)
//	email:    登录邮箱
//	password: 登录密码(也可用环境变量 CLOUDREVE_PASSWORD 覆盖)
//	dir:      上传目标目录(相对 my 根，可空；多级用 / 分隔)
type CloudreveCfg struct {
	Base     string `json:"base"`
	Email    string `json:"email"`
	Password string `json:"password"`
	Dir      string `json:"dir"`
}

// UpdateManifest 对应 update.json，字段声明顺序即 JSON 输出顺序。
type UpdateManifest struct {
	VersionCode  int64  `json:"versionCode"`
	VersionName  string `json:"versionName"`
	DownloadURL  string `json:"downloadUrl"`
	ReleaseNotes string `json:"releaseNotes"`
	FileSize     int64  `json:"fileSize"`
}

type loginResp struct {
	Token    string `json:"token"`
	Username string `json:"username"`
	Email    string `json:"email"`
	Name     any    `json:"name"`
	Phone    string `json:"phone"`
	Birthday any    `json:"birthday"`
}

type initResp struct {
	Login any `json:"login"`
}

type contentGetResp struct {
	Hashes map[string]string          `json:"hashes"`
	Result map[string]json.RawMessage `json:"result"`
}

type contentChange struct {
	Content      string `json:"content,omitempty"`
	Operation    string `json:"operation"`
	OriginalHash string `json:"originalHash,omitempty"`
	Path         string `json:"path"`
}

type contentChangesReq struct {
	Changes       []contentChange `json:"changes"`
	Domain        string          `json:"domain"`
	Username      string          `json:"username,omitempty"`
	WillOverwrite bool            `json:"willOverwrite,omitempty"`
}

type contentUpdateResp struct {
	ErrorLog any                        `json:"errorLog"`
	Modified map[string]string          `json:"modified"`
	Success  bool                       `json:"success"`
	Hash     string                     `json:"hash"`
	Raw      map[string]json.RawMessage `json:"-"`
}

// ---------- 登录态缓存: 登录后回写, 启动读回, 接口调用失败才重新登录 ----------

// sessionCache 持久化的登录态。
// cookies 保存登录(init)响应的 Set-Cookie 原始属性(Path/Expires 等),
// 因为部分 cookie(如 token)是窄 Path, 只能从响应头提取而非 jar 读取。
// 缓存文件与配置文件同目录: publish.session.json, 权限 0600, 已加入 .gitignore。
type sessionCache struct {
	Cookies   []*http.Cookie `json:"cookies"`
	AuthToken string         `json:"authToken"`
	Username  string         `json:"username"`
	SavedAt   time.Time      `json:"savedAt"`
}

func sessionPath(cfgPath string) string {
	return filepath.Join(filepath.Dir(cfgPath), "publish.session.json")
}

// sessionURLs 登录态相关请求地址, 灌回 cookie 时按这些 URL 逐个 SetCookies
var sessionURLs = []string{
	apiBase + "/account-v2/session",
	apiBase + "/init?appName=host",
}

// saveSession 把登录/init 响应的 cookies 与 host 会话 token 回写到缓存文件
func saveSession(path string, cookies []*http.Cookie, authToken, username string) {
	sc := sessionCache{Cookies: dedupeCookies(cookies), AuthToken: authToken, Username: username, SavedAt: time.Now()}
	b, _ := json.MarshalIndent(sc, "", "  ")
	if err := os.WriteFile(path, b, 0o600); err != nil {
		fmt.Println("    [警告] 回写登录态失败:", err)
		return
	}
	fmt.Println("    登录态已回写:", path)
}

// dedupeCookies 按名字去重, 保留后出现的(init 覆盖 login 同名 cookie)
func dedupeCookies(in []*http.Cookie) []*http.Cookie {
	seen := map[string]int{}
	var out []*http.Cookie
	for _, ck := range in {
		if ck == nil || ck.Name == "" {
			continue
		}
		if i, ok := seen[ck.Name]; ok {
			out[i] = ck
			continue
		}
		seen[ck.Name] = len(out)
		out = append(out, ck)
	}
	return out
}

// loadSession 启动时读回缓存登录态, 并把 cookies 灌回 cookie jar。
// 缓存不存在/损坏/字段缺失返回 nil。
func loadSession(path string, client *http.Client) *sessionCache {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var sc sessionCache
	if json.Unmarshal(data, &sc) != nil || sc.AuthToken == "" || sc.Username == "" || len(sc.Cookies) == 0 {
		return nil
	}
	for _, su := range sessionURLs {
		u, err := url.Parse(su)
		if err != nil {
			continue
		}
		cks := make([]*http.Cookie, 0, len(sc.Cookies))
		for _, ck := range sc.Cookies {
			if ck.Name != "" {
				cks = append(cks, &http.Cookie{Name: ck.Name, Value: ck.Value, Path: ck.Path, Domain: ck.Domain})
			}
		}
		client.Jar.SetCookies(u, cks)
	}
	return &sc
}

// rthSession 热帖登录态管理
type rthSession struct {
	client    *http.Client
	cfg       *Config
	cachePath string
	authToken string
	username  string
}

// loginFresh 完整登录: 密码登录拿 cookie -> init 换 host token -> 回写缓存
func (s *rthSession) loginFresh() error {
	lr, lcs, err := login(s.client, *s.cfg)
	if err != nil {
		return err
	}
	fmt.Printf("    登录成功: user=%s token=%s...\n", lr.Username, trunc(lr.Token, 8))
	authToken, username, ics, err := getHostSession(s.client)
	if err != nil {
		return err
	}
	if authToken == "" {
		fmt.Println("    警告: init 未返回 token, 将不带 Authorization 重试")
	} else {
		fmt.Printf("    authToken=%s... username=%s\n", trunc(authToken, 8), username)
	}
	s.authToken, s.username = authToken, username
	saveSession(s.cachePath, append(lcs, ics...), authToken, username)
	return nil
}

// renewFromCookies 用缓存 cookies 调 init 续期 host token, 成功则回写缓存(免密登录)
func (s *rthSession) renewFromCookies() error {
	authToken, username, _, err := getHostSession(s.client)
	if err != nil {
		return err
	}
	s.authToken, s.username = authToken, username
	fmt.Printf("    登录态续期成功(免密): user=%s token=%s...\n", username, trunc(authToken, 8))
	return nil
}

// withRetry 执行 fn。仅鉴权失败才重新登录并重试一次；
// 404 / 路由错误等非鉴权失败直接返回，避免把站点问题当成登录过期。
func (s *rthSession) withRetry(what string, fn func() error) error {
	err := fn()
	if err == nil {
		return nil
	}
	if !isAuthFailure(err) {
		return err
	}
	fmt.Printf("    %s auth failed (%v), re-login and retry...\n", what, err)
	if err2 := s.loginFresh(); err2 != nil {
		return fmt.Errorf("re-login failed: %w (first error: %v)", err2, err)
	}
	return fn()
}

func main() {
	cfgPath := flag.String("config", "publish.config.json", "配置文件路径")
	filePath := flag.String("file", "shiguangschedule.json", "local manifest path")
	key := flag.String("key", "shiguangschedule.json", "remote file key")
	uploadApk := flag.String("upload", "", "upload APK to the site first, then update the manifest")
	ysepanApk := flag.String("ysepan-apk", "", "upload APK to ysepan, then continue publishing the manifest")
	dryRun := flag.Bool("dry-run", false, "verify login/token/hash only, do not submit")
	crApk := flag.String("cr-apk", "", "upload APK to Cloudreve and generate the manifest from gradle (use auto to pick APK by versionName)")
	gradlePath := flag.String("gradle", "androidApp/build.gradle.kts", "build.gradle.kts path (versionCode/versionName)")
	notesFlag := flag.String("notes", "", "releaseNotes (empty keeps the previous manifest value)")
	flag.Parse()

	cfg, err := loadConfig(*cfgPath)
	if err != nil {
		fmt.Println("配置错误:", err)
		os.Exit(1)
	}
	if cfg.Domain == "" {
		fmt.Println("配置缺少 domain")
		os.Exit(1)
	}

	// 0. 可选: Cloudreve 上传 APK + 按 build.gradle.kts 版本号生成 update.json
	if *crApk != "" {
		if err := prepareViaCloudreve(cfg.Cloudreve, *crApk, *gradlePath, *filePath, *notesFlag); err != nil {
			fmt.Println("Cloudreve 发布准备失败:", err)
			os.Exit(1)
		}
	}

	// 0. 可选: 上传 APK 到亿方云网盘(旧版 APK 不管，直接传)
	if *ysepanApk != "" {
		if cfg.Ysepan == nil {
			fmt.Println("错误: 使用 -ysepan-apk 需要在配置文件中提供 ysepan 字段")
			os.Exit(1)
		}
		fmt.Println("==> 0/4 亿方云登录(管理密码换 token) ...")
		token, err := ysepanLogin(cfg.Ysepan)
		if err != nil {
			fmt.Println("亿方云登录失败:", err)
			os.Exit(1)
		}
		fmt.Printf("    登录成功 token=%s...\n", trunc(token, 12))
		fmt.Println("==> 0/4 上传 APK 到亿方云 ...")
		bh, err := ysepanUploadAPK(cfg.Ysepan, token, *ysepanApk)
		if err != nil {
			fmt.Println("上传 APK 失败:", err)
			os.Exit(1)
		}
		fmt.Printf("    APK 上传成功: 文件编号 bh=%d, 大小=%d 字节\n", bh, fileSize(*ysepanApk))
		// 生成下载直链(供 update.json downloadUrl 使用)
		if dl, err := ysepanGetFileURL(&http.Client{Timeout: 30 * time.Second}, cfg.Ysepan, token,
			cfg.Ysepan.MLBH, bh, filepath.Base(*ysepanApk)); err != nil {
			fmt.Println("    [警告] 生成下载链接失败:", err)
		} else {
			fmt.Printf("    下载链接: %s\n", dl)
		}
	}

	// 1. 登录态: 启动时读回缓存, 没有缓存才真正登录
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar, Timeout: 30 * time.Second}
	sess := &rthSession{client: client, cfg: cfg, cachePath: sessionPath(*cfgPath)}

	if sc := loadSession(sess.cachePath, client); sc != nil {
		fmt.Printf("==> 1/4 读回缓存登录态(%s), 尝试免密续期 ...\n", sc.SavedAt.Format("2006-01-02 15:04"))
		if err := sess.renewFromCookies(); err != nil {
			fmt.Println("    缓存登录态已失效, 重新登录 ...")
			if err := sess.loginFresh(); err != nil {
				fmt.Println("登录失败:", err)
				os.Exit(1)
			}
		}
	} else {
		fmt.Println("==> 1/4 登录 ...")
		if err := sess.loginFresh(); err != nil {
			fmt.Println("登录失败:", err)
			os.Exit(1)
		}
	}

	// 2.5 可选: 上传 APK 到站点(接口失败自动重新登录重试)
	if *uploadApk != "" {
		apkName := filepath.Base(*uploadApk)
		fmt.Printf("==> 2.5/4 上传 APK -> https://%s/%s ...\n", cfg.Domain, apkName)
		if err := sess.withRetry("上传 APK", func() error {
			return uploadFile(client, sess.authToken, sess.username, cfg.Domain, apkName, *uploadApk)
		}); err != nil {
			fmt.Println("上传 APK 失败:", err)
			os.Exit(1)
		}
		fmt.Printf("    APK 上传成功: https://%s/%s\n", cfg.Domain, apkName)
	}

	// 3. 读取远端当前 update.json 与 hash(接口失败自动重新登录重试)
	fmt.Println("==> 3/4 读取远端当前文件 ...")
	var oldHash string
	if err := sess.withRetry("读取远端文件", func() error {
		var e error
		oldHash, e = getFileHash(client, sess.authToken, sess.username, cfg.Domain, *key)
		return e
	}); err != nil {
		fmt.Println("读取远端文件失败:", err)
		os.Exit(1)
	}
	fmt.Printf("    originalHash=%s\n", trunc(oldHash, 16))

	if *dryRun {
		fmt.Println("==> dry-run 完成: 登录态/读hash 均正常, 未提交更新")
		return
	}

	// 4. 读取本地新内容并提交(接口失败自动重新登录重试)
	content, err := os.ReadFile(*filePath)
	if err != nil {
		fmt.Println("读取本地文件失败:", err)
		os.Exit(1)
	}
	fmt.Println("==> 4/4 提交更新 ...")
	var resp *contentUpdateResp
	if err := sess.withRetry("提交更新", func() error {
		var e error
		resp, e = updateContent(client, sess.authToken, sess.username, cfg.Domain, *key, oldHash, string(content))
		return e
	}); err != nil {
		fmt.Println("更新失败:", err)
		os.Exit(1)
	}
	if !resp.Success {
		fmt.Println("更新失败: success=false, 原始响应:")
		b, _ := json.MarshalIndent(resp, "", "  ")
		fmt.Println(string(b))
		os.Exit(1)
	}
	fmt.Printf("更新成功! 新 hash=%s\n", resp.Hash)
}

// ---------- 加密: RSA(MD5hex(密码) + 毫秒时间戳), PKCS#1 v1.5, base64 ----------
func encryptPassword(pwd string) (string, error) {
	block, _ := pem.Decode([]byte(publicKeyPEM))
	if block == nil {
		return "", fmt.Errorf("公钥解析失败")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return "", err
	}
	rsaPub, ok := pub.(*rsa.PublicKey)
	if !ok {
		return "", fmt.Errorf("非 RSA 公钥")
	}
	sum := md5.Sum([]byte(pwd))
	plain := hex.EncodeToString(sum[:]) + strconv.FormatInt(time.Now().UnixMilli(), 10)
	enc, err := rsa.EncryptPKCS1v15(rand.Reader, rsaPub, []byte(plain))
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(enc), nil
}

// ---------- 1. 登录 ----------
// 返回登录响应的 Set-Cookie(供登录态缓存回写)
func login(client *http.Client, cfg Config) (*loginResp, []*http.Cookie, error) {
	pwd := cfg.Password
	if env := os.Getenv("RETIEHE_PASSWORD"); env != "" {
		pwd = env
	}
	if pwd == "" {
		return nil, nil, fmt.Errorf("密码为空(配置 password 或环境变量 RETIEHE_PASSWORD)")
	}
	enc, err := encryptPassword(pwd)
	if err != nil {
		return nil, nil, err
	}
	body := map[string]string{
		"appName":   "account",
		"code":      "",
		"encrypted": enc,
		"referrer":  "https://account.retiehe.com/login",
		"user":      cfg.Email,
	}
	b, _ := json.Marshal(body)
	req, err := http.NewRequest("POST", apiBase+"/account-v2/session", bytes.NewReader(b))
	if err != nil {
		return nil, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Referer", "https://account.retiehe.com/")
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Origin", "https://account.retiehe.com")

	res, err := client.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	if res.StatusCode != 200 {
		return nil, nil, fmt.Errorf("HTTP %d: %s", res.StatusCode, string(data))
	}
	var r loginResp
	if err := json.Unmarshal(data, &r); err != nil {
		return nil, nil, fmt.Errorf("响应解析失败: %v", err)
	}
	if r.Token == "" {
		return nil, nil, fmt.Errorf("未返回 token: %s", string(data))
	}
	return &r, res.Cookies(), nil
}

// ---------- 2. init 获取 host 会话 ----------
// 返回 init 响应的 Set-Cookie(供登录态缓存回写)
func getHostSession(client *http.Client) (authToken, username string, cookies []*http.Cookie, err error) {
	u := apiBase + "/init?appName=host"
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
	req.Header.Set("Referer", "https://host.retiehe.com/")
	req.Header.Set("Origin", "https://host.retiehe.com")
	res, err := client.Do(req)
	if err != nil {
		return
	}
	cookies = res.Cookies()
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	if os.Getenv("RTH_DEBUG") != "" {
		fmt.Println("    [debug] init body:", string(data))
	}
	if res.StatusCode != 200 {
		err = fmt.Errorf("init HTTP %d: %s", res.StatusCode, string(data))
		return
	}
	var r initResp
	if err = json.Unmarshal(data, &r); err != nil {
		err = fmt.Errorf("init 解析失败: %v\n原始: %s", err, string(data))
		return
	}
	// login 可能是对象或 JSON 字符串
	switch v := r.Login.(type) {
	case map[string]any:
		authToken, _ = v["token"].(string)
		username, _ = v["username"].(string)
	case string:
		var m map[string]any
		if json.Unmarshal([]byte(v), &m) == nil {
			authToken, _ = m["token"].(string)
			username, _ = m["username"].(string)
		}
	case nil:
		// 未登录状态
		err = fmt.Errorf("init 未返回 login(登录态失效): %s", string(data))
		return
	default:
		err = fmt.Errorf("login 字段类型未知: %T, 原始: %s", r.Login, string(data))
		return
	}
	if username == "" {
		err = fmt.Errorf("init 未返回 username: %s", string(data))
	}
	return
}

// ---------- 2.5 上传文件(APK)到站点 ----------
func uploadFile(client *http.Client, authToken, username, domain, key, filePath string) error {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return err
	}
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	fw, err := w.CreateFormFile("content", filepath.Base(filePath))
	if err != nil {
		return err
	}
	if _, err := fw.Write(data); err != nil {
		return err
	}
	_ = w.WriteField("key", key)
	_ = w.WriteField("domain", domain)
	_ = w.WriteField("username", username)
	_ = w.Close()

	payload := buf.Bytes()
	contentType := w.FormDataContentType()
	var lastErr error
	for _, base := range hostContentBases {
		req, err := http.NewRequest("POST", base+"/host/site/binary", bytes.NewReader(payload))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", contentType)
		setCommonHeaders(req, authToken)
		res, err := client.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		body, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 200 {
			lastErr = &httpStatusError{status: res.StatusCode, body: string(body)}
			if isMissingRoute(res.StatusCode) {
				fmt.Printf("    skip %s (HTTP %d)\n", base, res.StatusCode)
				continue
			}
			return lastErr
		}
		var r struct {
			ErrorLog any               `json:"errorLog"`
			Hashes   map[string]string `json:"hashes"`
		}
		if err := json.Unmarshal(body, &r); err != nil {
			return fmt.Errorf("upload response parse failed: %v\nraw: %s", err, string(body))
		}
		if r.ErrorLog != nil && fmt.Sprint(r.ErrorLog) != "" {
			return fmt.Errorf("upload failed: %s", string(body))
		}
		return nil
	}
	if lastErr != nil {
		return lastErr
	}
	return fmt.Errorf("upload failed: no host content API available")
}

func getFileHash(client *http.Client, authToken, username, domain, key string) (string, error) {
	q := "?domain=" + url.QueryEscape(domain) + "&username=" + url.QueryEscape(username)
	var lastErr error
	for _, base := range hostContentBases {
		req, err := http.NewRequest("GET", base+"/host/site/content"+q, nil)
		if err != nil {
			return "", err
		}
		setCommonHeaders(req, authToken)
		res, err := client.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		data, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 200 {
			lastErr = &httpStatusError{status: res.StatusCode, body: string(data)}
			if isMissingRoute(res.StatusCode) {
				fmt.Printf("    skip %s (HTTP %d)\n", base, res.StatusCode)
				continue
			}
			return "", lastErr
		}
		var r contentGetResp
		if err := json.Unmarshal(data, &r); err != nil {
			return "", fmt.Errorf("GET content parse failed: %v\nraw: %s", err, string(data))
		}
		h, ok := r.Hashes[key]
		if !ok {
			fmt.Printf("    remote file %q not found, will create it\n", key)
			return "", nil
		}
		return h, nil
	}
	if lastErr != nil {
		return "", lastErr
	}
	return "", fmt.Errorf("GET content failed: no host content API available")
}

// ---------- 4. 提交更新 ----------
func updateContent(client *http.Client, authToken, username, domain, key, oldHash, content string) (*contentUpdateResp, error) {
	reqBody := contentChangesReq{
		Changes: []contentChange{{
			Content:      content,
			Operation:    "write",
			OriginalHash: oldHash,
			Path:         key,
		}},
		Domain:   domain,
		Username: username,
	}
	return postSiteChanges(client, authToken, key, reqBody)
}

func postSiteChanges(client *http.Client, authToken, key string, reqBody contentChangesReq) (*contentUpdateResp, error) {
	b, _ := json.Marshal(reqBody)
	var lastErr error
	for _, base := range hostContentBases {
		req, err := http.NewRequest("POST", base+"/host/site/changes", bytes.NewReader(b))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		setCommonHeaders(req, authToken)
		res, err := client.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		data, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 200 {
			lastErr = &httpStatusError{status: res.StatusCode, body: string(data)}
			if isMissingRoute(res.StatusCode) {
				fmt.Printf("    skip %s (HTTP %d)\n", base, res.StatusCode)
				continue
			}
			if !reqBody.WillOverwrite && isFileConflict(lastErr, string(data)) {
				fmt.Println("    fileConflict, retry with willOverwrite")
				retry := reqBody
				retry.WillOverwrite = true
				return postSiteChanges(client, authToken, key, retry)
			}
			return nil, lastErr
		}
		var raw struct {
			ErrorLog any               `json:"errorLog"`
			Modified map[string]string `json:"modified"`
			Hashes   map[string]string `json:"hashes"`
			Success  bool              `json:"success"`
			Hash     string            `json:"hash"`
		}
		if err := json.Unmarshal(data, &raw); err != nil {
			return nil, fmt.Errorf("POST changes parse failed: %v\nraw: %s", err, string(data))
		}
		if raw.ErrorLog != nil && fmt.Sprint(raw.ErrorLog) != "" {
			return nil, fmt.Errorf("POST changes failed: %s", string(data))
		}
		hash := raw.Hash
		if hash == "" && raw.Hashes != nil {
			hash = raw.Hashes[key]
		}
		return &contentUpdateResp{
			ErrorLog: raw.ErrorLog,
			Modified: raw.Modified,
			Success:  true,
			Hash:     hash,
		}, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("POST changes failed: no host content API available")
}

func setCommonHeaders(req *http.Request, authToken string) {
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9,en-GB;q=0.8,en;q=0.7,en-US;q=0.6")
	req.Header.Set("Referer", "https://host.retiehe.com/")
	req.Header.Set("Origin", "https://host.retiehe.com")
	req.Header.Set("Cache-Control", "no-cache")
	req.Header.Set("Pragma", "no-cache")
	if authToken != "" {
		req.Header.Set("Authorization", "Bearer "+authToken)
	}
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取 %s: %v", path, err)
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if cfg.Email == "" {
		return nil, fmt.Errorf("缺少 email")
	}
	return &cfg, nil
}

// ysepanFileURL 构造文件下载直链
// 格式(逆向自前端 xzurl()): https://ys-<fwq>.ysepan.com/wap/<登录名>/<目录xzpz>/<文件pz>/<原始文件名>
func ysepanFileURL(loginName string, dirXzpz, filePz, fwq, wjm string) string {
	if dirXzpz == "" || filePz == "" || fwq == "" {
		return ""
	}
	host := "ys-" + strings.ToLower(fwq) + ".ysepan.com"
	return fmt.Sprintf("https://%s/wap/%s/%s/%s/%s", host, loginName, dirXzpz, filePz, wjm)
}

// ysepanGetFileURL 上传后查询文件 pz + 目录 xzpz，拼下载直链
func ysepanGetFileURL(c *http.Client, cfg *YsepanCfg, token string, mlbh, wjbh int, wjm string) (string, error) {
	hdrs := ysepanHeaders(cfg.LoginName, token)
	b, _ := json.Marshal(map[string]any{"mlbh": mlbh, "kqmm": "", "wjbh": 0, "ip1": ""})
	data, err := ysepanPost(c, "https://c6.ysepan.com/api/wj/wjdq", hdrs, b)
	if err != nil {
		return "", err
	}
	var r struct {
		ML struct {
			XZPZ string `json:"xzpz"`
		} `json:"ml"`
		LB []struct {
			BH  int    `json:"bh"`
			PZ  string `json:"pz"`
			FWQ string `json:"fwq"`
			WJM string `json:"wjm"`
		} `json:"lb"`
	}
	if err := json.Unmarshal(data, &r); err != nil {
		return "", fmt.Errorf("wjdq 解析失败: %v", err)
	}
	for _, f := range r.LB {
		if f.BH == wjbh {
			return ysepanFileURL(cfg.LoginName, r.ML.XZPZ, f.PZ, f.FWQ, wjm), nil
		}
	}
	return "", fmt.Errorf("文件 %d 未在目录 %d 列表中找到", wjbh, mlbh)
}

func trunc(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

func fileSize(path string) int64 {
	fi, err := os.Stat(path)
	if err != nil {
		return -1
	}
	return fi.Size()
}

// ============================================================
// Cloudreve v4 网盘: 密码登录 + 分片上传 + 永久直链
// 站点 cfg.Cloudreve.Base，API 前缀 /api/v4
// ============================================================

// crResp Cloudreve 统一响应信封 {code,data,msg}。code=0 为成功。
type crResp struct {
	Code int             `json:"code"`
	Msg  string          `json:"msg"`
	Data json.RawMessage `json:"data"`
}

// crLockInfo 锁冲突(code=40073)时 data 数组元素。
type crLockInfo struct {
	Path  string `json:"path"`
	Token string `json:"token"`
}

// crSessionData 创建上传会话返回的 data。
type crSessionData struct {
	SessionID     string   `json:"session_id"`
	ChunkSize     int64    `json:"chunk_size"`
	UploadURLs    []string `json:"upload_urls"`
	Credential    string   `json:"credential"`
	StoragePolicy struct {
		Type  string `json:"type"`
		Relay bool   `json:"relay"`
	} `json:"storage_policy"`
}

// crSourceLink 创建直链返回的 data 数组元素。
type crSourceLink struct {
	Link    string `json:"link"`
	FileURL string `json:"file_url"`
}

// cloudreveDo 发送 JSON 请求并解析统一响应信封；token 为空则不带 Authorization。
// 仅在网络/解析失败时返回 error，业务错误码(如 40073)交由调用方判断。
func cloudreveDo(c *http.Client, method, rawURL, token string, body any) (*crResp, error) {
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, rawURL, rdr)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	var r crResp
	if err := json.Unmarshal(data, &r); err != nil {
		return nil, fmt.Errorf("响应解析失败(HTTP %d): %v\n原始: %s", res.StatusCode, err, trunc(string(data), 512))
	}
	return &r, nil
}

// cloudreveLogin 密码登录，返回 access_token。
func cloudreveLogin(c *http.Client, base, email, pwd string) (string, error) {
	r, err := cloudreveDo(c, "POST", base+"/session/token", "", map[string]string{"email": email, "password": pwd})
	if err != nil {
		return "", err
	}
	if r.Code != 0 {
		return "", fmt.Errorf("登录被拒: code=%d msg=%s", r.Code, r.Msg)
	}
	var d struct {
		Token struct {
			AccessToken string `json:"access_token"`
		} `json:"token"`
	}
	if err := json.Unmarshal(r.Data, &d); err != nil {
		return "", fmt.Errorf("登录响应解析失败: %v", err)
	}
	if d.Token.AccessToken == "" {
		return "", fmt.Errorf("登录未返回 access_token")
	}
	return d.Token.AccessToken, nil
}

// cloudreveCreateSession 创建上传会话；遇锁冲突(40073)自动强制解锁后重试一次。
func cloudreveCreateSession(c *http.Client, base, token, uri string, size int64, modTime time.Time) (*crSessionData, error) {
	reqBody := map[string]any{
		"uri":           uri,
		"size":          size,
		"last_modified": modTime.UnixMilli(),
		"mime_type":     "application/vnd.android.package-archive",
		"entity_type":   "version", // 同名文件覆盖，便于重复发布同一版本
	}
	for attempt := 0; attempt < 2; attempt++ {
		r, err := cloudreveDo(c, "PUT", base+"/file/upload", token, reqBody)
		if err != nil {
			return nil, err
		}
		if r.Code == 40073 { // 锁冲突: 提取锁令牌强制解锁后重试
			var locks []crLockInfo
			if json.Unmarshal(r.Data, &locks) != nil || len(locks) == 0 {
				return nil, fmt.Errorf("锁冲突但无法解析锁令牌: %s", r.Msg)
			}
			tokens := make([]string, 0, len(locks))
			for _, l := range locks {
				if l.Token != "" {
					tokens = append(tokens, l.Token)
				}
			}
			if len(tokens) == 0 {
				return nil, fmt.Errorf("锁冲突但无可用锁令牌: %s", r.Msg)
			}
			fmt.Printf("    检测到锁冲突(%d 个)，强制解锁后重试...\n", len(tokens))
			ur, err := cloudreveDo(c, "DELETE", base+"/file/lock", token, map[string]any{"tokens": tokens})
			if err != nil {
				return nil, fmt.Errorf("强制解锁失败: %w", err)
			}
			if ur.Code != 0 {
				return nil, fmt.Errorf("强制解锁被拒: code=%d msg=%s", ur.Code, ur.Msg)
			}
			continue
		}
		if r.Code != 0 {
			return nil, fmt.Errorf("创建上传会话失败: code=%d msg=%s", r.Code, r.Msg)
		}
		var d crSessionData
		if err := json.Unmarshal(r.Data, &d); err != nil {
			return nil, fmt.Errorf("会话响应解析失败: %v", err)
		}
		if d.SessionID == "" {
			return nil, fmt.Errorf("会话未返回 session_id")
		}
		return &d, nil
	}
	return nil, fmt.Errorf("创建上传会话失败: 解锁重试后仍锁冲突")
}

// cloudreveUploadChunks 按 chunkSize 顺序分片上传(local/relay 存储直传 Cloudreve)。
func cloudreveUploadChunks(c *http.Client, base, token, sessionID, apkPath string, size, chunkSize int64) error {
	f, err := os.Open(apkPath)
	if err != nil {
		return err
	}
	defer f.Close()
	buf := make([]byte, chunkSize)
	var idx int
	var sent int64
	for sent < size {
		n := chunkSize
		if size-sent < n {
			n = size - sent
		}
		if _, err := io.ReadFull(f, buf[:n]); err != nil {
			return fmt.Errorf("读取分片 %d 失败: %w", idx, err)
		}
		u := fmt.Sprintf("%s/file/upload/%s/%d", base, sessionID, idx)
		req, err := http.NewRequest("POST", u, bytes.NewReader(buf[:n]))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/octet-stream")
		req.Header.Set("Authorization", "Bearer "+token)
		req.ContentLength = n
		res, err := c.Do(req)
		if err != nil {
			return fmt.Errorf("上传分片 %d 失败: %w", idx, err)
		}
		data, _ := io.ReadAll(res.Body)
		res.Body.Close()
		var r crResp
		if json.Unmarshal(data, &r) != nil || r.Code != 0 {
			return fmt.Errorf("上传分片 %d 被拒(HTTP %d): %s", idx, res.StatusCode, trunc(string(data), 512))
		}
		sent += n
		idx++
		fmt.Printf("    分片 %d 上传完成 (%d/%d 字节)\n", idx, sent, size)
	}
	return nil
}

// cloudreveDirectLink 为文件创建永久直链(匿名可下载)。
func cloudreveDirectLink(c *http.Client, base, token, uri string) (string, error) {
	r, err := cloudreveDo(c, "PUT", base+"/file/source", token, map[string]any{"uris": []string{uri}})
	if err != nil {
		return "", err
	}
	if r.Code != 0 {
		return "", fmt.Errorf("创建直链失败: code=%d msg=%s", r.Code, r.Msg)
	}
	var links []crSourceLink
	if err := json.Unmarshal(r.Data, &links); err != nil {
		return "", fmt.Errorf("直链响应解析失败: %v", err)
	}
	if len(links) == 0 || links[0].Link == "" {
		return "", fmt.Errorf("未返回直链")
	}
	return links[0].Link, nil
}

// cloudreveURI 拼装文件 URI: cloudreve://my/[dir/]<name>，各段做路径转义。
func cloudreveURI(dir, name string) string {
	segs := []string{"my"}
	if dir = strings.Trim(dir, "/"); dir != "" {
		for _, s := range strings.Split(dir, "/") {
			if s = strings.TrimSpace(s); s != "" {
				segs = append(segs, url.PathEscape(s))
			}
		}
	}
	segs = append(segs, url.PathEscape(name))
	return "cloudreve://" + strings.Join(segs, "/")
}

// cloudreveUpload 登录 -> 创建会话(自动解锁) -> 分片上传 -> 创建直链，返回下载直链。
func cloudreveUpload(cfg *CloudreveCfg, apkPath string) (string, error) {
	if cfg.Base == "" {
		return "", fmt.Errorf("配置缺少 cloudreve.base")
	}
	if cfg.Email == "" {
		return "", fmt.Errorf("配置缺少 cloudreve.email")
	}
	pwd := cfg.Password
	if env := os.Getenv("CLOUDREVE_PASSWORD"); env != "" {
		pwd = env
	}
	if pwd == "" {
		return "", fmt.Errorf("配置缺少 cloudreve.password(或环境变量 CLOUDREVE_PASSWORD)")
	}
	fi, err := os.Stat(apkPath)
	if err != nil {
		return "", err
	}
	size := fi.Size()
	if size == 0 {
		return "", fmt.Errorf("APK 为空: %s", apkPath)
	}
	name := filepath.Base(apkPath)
	uri := cloudreveURI(cfg.Dir, name)
	base := strings.TrimRight(cfg.Base, "/") + "/api/v4"
	c := &http.Client{Timeout: 10 * time.Minute}

	token, err := cloudreveLogin(c, base, cfg.Email, pwd)
	if err != nil {
		return "", err
	}
	fmt.Printf("    Cloudreve 登录成功: %s\n", cfg.Email)

	sess, err := cloudreveCreateSession(c, base, token, uri, size, fi.ModTime())
	if err != nil {
		return "", err
	}
	if sess.StoragePolicy.Type != "local" && !sess.StoragePolicy.Relay {
		return "", fmt.Errorf("暂不支持的存储策略类型 %q(仅支持 local/relay)", sess.StoragePolicy.Type)
	}
	chunkSize := sess.ChunkSize
	if chunkSize <= 0 || chunkSize > size {
		chunkSize = size // 0 表示禁用分片；单片即可容纳则整体一次传
	}
	fmt.Printf("    上传会话: sid=%s chunk=%d type=%s uri=%s\n", trunc(sess.SessionID, 8), chunkSize, sess.StoragePolicy.Type, uri)

	if err := cloudreveUploadChunks(c, base, token, sess.SessionID, apkPath, size, chunkSize); err != nil {
		return "", err
	}
	return cloudreveDirectLink(c, base, token, uri)
}

// ---------- build.gradle.kts 版本号解析 ----------

var (
	reVersionCode = regexp.MustCompile(`versionCode\s*=\s*(\d+)`)
	reVersionName = regexp.MustCompile(`versionName\s*=\s*"([^"]+)"`)
)

// parseGradleVersion 从 build.gradle.kts 解析 versionCode / versionName。
func parseGradleVersion(path string) (int64, string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, "", err
	}
	mc := reVersionCode.FindSubmatch(data)
	if mc == nil {
		return 0, "", fmt.Errorf("未在 %s 找到 versionCode", path)
	}
	mn := reVersionName.FindSubmatch(data)
	if mn == nil {
		return 0, "", fmt.Errorf("未在 %s 找到 versionName", path)
	}
	code, err := strconv.ParseInt(string(mc[1]), 10, 64)
	if err != nil {
		return 0, "", fmt.Errorf("versionCode 解析失败: %w", err)
	}
	return code, string(mn[1]), nil
}

// ---------- update.json 读写 ----------

// readReleaseNotes 读取现有 update.json 的 releaseNotes(不存在或损坏则返回空)。
func readReleaseNotes(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	var m UpdateManifest
	if json.Unmarshal(data, &m) != nil {
		return ""
	}
	return m.ReleaseNotes
}

// writeUpdateJSON 以两空格缩进写出 update.json。
func writeUpdateJSON(path string, m UpdateManifest) error {
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	return os.WriteFile(path, b, 0o644)
}

// prepareViaCloudreve 读取版本号 -> 上传 APK 到 Cloudreve -> 生成 update.json。
func prepareViaCloudreve(cr *CloudreveCfg, apkPath, gradlePath, updatePath, notes string) error {
	if cr == nil {
		return fmt.Errorf("使用 -cr-apk 需要在配置文件中提供 cloudreve 字段")
	}
	versionCode, versionName, err := parseGradleVersion(gradlePath)
	if err != nil {
		return err
	}
	if apkPath == "auto" {
		apkPath = fmt.Sprintf("shiguangschedule-v%s-arm64-v8a-release.apk", versionName)
	}
	if _, err := os.Stat(apkPath); err != nil {
		return fmt.Errorf("APK 不存在: %w", err)
	}
	fmt.Printf("==> 0.1 版本号 versionCode=%d versionName=%s (来自 %s)\n", versionCode, versionName, gradlePath)

	fmt.Printf("==> 0.2 上传 APK 到 Cloudreve: %s (%d 字节) ...\n", apkPath, fileSize(apkPath))
	link, err := cloudreveUpload(cr, apkPath)
	if err != nil {
		return err
	}
	fmt.Printf("    下载直链: %s\n", link)

	if notes == "" {
		notes = readReleaseNotes(updatePath)
	}
	m := UpdateManifest{
		VersionCode:  versionCode,
		VersionName:  versionName,
		DownloadURL:  link,
		ReleaseNotes: notes,
		FileSize:     fileSize(apkPath),
	}
	if err := writeUpdateJSON(updatePath, m); err != nil {
		return err
	}
	fmt.Printf("==> 0.3 已生成 %s: versionName=%s fileSize=%d\n", updatePath, m.VersionName, m.FileSize)
	return nil
}

// ============================================================
// 亿方云 ysepan 网盘: 管理密码登录 + 分片上传
// ============================================================

const ysepanChunkSize = 2 * 1024 * 1024 // 分片大小 2MB(与前端 JS 一致)

// ysepanHeaders 拼装亿方云 API 通用请求头
func ysepanHeaders(loginName, token string) map[string]string {
	site := "https://" + loginName + ".ysepan.com"
	return map[string]string{
		"authorization":   "Bearer " + token,
		"content-type":    "application/json",
		"referer":         site + "/",
		"origin":          site,
		"accept":          "application/json, text/plain, */*",
		"accept-language": "zh-CN,zh;q=0.9",
		"user-agent":      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
	}
}

// ysepanPost JSON POST 到亿方云 API，返回响应体
func ysepanPost(c *http.Client, rawURL string, headers map[string]string, body []byte) ([]byte, error) {
	req, err := http.NewRequest("POST", rawURL, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d: %s", res.StatusCode, string(data))
	}
	return data, nil
}

// ysepanLogin 管理密码换 token
// POST https://c6.ysepan.com/api/csxx/yzglmm
// body: {"glmm":"<管理密码>","mlbh":0}  →  {"token":"<新JWT>", ...}
func ysepanLogin(cfg *YsepanCfg) (string, error) {
	if cfg.GLMM == "" {
		return "", fmt.Errorf("配置缺少 ysepan.glmm(管理密码)")
	}
	if cfg.JWT == "" {
		return "", fmt.Errorf("配置缺少 ysepan.jwt(旧 JWT)")
	}
	if cfg.LoginName == "" {
		return "", fmt.Errorf("配置缺少 ysepan.loginName")
	}
	body, _ := json.Marshal(map[string]any{"glmm": cfg.GLMM, "mlbh": 0})
	hdrs := ysepanHeaders(cfg.LoginName, cfg.JWT)
	data, err := ysepanPost(&http.Client{Timeout: 30 * time.Second},
		"https://c6.ysepan.com/api/csxx/yzglmm", hdrs, body)
	if err != nil {
		return "", err
	}
	var r struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(data, &r); err != nil {
		return "", fmt.Errorf("yzglmm 响应解析失败: %v\n原始: %s", err, string(data))
	}
	if r.Token == "" {
		return "", fmt.Errorf("yzglmm 未返回 token: %s", string(data))
	}
	return r.Token, nil
}

// ysepanUploadAPK 分片上传 APK 到指定目录，返回文件编号 bh
// 流程: ml/hqpz 拿 scdz+scpz → 2MB 分片 POST scdz(multipart) → upkjdx/zx 上报空间
func ysepanUploadAPK(cfg *YsepanCfg, token, apkPath string) (int, error) {
	data, err := os.ReadFile(apkPath)
	if err != nil {
		return 0, err
	}
	if len(data) == 0 {
		return 0, fmt.Errorf("文件为空: %s", apkPath)
	}
	name := filepath.Base(apkPath)
	c := &http.Client{Timeout: 5 * time.Minute}
	hdrs := ysepanHeaders(cfg.LoginName, token)

	// 目标目录(mlbh=0 时自动取第一个)
	mlbh := cfg.MLBH
	if mlbh <= 0 {
		mlbh, err = ysepanFirstDir(c, hdrs)
		if err != nil {
			return 0, fmt.Errorf("自动查找目录失败: %w", err)
		}
	}

	// 1. 拿上传配置
	b, _ := json.Marshal(map[string]any{"mlbh": mlbh, "kqmm": "", "lx": "sc", "ymzd": 6, "fmax": 30})
	hq, err := ysepanPost(c, "https://c6.ysepan.com/api/ml/hqpz", hdrs, b)
	if err != nil {
		return 0, fmt.Errorf("hqpz: %w", err)
	}
	var hqResp struct {
		ML struct {
			SCPZ string `json:"scpz"`
		} `json:"ml"`
		KJ struct {
			SCDZ string `json:"scdz"`
		} `json:"kj"`
	}
	if err := json.Unmarshal(hq, &hqResp); err != nil {
		return 0, fmt.Errorf("hqpz 解析失败: %v\n原始: %s", err, string(hq))
	}
	if hqResp.KJ.SCDZ == "" {
		return 0, fmt.Errorf("hqpz 未返回上传地址: %s", string(hq))
	}

	// 2. 算 jsq 序号(空目录 1000000，否则 max(jsq)+100)
	jsq := 1000000
	if files, err := ysepanListFiles(c, hdrs, mlbh); err == nil && len(files) > 0 {
		max := 0
		for _, f := range files {
			if f.JSQ > max {
				max = f.JSQ
			}
		}
		if max > 0 {
			jsq = max + 100
		}
	}

	// 3. 分片上传
	total := (len(data) + ysepanChunkSize - 1) / ysepanChunkSize
	gid := ""
	bh := 0
	for n := 0; n < total; n++ {
		start := n * ysepanChunkSize
		end := start + ysepanChunkSize
		if end > len(data) {
			end = len(data)
		}
		var buf bytes.Buffer
		w := multipart.NewWriter(&buf)
		if n == 0 {
			_ = w.WriteField("dlmc", cfg.LoginName)
			_ = w.WriteField("scpz", hqResp.ML.SCPZ)
			_ = w.WriteField("zmlmc", "")
			_ = w.WriteField("jsq", strconv.Itoa(jsq))
			_ = w.WriteField("pdgk", "1")
			_ = w.WriteField("bz", "")
			_ = w.WriteField("fsize", strconv.Itoa(len(data)))
			_ = w.WriteField("isbm", "1")
			_ = w.WriteField("gid", "")
		} else {
			_ = w.WriteField("gid", gid)
		}
		// 注意: filename 必须 Base64(JS: btoa(unescape(encodeURIComponent(name))))
		encName := base64.StdEncoding.EncodeToString([]byte(name))
		fw, err := w.CreateFormFile("file", encName)
		if err != nil {
			return 0, err
		}
		if _, err := fw.Write(data[start:end]); err != nil {
			return 0, err
		}
		_ = w.WriteField("chunkIndex", strconv.Itoa(n))
		_ = w.WriteField("totalfps", strconv.Itoa(total))
		_ = w.Close()

		req, err := http.NewRequest("POST", hqResp.KJ.SCDZ, &buf)
		if err != nil {
			return 0, err
		}
		req.Header.Set("content-type", w.FormDataContentType())
		req.Header.Set("referer", "https://"+cfg.LoginName+".ysepan.com/")
		req.Header.Set("origin", "https://"+cfg.LoginName+".ysepan.com")
		req.Header.Set("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
		res, err := c.Do(req)
		if err != nil {
			return 0, fmt.Errorf("分片 %d 上传: %w", n, err)
		}
		rb, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if res.StatusCode != 200 {
			return 0, fmt.Errorf("分片 %d -> HTTP %d: %s", n, res.StatusCode, string(rb))
		}
		var cr struct {
			BH  int    `json:"bh"`
			GID string `json:"gid"`
		}
		if err := json.Unmarshal(rb, &cr); err != nil {
			return 0, fmt.Errorf("分片 %d 响应解析失败: %s", n, string(rb))
		}
		if n == 0 && cr.GID != "" {
			gid = cr.GID
		}
		if n == total-1 && cr.BH > 0 {
			bh = cr.BH
		}
		fmt.Printf("    分片 %d/%d 完成 (gid=%s)\n", n+1, total, trunc(gid, 8))
	}
	if bh == 0 {
		return 0, fmt.Errorf("上传完成但未获得文件编号 bh")
	}

	// 4. 上报空间(注意: 走站点域名, 不是 c6)
	kjbh := 6310798
	if dirs, err := ysepanListDirs(c, hdrs); err == nil {
		for _, d := range dirs {
			if d.BH == mlbh {
				kjbh = d.KHBH
				break
			}
		}
	}
	upBody, _ := json.Marshal(map[string]any{"dx": len(data), "qlgx": "0", "dlmc": cfg.LoginName, "kjbh": kjbh})
	upHdrs := ysepanHeaders(cfg.LoginName, token)
	upURL := "https://" + cfg.LoginName + ".ysepan.com/api/upkjdx/zx"
	if _, err := ysepanPost(c, upURL, upHdrs, upBody); err != nil {
		return 0, fmt.Errorf("upkjdx/zx: %w", err)
	}
	return bh, nil
}

type ysepanDir struct {
	BH   int    `json:"bh"`
	BT   string `json:"bt"`
	KHBH int    `json:"khbh"`
}

type ysepanFile struct {
	BH  int    `json:"bh"`
	WJM string `json:"wjm"`
	JSQ int    `json:"jsq"`
}

// ysepanListDirs 目录列表(POST ml/mldq)
func ysepanListDirs(c *http.Client, hdrs map[string]string) ([]ysepanDir, error) {
	data, err := ysepanPost(c, "https://c6.ysepan.com/api/ml/mldq", hdrs, []byte("{}"))
	if err != nil {
		return nil, err
	}
	var r struct {
		LB []ysepanDir `json:"lb"`
	}
	if err := json.Unmarshal(data, &r); err != nil {
		return nil, err
	}
	return r.LB, nil
}

// ysepanFirstDir 取第一个目录编号
func ysepanFirstDir(c *http.Client, hdrs map[string]string) (int, error) {
	dirs, err := ysepanListDirs(c, hdrs)
	if err != nil {
		return 0, err
	}
	if len(dirs) == 0 {
		return 0, fmt.Errorf("网盘无目录")
	}
	return dirs[0].BH, nil
}

// ysepanListFiles 文件列表(POST wj/wjdq)
func ysepanListFiles(c *http.Client, hdrs map[string]string, mlbh int) ([]ysepanFile, error) {
	b, _ := json.Marshal(map[string]any{"mlbh": mlbh, "kqmm": "", "wjbh": 0, "ip1": ""})
	data, err := ysepanPost(c, "https://c6.ysepan.com/api/wj/wjdq", hdrs, b)
	if err != nil {
		return nil, err
	}
	var r struct {
		LB []ysepanFile `json:"lb"`
	}
	if err := json.Unmarshal(data, &r); err != nil {
		return nil, err
	}
	return r.LB, nil
}
