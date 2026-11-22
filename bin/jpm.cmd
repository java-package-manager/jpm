@echo off
rem Launcher for the jar build of jpm on Windows (cli\dist and api\dist). The native binary, cli\dist\jpm.exe,
rem needs no launcher.
setlocal
set "HERE=%~dp0.."
set "CLI="
set "API="
for /f "delims=" %%f in ('dir /b /o-d "%HERE%\cli\dist\jpm-?.*.jar" 2^>nul') do if not defined CLI set "CLI=%HERE%\cli\dist\%%f"
for /f "delims=" %%f in ('dir /b /o-d "%HERE%\api\dist\api-*.jar" 2^>nul') do if not defined API set "API=%HERE%\api\dist\%%f"
set "CP=%HERE%\.cache\bootstrap\jpm.jar"
if defined CLI if defined API set "CP=%CLI%;%API%"
if not defined CLI if not exist "%CP%" (
  echo jpm is not built yet; run bootstrap.sh 1>&2
  exit /b 1
)
rem scripts, hooks and extensions call back into jpm through JPM_EXE
set "JPM_EXE=%HERE%\bin\jpm.cmd"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -cp "%CP%" dev.jpm.cli.Main %*
