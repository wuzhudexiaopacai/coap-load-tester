@echo off
REM Build CoapLoadTester.exe with ProGuard obfuscation + jpackage.
REM Usage: build-exe.bat
REM Output: target\jpackage\CoapLoadTester\CoapLoadTester.exe

setlocal

REM Use JAVA_HOME from environment (must point to JDK 21+)
if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME is not set. Please point it to JDK 21+.
    exit /b 1
)
set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
if not exist "%JPACKAGE%" (
    echo [ERROR] jpackage not found: %JPACKAGE%
    echo         Please make sure JAVA_HOME points to JDK 21+.
    exit /b 1
)

echo [1/4] mvn package (ProGuard obfuscate) ...
call mvn -q package -DskipTests
if errorlevel 1 goto :error

echo [2/4] Prepare target\dist ...
if exist target\dist rmdir /s /q target\dist
mkdir target\dist
copy /y target\coap-load-tester-1.0.0-obf.jar target\dist\ >nul
call mvn -q dependency:copy-dependencies "-DoutputDirectory=target/dist" "-DincludeScope=runtime"
if errorlevel 1 goto :error

echo [3/4] jpackage app-image ...
if exist target\jpackage rmdir /s /q target\jpackage
"%JPACKAGE%" --type app-image ^
  --name CoapLoadTester ^
  --input target\dist ^
  --main-jar coap-load-tester-1.0.0-obf.jar ^
  --main-class com.github.wuzhudexiaopacai.CoapLoadTester ^
  --dest target\jpackage ^
  --app-version 1.0.0 ^
  --vendor ndx
if errorlevel 1 goto :error

echo [4/4] Done.
echo Output: %cd%\target\jpackage\CoapLoadTester\CoapLoadTester.exe
endlocal
exit /b 0

:error
echo [ERROR] build failed.
endlocal
exit /b 1
