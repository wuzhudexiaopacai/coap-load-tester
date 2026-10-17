@echo off
REM Launch CoapLoadTester GUI using JDK 21.
REM Usage: run-loadtest-gui.bat

setlocal

REM Use JAVA_HOME from environment (must point to JDK 21+)
if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME is not set. Please point it to JDK 21+.
    exit /b 1
)
set "JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA%" (
    echo [ERROR] java not found: %JAVA%
    echo         Please make sure JAVA_HOME points to JDK 21+.
    exit /b 1
)

if not exist target\classes\com\github\wuzhudexiaopacai\CoapLoadTester.class (
    echo [INFO] Compiling project ...
    call mvn -q compile
)

if not exist cp.txt (
    echo [INFO] Building classpath ...
    call mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
)

for /f "usebackq delims=" %%i in ("cp.txt") do set "CP=%%i"
set "FULL_CP=target\classes;%CP%"

echo [INFO] Launching CoapLoadTester ...
start "" "%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "%FULL_CP%" com.github.wuzhudexiaopacai.CoapLoadTester

endlocal
