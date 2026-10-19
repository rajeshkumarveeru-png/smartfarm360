@echo off

title Smart Billing Development

echo.
echo ============================================================
echo              SMART BILLING DEVELOPMENT MODE
echo ============================================================
echo.

REM ============================================================
REM PROJECT ROOT
REM ============================================================

set "SMART_BILLING_ROOT=%~dp0"
set "SMART_BILLING_ROOT=%SMART_BILLING_ROOT:~0,-1%"


REM ============================================================
REM DIRECTORIES
REM ============================================================

set "SMART_BILLING_CONFIG=%SMART_BILLING_ROOT%\config"
set "SMART_BILLING_LOGS=%SMART_BILLING_ROOT%\logs"

set "SMART_BILLING_PROPERTIES=%SMART_BILLING_CONFIG%\application.properties"
set "SMART_BILLING_LOGBACK=%SMART_BILLING_CONFIG%\logback-spring.xml"


REM ============================================================
REM DISPLAY CONFIGURATION
REM ============================================================

echo Project Root:
echo %SMART_BILLING_ROOT%
echo.

echo Backend:
echo %SMART_BILLING_ROOT%\backend
echo.

echo Frontend:
echo %SMART_BILLING_ROOT%\frontend
echo.

echo External Configuration:
echo %SMART_BILLING_CONFIG%
echo.

echo Application Properties:
echo %SMART_BILLING_PROPERTIES%
echo.

echo External Logging Configuration:
echo %SMART_BILLING_LOGBACK%
echo.

echo Log Directory:
echo %SMART_BILLING_LOGS%
echo.


REM ============================================================
REM CREATE REQUIRED DIRECTORIES
REM ============================================================

if not exist "%SMART_BILLING_CONFIG%" (
    echo Creating config directory...
    mkdir "%SMART_BILLING_CONFIG%"
)

if not exist "%SMART_BILLING_LOGS%" (
    echo Creating logs directory...
    mkdir "%SMART_BILLING_LOGS%"
)

if not exist "%SMART_BILLING_LOGS%\archive" (
    echo Creating log archive directory...
    mkdir "%SMART_BILLING_LOGS%\archive"
)


REM ============================================================
REM VALIDATE BACKEND DIRECTORY
REM ============================================================

if not exist "%SMART_BILLING_ROOT%\backend" (
    echo.
    echo ============================================================
    echo ERROR: BACKEND DIRECTORY NOT FOUND
    echo ============================================================
    echo.
    echo Expected:
    echo %SMART_BILLING_ROOT%\backend
    echo.
    pause
    exit /b 1
)


REM ============================================================
REM VALIDATE GRADLE WRAPPER
REM ============================================================

if not exist "%SMART_BILLING_ROOT%\backend\gradlew.bat" (
    echo.
    echo ============================================================
    echo ERROR: GRADLE WRAPPER NOT FOUND
    echo ============================================================
    echo.
    echo Expected:
    echo %SMART_BILLING_ROOT%\backend\gradlew.bat
    echo.
    pause
    exit /b 1
)


REM ============================================================
REM VALIDATE FRONTEND DIRECTORY
REM ============================================================

if not exist "%SMART_BILLING_ROOT%\frontend" (
    echo.
    echo ============================================================
    echo ERROR: FRONTEND DIRECTORY NOT FOUND
    echo ============================================================
    echo.
    echo Expected:
    echo %SMART_BILLING_ROOT%\frontend
    echo.
    pause
    exit /b 1
)


REM ============================================================
REM VALIDATE APPLICATION.PROPERTIES
REM ============================================================

if not exist "%SMART_BILLING_PROPERTIES%" (

    echo.
    echo ============================================================
    echo ERROR: application.properties NOT FOUND
    echo ============================================================
    echo.

    echo Expected:
    echo %SMART_BILLING_PROPERTIES%

    echo.
    echo Please create:
    echo %SMART_BILLING_PROPERTIES%

    echo.
    pause
    exit /b 1
)


REM ============================================================
REM VALIDATE LOGBACK-SPRING.XML
REM ============================================================

if not exist "%SMART_BILLING_LOGBACK%" (

    echo.
    echo ============================================================
    echo ERROR: logback-spring.xml NOT FOUND
    echo ============================================================
    echo.

    echo Expected:
    echo %SMART_BILLING_LOGBACK%

    echo.
    echo Please create:
    echo %SMART_BILLING_LOGBACK%

    echo.
    pause
    exit /b 1
)


REM ============================================================
REM SPRING BOOT EXTERNAL CONFIGURATION
REM ============================================================

set "SPRING_CONFIG_ADDITIONAL_LOCATION=file:%SMART_BILLING_CONFIG%/"
set "LOGGING_CONFIG=file:%SMART_BILLING_LOGBACK%"
set "SMART_BILLING_LOG_DIR=%SMART_BILLING_LOGS%"


REM ============================================================
REM DISPLAY LOGGING CONFIGURATION
REM ============================================================

echo.
echo ============================================================
echo                 LOGGING CONFIGURATION
echo ============================================================
echo.

echo Log Directory:
echo %SMART_BILLING_LOG_DIR%
echo.

echo Active Log:
echo %SMART_BILLING_LOG_DIR%\smartbilling.log
echo.

echo Archive:
echo %SMART_BILLING_LOG_DIR%\archive\
echo.


REM ============================================================
REM START SPRING BOOT
REM ============================================================

echo.
echo ============================================================
echo                 STARTING SPRING BOOT
echo ============================================================
echo.

echo Backend:
echo http://localhost:8080
echo.

echo Working Directory:
echo %SMART_BILLING_ROOT%\backend
echo.

echo External application.properties:
echo %SMART_BILLING_PROPERTIES%
echo.

echo External logback-spring.xml:
echo %SMART_BILLING_LOGBACK%
echo.

echo Logs:
echo %SMART_BILLING_LOGS%
echo.

echo Starting Spring Boot...
echo.


REM
REM IMPORTANT:
REM
REM start /D sets the working directory.
REM This avoids the nested quote problem from:
REM
REM start ... cmd /k "cd /d "%PATH%" ..."
REM

start "Smart Billing - Spring Boot" /D "%SMART_BILLING_ROOT%\backend" cmd /k gradlew.bat bootRun


REM ============================================================
REM WAIT FOR BACKEND WINDOW
REM ============================================================

echo.
echo Waiting for Spring Boot startup window...
echo.

timeout /t 5 /nobreak >nul


REM ============================================================
REM START VITE FRONTEND
REM ============================================================

echo.
echo ============================================================
echo                 STARTING VITE FRONTEND
echo ============================================================
echo.

echo Frontend:
echo http://localhost:5173
echo.

echo Working Directory:
echo %SMART_BILLING_ROOT%\frontend
echo.

echo Starting Vite...
echo.


start "Smart Billing - Vite" /D "%SMART_BILLING_ROOT%\frontend" cmd /k npm run dev


REM ============================================================
REM STARTUP SUMMARY
REM ============================================================

echo.
echo ============================================================
echo              SMART BILLING SERVICES STARTING
echo ============================================================
echo.

echo Backend:
echo http://localhost:8080
echo.

echo Frontend:
echo http://localhost:5173
echo.

echo.
echo External Configuration:
echo %SMART_BILLING_CONFIG%
echo.

echo Application Properties:
echo %SMART_BILLING_PROPERTIES%
echo.

echo Logback:
echo %SMART_BILLING_LOGBACK%
echo.

echo Logs:
echo %SMART_BILLING_LOGS%\
echo.

echo Active Log:
echo %SMART_BILLING_LOGS%\smartbilling.log
echo.

echo Archive:
echo %SMART_BILLING_LOGS%\archive\
echo.

echo.
echo ============================================================
echo                 SERVICES STARTED
echo ============================================================
echo.

echo   [1] Spring Boot
echo       http://localhost:8080
echo.

echo   [2] Vite
echo       http://localhost:5173
echo.

echo Open the application:
echo http://localhost:5173
echo.

echo ============================================================
echo.

pause