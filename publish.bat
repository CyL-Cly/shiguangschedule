@echo off

publish.exe -config publish.config.json -cr-apk auto
pause
exit
REM ============================================================
REM  shiguangschedule publish script
REM   1) Read versionCode/versionName from androidApp\build.gradle.kts
REM   2) Upload APK to Cloudreve and get a permanent direct link
REM   3) Write shiguangschedule.json and publish it to the retiehe site
REM  -cr-apk auto locates shiguangschedule-v<versionName>-arm64-v8a-release.apk
REM  Append -dry-run to generate the JSON locally without publishing
REM ============================================================
