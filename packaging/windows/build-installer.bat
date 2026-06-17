@echo off
REM ===========================================================================
REM  Build a Windows installer (.exe) for HyperRocket using jpackage + WiX v3.
REM
REM  Requirements:
REM    * JDK 17 on PATH (provides jpackage)
REM    * WiX Toolset v3.x installed (jpackage uses candle.exe / light.exe)
REM
REM  Usage:   packaging\windows\build-installer.bat [version]
REM  Example: packaging\windows\build-installer.bat 1.0.0
REM
REM  Output:  dist-installer\HyperRocket-<version>.exe
REM ===========================================================================
setlocal enabledelayedexpansion

REM --- Resolve the repository root (two levels up from this script) ----------
set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%\..\.." || exit /b 1
set "REPO=%CD%"

set "APP_NAME=HyperRocket"
set "APP_VERSION=%~1"
if "%APP_VERSION%"=="" set "APP_VERSION=1.0.0"
set "MAIN_CLASS=info.openrocket.swing.startup.OpenRocket"
set "ICON=%REPO%\swing\src\main\resources\pix\icon\icon-windows.ico"
set "LICENSE=%REPO%\LICENSE.TXT"
set "INPUT=%REPO%\build\libs"
set "DEST=%REPO%\dist-installer"

REM --- 1. Build the fat distributable jar if it is missing -------------------
if not exist "%INPUT%\OpenRocket-*.jar" (
  echo [build-installer] No distributable jar found - running 'gradlew shadowJar'...
  REM shadowJar builds the fat jar without the full 'check' suite that 'dist' requires.
  call "%REPO%\gradlew.bat" shadowJar || exit /b 1
)

REM --- 2. Discover the main jar file name ------------------------------------
set "MAIN_JAR="
for %%F in ("%INPUT%\OpenRocket-*.jar") do set "MAIN_JAR=%%~nxF"
if "%MAIN_JAR%"=="" (
  echo [build-installer] ERROR: no OpenRocket-*.jar in "%INPUT%".
  exit /b 1
)
echo [build-installer] Using main jar: %MAIN_JAR%

REM --- 3. Make sure WiX (candle.exe) is on PATH -----------------------------
REM Note: the WIX path contains "(x86)", so it must be handled with delayed
REM expansion (!WIX!) and single-line ifs to avoid breaking the batch parser.
where candle.exe >nul 2>&1
if errorlevel 1 if defined WIX set "PATH=!WIX!bin;!PATH!"
where candle.exe >nul 2>&1
if errorlevel 1 goto :no_wix

REM --- 4. Build the installer ------------------------------------------------
if exist "%DEST%" rmdir /s /q "%DEST%"
mkdir "%DEST%"

echo [build-installer] Running jpackage ^(this can take a few minutes^)...
jpackage ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version "%APP_VERSION%" ^
  --vendor "HyperRocket" ^
  --description "HyperRocket - model rocket design and flight simulator" ^
  --input "%INPUT%" ^
  --main-jar "%MAIN_JAR%" ^
  --main-class "%MAIN_CLASS%" ^
  --icon "%ICON%" ^
  --license-file "%LICENSE%" ^
  --java-options "-Xmx2048m" ^
  --win-dir-chooser ^
  --win-menu ^
  --win-menu-group "%APP_NAME%" ^
  --win-shortcut ^
  --win-shortcut-prompt ^
  --dest "%DEST%" || exit /b 1

echo.
echo [build-installer] Done. Installer written to:
dir /b "%DEST%\*.exe"
popd
endlocal
exit /b 0

:no_wix
echo [build-installer] ERROR: WiX Toolset v3 not found ^(candle.exe^).
echo                   Install it with: winget install WiXToolset.WiXToolset
popd
endlocal
exit /b 1
