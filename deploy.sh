#!/bin/bash
#
# Hot-deploy gateway APK to the Magisk module overlay (no reboot).
#
# Usage:
#   ./deploy.sh              # build release + hot-swap APK on device
#   ./deploy.sh --no-build   # use existing gateway.apk
#   ./deploy.sh -s SERIAL    # target a specific adb device
#   ./deploy.sh --reboot     # full Magisk module reinstall + reboot
#   ./deploy.sh --no-bridge # skip SharedPreferences hub configure step
#
# Hot-swap updates the priv-app APK in-place. Use --reboot when the module
# structure or privapp-permissions manifest changed (new permissions, paths).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MODULE_ID="gsm2computer-bridge"
MODULE_APK="/data/adb/modules/${MODULE_ID}/system/priv-app/Gateway/Gateway.apk"
SYSTEM_APK="/system/priv-app/Gateway/Gateway.apk"
PKG="com.gsm2computer.bridge"
TMP_APK="/data/local/tmp/Gateway.apk"

NO_BUILD=false
REBOOT=false
NO_BRIDGE=false
ADB_SERIAL=""

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) NO_BUILD=true ;;
        --no-bridge) NO_BRIDGE=true ;;
        --reboot)   REBOOT=true ;;
        -s)
            shift
            [ $# -gt 0 ] || { echo "ERROR: -s requires a device serial"; exit 1; }
            ADB_SERIAL="$1"
            ;;
        -h|--help) usage 0 ;;
        *)
            echo "ERROR: unknown argument: $1"
            usage 1
            ;;
    esac
    shift
done

adb_cmd() {
    if [ -n "$ADB_SERIAL" ]; then
        adb -s "$ADB_SERIAL" "$@"
    else
        adb "$@"
    fi
}

fail() {
    echo "FAIL: $*"
    exit 1
}

ok() {
    echo "OK: $*"
}

resolve_device() {
    if [ -n "$ADB_SERIAL" ]; then
        echo "Using device: $ADB_SERIAL"
        return
    fi
    mapfile -t devices < <(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')
    if [ "${#devices[@]}" -eq 0 ]; then
        fail "no adb device connected (use -s SERIAL)"
    fi
    if [ "${#devices[@]}" -gt 1 ]; then
        fail "multiple adb devices — specify one with -s SERIAL (${devices[*]})"
    fi
    ADB_SERIAL="${devices[0]}"
    echo "Auto-detected device: $ADB_SERIAL"
}

build_release() {
    echo ""
    echo "=== Building release APK ==="
    ./build.sh release
}

build_magisk_only() {
    # build.sh always builds APK + module; for --reboot --no-build we only need the zip.
    if [ ! -f "$SCRIPT_DIR/gateway.apk" ]; then
        fail "gateway.apk not found — run without --no-build"
    fi
    echo ""
    echo "=== Packaging Magisk module from existing gateway.apk ==="
    mkdir -p "$SCRIPT_DIR/magisk/system/priv-app/Gateway"
    cp "$SCRIPT_DIR/gateway.apk" "$SCRIPT_DIR/magisk/system/priv-app/Gateway/Gateway.apk"
    cd "$SCRIPT_DIR/magisk"
    rm -f "$SCRIPT_DIR/gateway-magisk.zip"
    zip -qr "$SCRIPT_DIR/gateway-magisk.zip" . -x "*.DS_Store" -x "__MACOSX/*"
    cd "$SCRIPT_DIR"
    ok "Magisk module: $SCRIPT_DIR/gateway-magisk.zip"
}

verify_root() {
    if ! adb_cmd shell su -c "id" 2>/dev/null | grep -q "uid=0"; then
        fail "device root (su) required for priv-app hot-swap"
    fi
}

verify_module_installed() {
    if ! adb_cmd shell su -c "test -f '$MODULE_APK'" 2>/dev/null; then
        fail "Magisk module not installed at $MODULE_APK — install gateway-magisk.zip first (./deploy.sh --reboot)"
    fi
}

verify_md5() {
    local host_md5 device_md5
    host_md5=$(md5sum "$SCRIPT_DIR/gateway.apk" | awk '{print $1}')
    device_md5=$(adb_cmd shell su -c "md5sum '$SYSTEM_APK'" 2>/dev/null | awk '{print $1}')
    echo "Host MD5:   $host_md5"
    echo "Device MD5: $device_md5"
    if [ "$host_md5" != "$device_md5" ]; then
        fail "MD5 mismatch — hot-swap did not take effect"
    fi
    ok "MD5 match ($host_md5)"
}

hot_swap_apk() {
    echo ""
    echo "=== Hot-swapping APK ==="
    adb_cmd push "$SCRIPT_DIR/gateway.apk" "$TMP_APK"
    adb_cmd shell su -c "cp '$TMP_APK' '$MODULE_APK'"
    # Separate su session: chmod immediately after cp fails on some Magisk builds.
    adb_cmd shell su -c "chmod 644 '$MODULE_APK'" 2>/dev/null || true
    ok "copied to $MODULE_APK"
}

restart_gateway() {
    echo ""
    echo "=== Restarting gateway service ==="
    adb_cmd shell am force-stop "$PKG"

    # MicCapabilityGuard / BootReceiver path: root-launch invisible MainActivity
    # trampoline so FGS starts with PROCESS_CAPABILITY_FOREGROUND_MICROPHONE.
    local relaunch_extra="mic_capability_relaunch"
    local start_cmd="am start -n ${PKG}/.MainActivity --ez ${relaunch_extra} true --activity-brought-to-front"
    local result
    result=$(adb_cmd shell su -c "$start_cmd" 2>&1) || true
    if echo "$result" | grep -qiE 'Error|SecurityException|does not exist'; then
        echo "WARN: foreground relaunch failed [$result], falling back to broadcast"
        adb_cmd shell am broadcast -a android.intent.action.MY_PACKAGE_REPLACED -p "$PKG" >/dev/null 2>&1 || true
    else
        ok "foreground relaunch started [$result]"
    fi
}

configure_bridge() {
    echo ""
    echo "=== Configuring hub stream URL ==="
    local args=()
    [ -n "$ADB_SERIAL" ] && args+=(-s "$ADB_SERIAL")
    args+=(--force)
    "$SCRIPT_DIR/scripts/configure-bridge.sh" "${args[@]}"
}

full_reboot_deploy() {
    echo ""
    echo "=== Full Magisk module reinstall + reboot ==="
    [ -f "$SCRIPT_DIR/gateway-magisk.zip" ] || fail "gateway-magisk.zip not found"
    adb_cmd push "$SCRIPT_DIR/gateway-magisk.zip" /data/local/tmp/gateway-magisk.zip
    adb_cmd shell su -c "magisk --install-module /data/local/tmp/gateway-magisk.zip"
    ok "module installed — rebooting"
    adb_cmd reboot
    echo "Waiting for device..."
    if [ -n "$ADB_SERIAL" ]; then
        adb -s "$ADB_SERIAL" wait-for-device
    else
        adb wait-for-device
    fi
    sleep 45
    verify_md5
    ok "full deploy complete after reboot"
}

# ── Main ─────────────────────────────────────────────

echo "=== GSM2Computer Bridge Deploy ==="

if ! command -v adb &>/dev/null; then
    fail "adb not found in PATH"
fi

resolve_device

if [ "$NO_BUILD" = true ]; then
    [ -f "$SCRIPT_DIR/gateway.apk" ] || fail "gateway.apk not found — run without --no-build"
    echo "Skipping build (--no-build)"
else
    build_release
fi

if [ "$REBOOT" = true ]; then
    if [ "$NO_BUILD" = true ]; then
        build_magisk_only
    fi
    full_reboot_deploy
    exit 0
fi

verify_root
verify_module_installed
hot_swap_apk
verify_md5
if [ "$NO_BRIDGE" = false ]; then
    configure_bridge
fi
restart_gateway

echo ""
ok "hot-deploy complete (no reboot)"
