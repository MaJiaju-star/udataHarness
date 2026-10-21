@echo off
setlocal EnableExtensions DisableDelayedExpansion
title UdataHarness

pushd "%~dp0"
if errorlevel 1 (
    echo [ERROR] Cannot open the application directory.
    pause
    exit /b 1
)

set "HARNESS_JAR=udata-harness.jar"
if not exist "%HARNESS_JAR%" set "HARNESS_JAR=target\udata-harness.jar"
if not exist "%HARNESS_JAR%" (
    echo [ERROR] udata-harness.jar was not found.
    echo Place this script beside udata-harness.jar, or build the project first.
    goto failed
)

set "HARNESS_JAVA=%~dp0runtime\bin\java.exe"
if exist "%HARNESS_JAVA%" goto ready
if defined JAVA_HOME (
    set "HARNESS_JAVA=%JAVA_HOME%\bin\java.exe"
)
if exist "%HARNESS_JAVA%" goto ready
set "HARNESS_JAVA=java.exe"
where java.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found. Install Java 8 or later and configure JAVA_HOME or PATH.
    goto failed
)

:ready
if not defined SOLON_AI_CLI_SHELL set "SOLON_AI_CLI_SHELL=powershell"
if not defined UDATA_HARNESS_URL set "UDATA_HARNESS_URL=http://127.0.0.1:8080/"
set "UDATA_HARNESS_BROWSER_LOG=%~dp0browser-start.log"
echo Starting UdataHarness...
echo The browser will open when the server is ready: %UDATA_HARNESS_URL%
echo Browser startup diagnostics: browser-start.log
echo Keep this window open. Press Ctrl+C to stop the server.
echo Terminal: %SOLON_AI_CLI_SHELL%
echo.
start "" /b powershell.exe -NoLogo -NoProfile -NonInteractive -Command "$url = $env:UDATA_HARNESS_URL; $log = $env:UDATA_HARNESS_BROWSER_LOG; Set-Content -LiteralPath $log -Value ('Waiting for ' + $url); $deadline = [DateTime]::UtcNow.AddSeconds(120); $ready = $false; while ([DateTime]::UtcNow -lt $deadline) { $response = $null; try { $request = [System.Net.HttpWebRequest]::Create($url); $request.Proxy = $null; $request.Timeout = 2000; $request.ReadWriteTimeout = 2000; $response = $request.GetResponse(); $ready = ([int]$response.StatusCode -eq 200) } catch { Add-Content -LiteralPath $log -Value $_.Exception.Message } finally { if ($response) { $response.Close() } }; if ($ready) { break }; Start-Sleep -Seconds 1 }; if ($ready) { try { Start-Process -FilePath $url -ErrorAction Stop; Add-Content -LiteralPath $log -Value 'Default browser launch requested.'; Write-Host '[Browser] Server ready; browser launch requested.' } catch { Add-Content -LiteralPath $log -Value $_.Exception.Message; Write-Host '[Browser] Could not open browser. Open the URL manually; see browser-start.log.' } } else { Add-Content -LiteralPath $log -Value 'Timed out after 120 seconds.'; Write-Host '[Browser] Startup check timed out. Open the URL manually; see browser-start.log.' }"
"%HARNESS_JAVA%" -jar "%HARNESS_JAR%" %*
set "HARNESS_EXIT=%ERRORLEVEL%"
if not "%HARNESS_EXIT%"=="0" echo [ERROR] Startup or execution failed. See the output above.
goto finished

:failed
set "HARNESS_EXIT=1"

:finished
echo.
pause
popd
endlocal & exit /b %HARNESS_EXIT%
