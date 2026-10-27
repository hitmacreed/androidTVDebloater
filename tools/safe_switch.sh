#!/usr/bin/env bash
# One-click safe switch and diagnostics for NLRC / Google TV on Acer QLED Android 14
set -euo pipefail

TV_HOST="${TV_HOST:-192.168.31.236}"
TV_PORT="${TV_PORT:-5555}"
TV_SERIAL="${TV_HOST}:${TV_PORT}"

echo "=========================================================="
echo "       NLRC / Google TV Safe UI Manager (Acer QLED)       "
echo "=========================================================="
echo "Target TV: ${TV_SERIAL}"
echo ""

adb connect "${TV_SERIAL}" >/dev/null 2>&1 || true

echo "Ensuring Stock Launcher is SAFE and ENABLED..."
adb -s "${TV_SERIAL}" shell pm enable com.google.android.apps.tv.launcherx >/dev/null 2>&1 || true

echo ""
echo "Select an action:"
echo "  [1] Activate NLRC Custom UI (Ad-Free Edition)"
echo "  [2] Restore Stock Google TV UI"
echo "  [3] Verify System Safety and Health"
echo "  [4] Safe Soft Reboot"
echo "  [5] Exit"
echo ""
read -p "Enter choice [1-5]: " CHOICE

case "${CHOICE}" in
  1)
    echo "Activating NLRC Custom UI..."
    adb -s "${TV_SERIAL}" shell appops set com.hitmacreed.androidtvdebloater AUTO_START allow
    adb -s "${TV_SERIAL}" shell settings delete secure enabled_accessibility_services
    adb -s "${TV_SERIAL}" shell settings put secure enabled_accessibility_services com.hitmacreed.androidtvdebloater/.HomeKeyService
    adb -s "${TV_SERIAL}" shell settings put secure accessibility_enabled 1
    adb -s "${TV_SERIAL}" shell appops set com.hitmacreed.androidtvdebloater SYSTEM_ALERT_WINDOW allow
    adb -s "${TV_SERIAL}" shell am start -n com.hitmacreed.androidtvdebloater/.MainActivity
    echo "NLRC Custom UI successfully activated!"
    ;;
  2)
    echo "Restoring Stock Google TV..."
    adb -s "${TV_SERIAL}" shell settings put secure enabled_accessibility_services '""'
    adb -s "${TV_SERIAL}" shell settings put secure accessibility_enabled 0
    adb -s "${TV_SERIAL}" shell am start -n com.google.android.apps.tv.launcherx/.home.HomeActivity
    echo "Stock Google TV restored!"
    ;;
  3)
    echo "--- TV System Health Status ---"
    if adb -s "${TV_SERIAL}" shell pm list packages -d | grep -q "com.google.android.apps.tv.launcherx"; then
      echo "[WARNING] Stock launcherx is DISABLED! Re-enabling now..."
      adb -s "${TV_SERIAL}" shell pm enable com.google.android.apps.tv.launcherx
    else
      echo "[OK] Stock launcherx is ENABLED (Safe from bootloop)."
    fi
    echo "Active Accessibility Services:"
    adb -s "${TV_SERIAL}" shell settings get secure enabled_accessibility_services
    echo "Current Focused Window:"
    adb -s "${TV_SERIAL}" shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
    ;;
  4)
    echo "Performing safe soft reboot..."
    adb -s "${TV_SERIAL}" shell reboot
    echo "TV is rebooting."
    ;;
  *)
    echo "Exiting."
    ;;
esac
