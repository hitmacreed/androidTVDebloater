@echo off
setlocal enabledelayedexpansion

if "%TV_HOST%"=="" set TV_HOST=192.168.31.236
set TV_PORT=5555
set TV_SERIAL=%TV_HOST%:%TV_PORT%

echo ==========================================================
echo        NLRC / Google TV Safe UI Manager (Acer QLED)
echo ==========================================================
echo Target TV: %TV_SERIAL%
echo.

adb connect %TV_SERIAL% >nul 2>&1

echo Ensuring Stock Launcher is SAFE and ENABLED...
adb -s %TV_SERIAL% shell pm enable com.google.android.apps.tv.launcherx >nul 2>&1

:MENU
echo.
echo Select an action:
echo   [1] Activate NLRC Custom UI (Ad-Free Edition)
echo   [2] Restore Stock Google TV UI
echo   [3] Verify System Safety and Health
echo   [4] Safe Soft Reboot
echo   [5] Exit
echo.
set /p CHOICE="Enter choice [1-5]: "

if "%CHOICE%"=="1" goto ACTIVATE_NLRC
if "%CHOICE%"=="2" goto RESTORE_STOCK
if "%CHOICE%"=="3" goto CHECK_HEALTH
if "%CHOICE%"=="4" goto SAFE_REBOOT
if "%CHOICE%"=="5" goto EXIT
goto MENU

:ACTIVATE_NLRC
echo.
echo Activating NLRC Custom UI...
adb -s %TV_SERIAL% shell settings put secure enabled_accessibility_services com.hitmacreed.androidtvdebloater/.HomeKeyService
adb -s %TV_SERIAL% shell settings put secure accessibility_enabled 1
adb -s %TV_SERIAL% shell appops set com.hitmacreed.androidtvdebloater SYSTEM_ALERT_WINDOW allow
adb -s %TV_SERIAL% shell am start -n com.hitmacreed.androidtvdebloater/.MainActivity
echo NLRC Custom UI successfully activated!
goto MENU

:RESTORE_STOCK
echo.
echo Restoring Stock Google TV...
adb -s %TV_SERIAL% shell settings put secure enabled_accessibility_services ""
adb -s %TV_SERIAL% shell settings put secure accessibility_enabled 0
adb -s %TV_SERIAL% shell am start -n com.google.android.apps.tv.launcherx/.home.HomeActivity
echo Stock Google TV restored!
goto MENU

:CHECK_HEALTH
echo.
echo --- TV System Health Status ---
echo Stock Launcher state:
adb -s %TV_SERIAL% shell pm list packages -d | findstr com.google.android.apps.tv.launcherx >nul
if errorlevel 1 (
    echo [OK] Stock launcherx is ENABLED (Safe from bootloop).
) else (
    echo [WARNING] Stock launcherx is DISABLED! Re-enabling now...
    adb -s %TV_SERIAL% shell pm enable com.google.android.apps.tv.launcherx
)
echo Active Accessibility Services:
adb -s %TV_SERIAL% shell settings get secure enabled_accessibility_services
echo Current Focused Window:
adb -s %TV_SERIAL% shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
goto MENU

:SAFE_REBOOT
echo.
echo Performing safe soft reboot...
adb -s %TV_SERIAL% shell reboot
echo TV is rebooting. Please allow 45-60 seconds to reconnect.
goto EXIT

:EXIT
echo Exiting.
