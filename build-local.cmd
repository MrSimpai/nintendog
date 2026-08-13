@echo off
REM Builds the APK on this machine using the private toolchain that lives beside
REM the project (kept out of the repo so the folder stays drag-and-droppable).
REM
REM CI does not use this script — see .github/workflows/main.yml.

set "TOOLCHAIN=%~dp0..\nintendog-toolchain"

if not exist "%TOOLCHAIN%\jdk" (
    echo Toolchain not found at "%TOOLCHAIN%".
    echo Either restore it there, or just open this folder in Android Studio.
    exit /b 1
)

for /d %%J in ("%TOOLCHAIN%\jdk\jdk-*") do set "JAVA_HOME=%%J"
set "ANDROID_SDK_ROOT=%TOOLCHAIN%\sdk"
set "ANDROID_HOME=%TOOLCHAIN%\sdk"

echo JAVA_HOME=%JAVA_HOME%
echo ANDROID_SDK_ROOT=%ANDROID_SDK_ROOT%

call "%~dp0gradlew.bat" %* assembleDebug
if errorlevel 1 exit /b 1

echo.
echo APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
