@echo off
setlocal

rem =====================================================
rem  shiguangschedule one-click build / release
rem  Usage: build.bat [debug|release] [nopause]
rem    no args              -> prompt to choose debug / release
rem    build.bat debug      -> build debug, copy APK (no version bump, no publish)
rem    build.bat release    -> bump version, build release, copy APK, publish
rem    nopause              -> skip the pause at the end (for CI/automation)
rem  Release flow:
rem    1) versionCode +1 / versionName patch +1 in androidApp\build.gradle.kts
rem    2) build release APK (log: build.log)
rem    3) if build fails -> roll the version bump back
rem    4) if build succeeds -> copy APK to project root, then publish
rem       (publish.exe: upload APK to Cloudreve, update shiguangschedule.json)
rem  Output: shiguangschedule-v{version}-{abi}-{type}.apk in project root
rem          (abi = arm64-v8a)
rem  Env overrides: JAVA_EXE / JAVA_HOME, BUILD_PROXY (host:port or "direct")
rem =====================================================

set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"

rem optional arg "nopause" skips the pause at the end (any position)
set "NOPAUSE="
set "VARIANT="
if /i "%~1"=="nopause" (
    set "NOPAUSE=1"
) else (
    set "VARIANT=%~1"
)
if /i "%~2"=="nopause" set "NOPAUSE=1"

rem ---------- 1. variant ----------
if "%VARIANT%"=="" goto :choose
goto :resolve

:choose
echo ============================================
echo  shiguangschedule build script
echo  Choose build variant:
echo    [1] debug
echo    [2] release
echo ============================================
set "CHOICE="
set /p "CHOICE=Enter 1 or 2: "
if "%CHOICE%"=="1" set "VARIANT=debug" & goto :resolve
if /i "%CHOICE%"=="debug" set "VARIANT=debug" & goto :resolve
if "%CHOICE%"=="2" set "VARIANT=release" & goto :resolve
if /i "%CHOICE%"=="release" set "VARIANT=release" & goto :resolve
echo [ERROR] Invalid choice: %CHOICE%
if not defined NOPAUSE pause
exit /b 1

:resolve
if /i "%VARIANT%"=="release" (
    set "VARIANT=release"
    set "TASK=Release"
) else if /i "%VARIANT%"=="debug" (
    set "VARIANT=debug"
    set "TASK=Debug"
) else (
    echo [ERROR] Unknown variant: %VARIANT%
    echo Usage: build.bat [debug^|release] [nopause]
    if not defined NOPAUSE pause
    exit /b 1
)

rem ---------- 2. bump version (release only) ----------
set "OLD_CODE="
set "OLD_NAME="
set "NEW_CODE="
set "NEW_NAME="
set "BUMPED="
if /i not "%VARIANT%"=="release" goto :after_bump

for /f "usebackq tokens=1-3" %%a in (`powershell -NoProfile -Command "$q=[char]34;$f='androidApp\build.gradle.kts';$t=[IO.File]::ReadAllText($f);$m=[regex]::Match($t,'versionCode\s*=\s*(\d+)');if(-not $m.Success){exit 1};$c=[int]$m.Groups[1].Value;$m2=[regex]::Match($t,'versionName\s*=\s*'+$q+'([0-9]+(\.[0-9]+)*)'+$q);if(-not $m2.Success){exit 1};$n=$m2.Groups[1].Value;$p=$n.Split('.');$p[$p.Length-1]=[string]([int]$p[$p.Length-1]+1);$nn=$p -join '.';$t=[regex]::Replace($t,'versionCode\s*=\s*'+$c+'(?![0-9])','versionCode = '+($c+1));$t=[regex]::Replace($t,'versionName\s*=\s*'+$q+[regex]::Escape($n)+$q,'versionName = '+$q+$nn+$q);[IO.File]::WriteAllText($f,$t,(New-Object System.Text.UTF8Encoding($false)));Write-Output ($c.ToString()+' '+$n+' '+$nn)"`) do (
    set "OLD_CODE=%%a"
    set "OLD_NAME=%%b"
    set "NEW_NAME=%%c"
)
if not defined NEW_NAME (
    echo [ERROR] Failed to bump version in androidApp\build.gradle.kts
    echo         check versionCode / versionName format
    if not defined NOPAUSE pause
    exit /b 1
)
set /a NEW_CODE=OLD_CODE+1
set "BUMPED=1"

:after_bump

rem ---------- 3. java (prefer JAVA_EXE / JAVA_HOME override, then AS JBR) ----------
if not defined JAVA_EXE set "JAVA_EXE=E:\app\android-studio\jbr\bin\java.exe"
if not exist "%JAVA_EXE%" (
    if defined JAVA_HOME (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA_EXE=java"
    )
)

rem ---------- 4. proxy (Gradle dist / JDK toolchain / Maven Central via local proxy) ----------
if not defined BUILD_PROXY set "BUILD_PROXY=127.0.0.1:8848"
if /i "%BUILD_PROXY%"=="direct" goto :proxy_done
set "PROXY_HOST=127.0.0.1"
set "PROXY_PORT=8848"
for /f "tokens=1,2 delims=:" %%a in ("%BUILD_PROXY%") do (
    set "PROXY_HOST=%%a"
    if not "%%b"=="" set "PROXY_PORT=%%b"
)
set "HTTP_PROXY=http://%BUILD_PROXY%"
set "HTTPS_PROXY=http://%BUILD_PROXY%"
set "JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=%PROXY_HOST% -Dhttp.proxyPort=%PROXY_PORT% -Dhttps.proxyHost=%PROXY_HOST% -Dhttps.proxyPort=%PROXY_PORT%"
set "GRADLE_OPTS=-Dhttp.proxyHost=%PROXY_HOST% -Dhttp.proxyPort=%PROXY_PORT% -Dhttps.proxyHost=%PROXY_HOST% -Dhttps.proxyPort=%PROXY_PORT%"
:proxy_done

echo ============================================
echo  shiguangschedule build script
echo  Variant: %VARIANT%
if defined BUMPED echo  Version: %OLD_NAME% (code %OLD_CODE%)  -^>  %NEW_NAME% (code %NEW_CODE%)
echo  Dir:     %PROJECT_DIR%
echo ============================================
echo.

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [ERROR] gradle\wrapper\gradle-wrapper.jar not found
    goto :failed
)

rem ---------- 5. build ----------
echo Building %VARIANT% ... log: build.log (no console output during build)
"%JAVA_EXE%" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :androidApp:assemble%TASK% --no-daemon > build.log 2>&1
if errorlevel 1 goto :failed

rem ---------- 6. copy APKs to project root ----------
set "APK_DIR=%PROJECT_DIR%androidApp\build\outputs\apk\%VARIANT%"
set "COPIED=0"

if exist "%APK_DIR%\*.apk" (
    for %%F in ("%APK_DIR%\shiguangschedule-v*-%VARIANT%.apk") do (
        copy /y "%%F" "%PROJECT_DIR%" >nul
        set /a COPIED+=1
        echo [OK] %%~nxF
    )
)

if "%COPIED%"=="0" (
    echo [WARN] build reported success but no APK found in: %APK_DIR%
    echo        check build.log
    goto :failed
)

echo.
echo [OK] Build finished! %COPIED% APK(s) copied to project root.
echo.
echo --- build.log tail ---
powershell -NoProfile -Command "Get-Content -LiteralPath '%PROJECT_DIR%build.log' -Tail 12"
echo.

rem debug stops here
if /i not "%VARIANT%"=="release" goto :done

rem ---------- 7. publish (release only) ----------
rem publish.exe must connect DIRECT (proxying Cloudreve breaks TLS):
rem clear proxy vars set for gradle above, plus any inherited from the shell
set "HTTP_PROXY="
set "HTTPS_PROXY="
set "ALL_PROXY="
set "http_proxy="
set "https_proxy="
set "all_proxy="

if not exist "%PROJECT_DIR%publish.exe" (
    echo [FAILED] publish.exe not found: %PROJECT_DIR%publish.exe
    goto :publish_failed
)

echo Publishing ...
"%PROJECT_DIR%publish.exe" -config publish.config.json -cr-apk auto
if errorlevel 1 goto :publish_failed

echo.
echo [OK] Released version %NEW_NAME% (code %NEW_CODE%) successfully.
echo.

:done
if not defined NOPAUSE pause
exit /b 0

rem ---------- build failure: roll the version bump back ----------
:failed
if defined BUMPED call :rollback_version
echo.
if defined BUMPED echo [FAILED] Build error, version bumped back to %OLD_NAME% (code %OLD_CODE%).
if not defined BUMPED echo [FAILED] Build error.
echo          See log: %PROJECT_DIR%build.log
echo.
echo If incremental cache is corrupted (kspCaches / kotlin / desugar_graph),
echo clean it and retry:
echo   del /s /q "%PROJECT_DIR%androidApp\build\kspCaches"
echo   del /s /q "%PROJECT_DIR%androidApp\build\kotlin"
echo   del /s /q "%PROJECT_DIR%androidApp\build\intermediates\desugar_graph"
if not defined NOPAUSE pause
exit /b 1

rem ---------- publish failure: keep the new version, APK is already built ----------
:publish_failed
echo.
echo [FAILED] Publish error. Version stays at %NEW_NAME% (code %NEW_CODE%).
echo          APK is ready at: %PROJECT_DIR%shiguangschedule-v%NEW_NAME%-arm64-v8a-release.apk
echo          Fix the issue and re-publish manually:
echo    "%PROJECT_DIR%publish.exe" -config publish.config.json -cr-apk auto
if not defined NOPAUSE pause
exit /b 1

rem ---------- rollback subroutine: new -> old in androidApp\build.gradle.kts ----------
:rollback_version
set "RB_OC=%OLD_CODE%"
set "RB_ON=%OLD_NAME%"
set "RB_NC=%NEW_CODE%"
set "RB_NN=%NEW_NAME%"
powershell -NoProfile -Command "$q=[char]34;$f='androidApp\build.gradle.kts';$t=[IO.File]::ReadAllText($f);$t=[regex]::Replace($t,'versionCode\s*=\s*'+$env:RB_NC+'(?![0-9])','versionCode = '+$env:RB_OC);$t=[regex]::Replace($t,'versionName\s*=\s*'+$q+[regex]::Escape($env:RB_NN)+$q,'versionName = '+$q+$env:RB_ON+$q);[IO.File]::WriteAllText($f,$t,(New-Object System.Text.UTF8Encoding($false)));if($t -match 'versionCode\s*=\s*'+$env:RB_OC+'(?![0-9])'){exit 0}else{exit 1}"
if errorlevel 1 (
    echo [WARN] Version rollback failed, fix androidApp\build.gradle.kts manually.
)
goto :eof
