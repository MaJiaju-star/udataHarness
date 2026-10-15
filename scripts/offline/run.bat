@echo off
setlocal
set "BUNDLE_DIR=%~dp0"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_CMD set "JAVA_CMD=java"
"%JAVA_CMD%" "-Dudata.bundle.dir=%BUNDLE_DIR%." -jar "%BUNDLE_DIR%udata-harness.jar" %*
