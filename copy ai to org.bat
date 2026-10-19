@echo off
chcp 65001 > nul

set "CURRENT_DIR=%~dp0"
set "BACKEND_SOURCE=%CURRENT_DIR%backend\src"
set "FRONTEND_SOURCE=%CURRENT_DIR%frontend\src"
set "MOBILE_SOURCE=%CURRENT_DIR%mobile\src"

set "BACKEND_DEST=D:\source_code\smartfarm360\backend\src"
set "FRONTEND_DEST=D:\source_code\smartfarm360\frontend\src"
set "MOBILE_DEST=D:\source_code\smartfarm360\mobile\src"

echo ===================================================
echo [START] Forcing Overwrite Project Copy...
echo ===================================================

:: Removed /XO to ensure it copies every single file over
robocopy "%BACKEND_SOURCE%" "%BACKEND_DEST%" /E /R:3 /W:5
echo.
robocopy "%FRONTEND_SOURCE%" "%FRONTEND_DEST%" /E /R:3 /W:5
echo.
robocopy "%MOBILE_SOURCE%" "%MOBILE_DEST%" /E /R:3 /W:5
echo.

echo ===================================================
echo [FINISHED] Overwrite complete!
echo ===================================================
pause
