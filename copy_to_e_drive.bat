@echo off
echo ========================================
echo   PocketHost Project Transfer Script
echo ========================================
echo.
echo This script will copy PocketHost project to E:\PocketHost
echo Requires Administrator privileges
echo.
pause

echo.
echo Copying files...
robocopy "C:\Users\W1zago\PocketHost" "E:\PocketHost" /E /MT:8 /R:3 /W:5

if %ERRORLEVEL% LEQ 7 (
    echo.
    echo ========================================
    echo   SUCCESS! Project copied to E:\PocketHost
    echo ========================================
    echo.
    echo You can now open the project in Android Studio:
    echo File ^> Open ^> E:\PocketHost
    echo.
) else (
    echo.
    echo ========================================
    echo   ERROR: Copy failed
    echo ========================================
    echo.
    echo Please check:
    echo 1. Run this script as Administrator
    echo 2. E:\ drive is accessible
    echo 3. Sufficient disk space
    echo.
)

pause
