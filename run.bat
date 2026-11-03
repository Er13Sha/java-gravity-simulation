@echo off
cd /d "%~dp0"
if not exist "build\input\universal-interaction-formula.jar" (
    call build.bat
)
if not exist "build\input\universal-interaction-formula.jar" exit /b 1
rem тот JDK (22+), которым собирал build.ps1
set "JDK="
if exist "build\jdk.txt" set /p JDK=<"build\jdk.txt"
if defined JDK (
    start "" "%JDK%\bin\javaw.exe" -jar "build\input\universal-interaction-formula.jar"
) else if defined JAVA_HOME (
    start "" "%JAVA_HOME%\bin\javaw.exe" -jar "build\input\universal-interaction-formula.jar"
) else (
    start "" javaw -jar "build\input\universal-interaction-formula.jar"
)
