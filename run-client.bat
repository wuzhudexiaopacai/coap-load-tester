@echo off
REM Run CoAP over TLS/TCP client using JDK 21.
REM Usage: run-client.bat

setlocal

REM Force JDK 21 (override any system JAVA_HOME that may point to an older JDK)
set JAVA_HOME=D:\jdk21
set "JAVA=%JAVA_HOME%\bin\java.exe"

if not exist target\classes\com\coaplearn\tls\CoapTlsClient.class (
    echo [INFO] Compiling project ...
    call mvn -q compile
)

if not exist cp.txt (
    echo [INFO] Building classpath ...
    call mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
)

for /f "usebackq delims=" %%i in ("cp.txt") do set "CP=%%i"
set "FULL_CP=target\classes;%CP%"

echo [INFO] Running CoAP TLS client ...
"%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "%FULL_CP%" com.coaplearn.tls.CoapTlsClient

endlocal
