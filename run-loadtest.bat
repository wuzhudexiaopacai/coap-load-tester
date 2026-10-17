@echo off
REM Run CoAP TLS/TCP load test using JDK 21.
REM Usage: run-loadtest.bat [args]
REM   e.g. run-loadtest.bat --threads 50 --duration 30 --path /hello --method GET

setlocal

REM Force JDK 21 (override any system JAVA_HOME that may point to an older JDK)
set JAVA_HOME=D:\jdk21
set "JAVA=%JAVA_HOME%\bin\java.exe"

if not exist target\classes\com\coaplearn\tls\CoapTlsLoadTest.class (
    echo [INFO] Compiling project ...
    call mvn -q compile
)

if not exist cp.txt (
    echo [INFO] Building classpath ...
    call mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
)

for /f "usebackq delims=" %%i in ("cp.txt") do set "CP=%%i"
set "FULL_CP=target\classes;%CP%"

echo [INFO] Running CoAP TLS load test with args: %*
"%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "%FULL_CP%" com.coaplearn.tls.CoapTlsLoadTest %*

endlocal
