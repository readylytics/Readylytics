#!/usr/bin/env bash
set -euo pipefail

# ---------- Configuration ----------
AVD_NAME="${AVD_NAME:-android16_test}"
API_LEVEL="${API_LEVEL:-36}"
PACKAGE_TYPE="google_apis_playstore"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
POLL_INTERVAL=5

# Pin AVD home so avdmanager and emulator agree on location.
# ANDROID_SDK_HOME (set by setup-android action) shifts the default search
# path; ANDROID_AVD_HOME wins over both, keeping creation and lookup in sync.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"

# ---------- Mode detection ----------
CI_MODE=false
for arg in "$@"; do
  [[ "$arg" == "--ci" ]] && CI_MODE=true
done
[[ "${CI:-}" == "true" ]] && CI_MODE=true

# ---------- Architecture detection ----------
HOST_ARCH=$(uname -m)
if [[ "$HOST_ARCH" == "arm64" || "$HOST_ARCH" == "aarch64" ]]; then
  ABI="arm64-v8a"
else
  ABI="x86_64"
fi
SYSTEM_IMAGE="system-images;android-${API_LEVEL};${PACKAGE_TYPE};${ABI}"

echo "==> Config: API=${API_LEVEL}, ABI=${ABI}, CI_MODE=${CI_MODE}"
echo "==> Image: ${SYSTEM_IMAGE}"

# ---------- Install system image ----------
echo "==> Accepting SDK licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true
echo "==> Installing system image..."
sdkmanager "${SYSTEM_IMAGE}"

# ---------- Create AVD (idempotent) ----------
if ! avdmanager list avd | grep -q "Name: ${AVD_NAME}"; then
  echo "==> Creating AVD: ${AVD_NAME}"
  echo "no" | avdmanager create avd \
    --name "${AVD_NAME}" \
    --package "${SYSTEM_IMAGE}" \
    --device "pixel_6" \
    --force
else
  echo "==> AVD '${AVD_NAME}' already exists, skipping creation."
fi

# ---------- Boot emulator ----------
EMULATOR_ARGS="-avd ${AVD_NAME} -no-snapshot-load -no-audio"
if $CI_MODE; then
  # Headless flags: use KVM if available (Linux), fall back to software rendering
  if [[ -e /dev/kvm ]]; then
    EMULATOR_ARGS+=" -no-window -no-boot-anim -gpu swiftshader_indirect -accel on"
  else
    EMULATOR_ARGS+=" -no-window -no-boot-anim -gpu swiftshader_indirect -accel off"
  fi
fi

echo "==> Starting emulator (CI_MODE=${CI_MODE})..."
export PATH="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}/emulator:$PATH"
emulator ${EMULATOR_ARGS} &
EMULATOR_PID=$!

# ---------- Wait for sys.boot_completed ----------
echo "==> Polling sys.boot_completed (timeout=${BOOT_TIMEOUT}s)..."
ELAPSED=0
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do
  if (( ELAPSED >= BOOT_TIMEOUT )); then
    echo "ERROR: Emulator did not boot within ${BOOT_TIMEOUT}s"
    kill "$EMULATOR_PID" 2>/dev/null || true
    exit 1
  fi
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "ERROR: Emulator process died unexpectedly"
    exit 1
  fi
  echo "   ... still booting (${ELAPSED}s elapsed)"
  sleep "$POLL_INTERVAL"
  ELAPSED=$(( ELAPSED + POLL_INTERVAL ))
done

echo "==> Boot completed in ${ELAPSED}s"

# ---------- Disable animations (CI only) ----------
if $CI_MODE; then
  echo "==> Disabling animations for stable UI tests..."
  adb shell settings put global window_animation_scale 0.0
  adb shell settings put global transition_animation_scale 0.0
  adb shell settings put global animator_duration_scale 0.0
fi

echo "==> Emulator ready. PID=${EMULATOR_PID}"
