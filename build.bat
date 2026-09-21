@echo off
setlocal

rem =====================================================
rem  shiguangschedule build script
rem  Usage: build.bat [debug|release]
rem    build.bat          -> prompt to choose debug / release
rem    build.bat debug    -> build debug, copy APKs
rem    build.bat release  -> build release, copy APKs
rem  Output: shiguangschedule-v{version}-{abi}-{type}.apk in project root
rem          (abi = arm64-v8a)
rem  Build log: build.log
rem =====================================================

set "PROJECT_DIR=%~dp0"
cd /d "%PROJECT_DIR%"

rem ---------- 1. variant ----------
set "VARIANT=%~1"
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
    echo Usage: build.bat [debug^|release]
    exit /b 1
)

rem ---------- 2. java (not in PATH on this machine, prefer AS JBR) ----------
set "JAVA_EXE=E:\app\android-studio\jbr\bin\java.exe"
if not exist "%JAVA_EXE%" (
    if defined JAVA_HOME (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA_EXE=java"
    )
)

rem Foreign downloads (Gradle dist, JDK toolchain, Maven Central) via local HTTP proxy.
set "HTTP_PROXY=http://127.0.0.1:8848"
set "HTTPS_PROXY=http://127.0.0.1:8848"
set "JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8848 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8848"
set "GRADLE_OPTS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8848 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8848"

echo ============================================
echo  shiguangschedule build script
echo  Variant: %VARIANT%
echo  Dir:     %PROJECT_DIR%
echo ============================================
echo.

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [ERROR] gradle\wrapper\gradle-wrapper.jar not found
    exit /b 1
)

echo Building %VARIANT% ... log: build.log (no console output during build)
"%JAVA_EXE%" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :androidApp:assemble%TASK% --no-daemon > build.log 2>&1
if errorlevel 1 goto :failed

rem ---------- 3. copy APKs to project root ----------
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
    echo [WARN] build done but no APK found in: %APK_DIR%
    echo check build.log
    exit /b 1
)

echo.
echo [OK] Build finished! %COPIED% APK(s) copied to project root.
echo.
echo --- build.log tail ---
powershell -NoProfile -Command "Get-Content -LiteralPath '%PROJECT_DIR%build.log' -Tail 12"
pause
exit /b 0

:failed
echo.
echo [FAILED] Build error, see log: %PROJECT_DIR%build.log
echo.
echo If incremental cache is corrupted (kspCaches / kotlin / desugar_graph),
echo clean it and retry:
echo   del /s /q "%PROJECT_DIR%androidApp\build\kspCaches"
echo   del /s /q "%PROJECT_DIR%androidApp\build\kotlin"
echo   del /s /q "%PROJECT_DIR%androidApp\build\intermediates\desugar_graph"
pause
exit /b 1
