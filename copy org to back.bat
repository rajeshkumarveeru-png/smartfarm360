@echo off
:: Enable UTF-8 encoding in the console for file paths
chcp 65001 > nul

:: Define Paths
set "BACKEND_SOURCE=D:\source_code\smartfarm360\backend\src"
set "BACKEND_DEST=D:\source_code\smartfarmBack\backend\src"

set "FRONTEND_SOURCE=D:\source_code\smartfarm360\frontend\src"
set "FRONTEND_DEST=D:\source_code\smartfarmBack\frontend\src"

set "MOBILE_SOURCE=D:\source_code\smartfarm360\mobile\src"
set "MOBILE_DEST=D:\source_code\smartfarmBack\mobile\src"

echo ===================================================
echo [START] Project Copy Automation Setup (Mirror & Purge Mode)
echo ===================================================

:: 1. Copy Backend Files
echo [1/2] Mirroring Backend Source (Overwriting changes and purging deleted files)...
echo From: %BACKEND_SOURCE%
echo To:   %BACKEND_DEST%
:: /MIR : Mirrors a directory tree (equivalent to /E plus /PURGE). Deletes destination files if missing from source.
robocopy "%BACKEND_SOURCE%" "%BACKEND_DEST%" /MIR /R:3 /W:5
echo.

:: 2. Copy Frontend Files
echo [2/3] Mirroring Frontend Source (Overwriting changes and purging deleted files)...
echo From: %FRONTEND_SOURCE%
echo To:   %FRONTEND_DEST%
:: /MIR : Mirrors a directory tree (equivalent to /E plus /PURGE). Deletes destination files if missing from source.
robocopy "%FRONTEND_SOURCE%" "%FRONTEND_DEST%" /MIR /R:3 /W:5
echo.

:: 2. Copy Frontend Files
echo [3/3] Mirroring Frontend Source (Overwriting changes and purging deleted files)...
echo From: %MOBILE_SOURCE%
echo To:   %MOBILE_DEST%
:: /MIR : Mirrors a directory tree (equivalent to /E plus /PURGE). Deletes destination files if missing from source.
robocopy "%MOBILE_SOURCE%" "%MOBILE_DEST%" /MIR /R:3 /W:5
echo.

echo ===================================================
echo [FINISHED] All source folders copied successfully!
echo ===================================================
pause
