#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test-emulator.sh — build, boot, install, and exercise DeepGuard on an emulator
#
# What it does:
#   1. Builds the debug APK (using Android Studio's bundled JDK if found)
#   2. Starts an Android emulator (or reuses one already running)
#   3. Pushes 3 generated sample photos into the emulator gallery
#   4. Installs the APK
#   5. Exercises the GALLERY flow: taps "Gallery" -> picks a photo in the
#      system picker -> verifies "Analyze Image" becomes enabled
#   6. Exercises the CAMERA flow: taps "Camera" -> presses the shutter ->
#      confirms the shot -> verifies "Analyze Image" becomes enabled
#
# Usage:
#   scripts/test-emulator.sh [options]
#
# Options:
#   --avd <name>       AVD to boot (default: Medium_Phone)
#   --headless         Run the emulator without a window (CI-friendly)
#   --keep             Leave the emulator running when done
#   --clean            Uninstall the app before installing
#   --skip-build       Use the existing APK instead of rebuilding
#   --debug            Print the UI dumps while automating
#
# Exit codes:
#   0  all flows passed
#   1  hard failure (build / emulator / boot / install / gallery)
#   2  gallery passed but the camera flow could not be exercised
#
# Requirements: an Android SDK with emulator + platform-tools, JDK 17+ for the
# build, and a bash shell (Git Bash on Windows, bash on macOS/Linux).
# ─────────────────────────────────────────────────────────────────────────────

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ── Config ───────────────────────────────────────────────────────────────────
AVD_NAME="Medium_Phone"
HEADLESS=0
KEEP=0
CLEAN=0
SKIP_BUILD=0
DEBUG=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd)            AVD_NAME="$2"; shift 2 ;;
    --headless)       HEADLESS=1; shift ;;
    --keep)           KEEP=1; shift ;;
    --clean)          CLEAN=1; shift ;;
    --skip-build)     SKIP_BUILD=1; shift ;;
    --debug)          DEBUG=1; shift ;;
    -h|--help)        sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1 (try --help)"; exit 1 ;;
  esac
done

PACKAGE="com.shafi.deepfakedetector"
ACTIVITY=".MainActivity"
APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TMP="$PROJECT_ROOT/build/emulator-test"
PHOTOS_DIR="$TMP/photos"
GEN_PS1="$SCRIPT_DIR/gen_photos.ps1"
SERIAL=""
EMU_PID=""
STARTED_BY_US=""

mkdir -p "$TMP" "$PHOTOS_DIR"

log()  { echo "[$(date +%H:%M:%S)] $*"; }
fail() { log "FAIL: $*"; exit 1; }

# ── Locate the Android SDK ───────────────────────────────────────────────────
find_sdk() {
  local d
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then echo "$ANDROID_HOME"; return 0; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then echo "$ANDROID_SDK_ROOT"; return 0; fi
  if [[ -n "${LOCALAPPDATA:-}" && -d "$LOCALAPPDATA/Android/Sdk" ]]; then echo "$LOCALAPPDATA/Android/Sdk"; return 0; fi
  if [[ -d "$HOME/Android/Sdk" ]]; then echo "$HOME/Android/Sdk"; return 0; fi
  if [[ -f "$PROJECT_ROOT/local.properties" ]]; then
    d="$(grep '^sdk.dir' "$PROJECT_ROOT/local.properties" 2>/dev/null | head -1 | cut -d= -f2-)"
    if [[ -n "$d" ]]; then
      if command -v cygpath >/dev/null 2>&1; then d="$(cygpath -u "$d")"; fi
      if [[ -d "$d" ]]; then echo "$d"; return 0; fi
    fi
  fi
  return 1
}

# ── Locate a JDK 17+ (prefer Android Studio's bundled JBR) ──────────────────
find_java() {
  local p
  for p in \
    "/c/Program Files/Android/Android Studio/jbr" \
    "${LOCALAPPDATA:-}/Programs/Android Studio/jbr" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/usr/lib/jvm/default-java" \
    "/usr/lib/jvm/java-21-openjdk"; do
    if [[ -x "$p/bin/java" ]]; then echo "$p"; return 0; fi
  done
  if command -v java >/dev/null 2>&1; then
    local ver
    ver="$(java -version 2>&1 | head -1 | grep -oE '"1\.[0-9]+|"[0-9]+' | tr -d '"' | sed 's/^1\.//')"
    if [[ -n "$ver" && "$ver" -ge 17 ]]; then echo ""; return 0; fi
  fi
  return 1
}

SDK="$(find_sdk)"       || fail "Android SDK not found (set ANDROID_HOME or add sdk.dir to local.properties)"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
[[ -x "$ADB" ]]     || fail "adb not found at $ADB"
[[ -x "$EMULATOR" ]] || fail "emulator not found at $EMULATOR"
log "SDK: $SDK"

JAVA="$(find_java)" || fail "JDK 17+ not found (install one or set JAVA_HOME)"
if [[ -n "$JAVA" ]]; then export JAVA_HOME="$JAVA"; log "JDK: $JAVA"; fi

# On Git Bash (Windows), MSYS rewrites POSIX paths like /sdcard/... into
# Windows paths (C:/Program Files/Git/sdcard/...) before they reach adb,
# which breaks every device-side path. Disable that conversion for adb calls.
adb()       { MSYS_NO_PATHCONV=1 "$ADB" "$@"; }
adb_shell() { adb -s "$SERIAL" shell "$@"; }

# ── 1. Build ─────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == "1" ]]; then
  [[ -f "$APK" ]] || fail "APK not found ($APK) and --skip-build was given"
  log "Skipping build, using existing APK"
else
  log "Building debug APK..."
  ./gradlew assembleDebug --console=plain -q || fail "Gradle build failed"
  [[ -f "$APK" ]] || fail "APK missing after build: $APK"
fi
log "APK ready: $APK"

# ── 2. Start emulator (or reuse one already online) ─────────────────────────
SERIAL="$(adb devices | sed -n 's/^\(emulator-[0-9]*\)[[:space:]]*device$/\1/p' | head -1)"
if [[ -n "$SERIAL" ]]; then
  log "Reusing already-running emulator $SERIAL"
else
  log "Checking for AVD '$AVD_NAME'..."
  if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
    fail "AVD '$AVD_NAME' not found. Create one in Android Studio (Device Manager) or pass --avd <name>."
  fi
  local_flags=(-avd "$AVD_NAME" -no-snapshot-load -no-snapshot-save -no-audio -no-boot-anim)
  if [[ "$HEADLESS" == "1" ]]; then
    local_flags+=(-no-window -gpu swiftshader_indirect -memory 3072)
    log "Booting emulator '$AVD_NAME' (headless)..."
  else
    log "Booting emulator '$AVD_NAME'..."
  fi
  "$EMULATOR" "${local_flags[@]}" >"$TMP/emulator.log" 2>&1 &
  EMU_PID=$!
  STARTED_BY_US=1
  # wait for the device to appear in adb
  for _ in $(seq 1 60); do
    [[ -n "$(adb devices | sed -n 's/^\(emulator-[0-9]*\)[[:space:]]*device$/\1/p' | head -1)" ]] && break
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
      echo "--- emulator.log ---"; tail -20 "$TMP/emulator.log"; fail "emulator process died during startup"
    fi
    sleep 2
  done
  SERIAL="$(adb devices | sed -n 's/^\(emulator-[0-9]*\)[[:space:]]*device$/\1/p' | head -1)"
  [[ -n "$SERIAL" ]] || fail "emulator never appeared in adb"
fi

log "Waiting for boot to complete on $SERIAL..."
for i in $(seq 1 150); do
  if [[ -n "$EMU_PID" ]] && ! kill -0 "$EMU_PID" 2>/dev/null; then
    echo "--- emulator.log ---"; tail -20 "$TMP/emulator.log"; fail "emulator process died"
  fi
  st="$(adb -s "$SERIAL" get-state 2>/dev/null | tr -d '\r')"
  if [[ "$st" == "device" ]]; then
    boot="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [[ "$boot" == "1" ]] && { log "Boot completed"; break; }
  fi
  sleep 2
done
boot="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
[[ "$boot" == "1" ]] || fail "emulator did not finish booting in time"

# Speed up UI automation and make sure the screen is on
adb_shell settings put global window_animation_scale 0   >/dev/null 2>&1
adb_shell settings put global transition_animation_scale 0 >/dev/null 2>&1
adb_shell settings put global animator_duration_scale 0  >/dev/null 2>&1
adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb_shell wm dismiss-keyguard >/dev/null 2>&1

# ── 3. Sample photos ─────────────────────────────────────────────────────────
log "Generating sample photos..."
if command -v cygpath >/dev/null 2>&1; then
  photos_win="$(cygpath -w "$PHOTOS_DIR")"
else
  photos_win="$PHOTOS_DIR"
fi
powershell -NoProfile -ExecutionPolicy Bypass -File "$GEN_PS1" -OutDir "$photos_win" >/dev/null 2>&1 \
  || fail "failed to generate sample photos (PowerShell + System.Drawing required on Windows)"
adb_shell mkdir -p /sdcard/Pictures/DeepGuard || true
for f in "$PHOTOS_DIR"/*.png; do
  if command -v cygpath >/dev/null 2>&1; then
    local_win="$(cygpath -w "$f")"
  else
    local_win="$f"
  fi
  adb -s "$SERIAL" push "$local_win" /sdcard/Pictures/DeepGuard/ >/dev/null 2>&1 \
    || fail "could not push $f to the emulator"
done
adb_shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1 \
  || adb_shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
       -d file:///sdcard/Pictures/DeepGuard/sample1.png >/dev/null 2>&1
sleep 3
log "Sample photos pushed to the emulator gallery"

# ── 4. Install ───────────────────────────────────────────────────────────────
if [[ "$CLEAN" == "1" ]]; then
  adb -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
  log "Uninstalled previous copy"
fi
log "Installing APK..."
if command -v cygpath >/dev/null 2>&1; then
  apk_win="$(cygpath -w "$APK")"
else
  apk_win="$APK"
fi
adb -s "$SERIAL" install -r "$apk_win" || fail "adb install failed"
log "Installed. Launching $PACKAGE/$ACTIVITY"
adb_shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1 || fail "could not launch app"
sleep 3

# ── UI automation helpers ────────────────────────────────────────────────────
UI_XML="$TMP/ui.xml"

dump_ui() {
  local i
  for i in 1 2 3 4 5; do
    adb_shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
    adb -s "$SERIAL" exec-out cat /sdcard/window_dump.xml > "$UI_XML" 2>/dev/null
    if grep -q '<node' "$UI_XML" 2>/dev/null; then
      [[ "$DEBUG" == "1" ]] && { log "--- UI dump ---"; sed 's/></>\n</g' "$UI_XML" | grep '<node' | head -40; log "--- end dump ---"; }
      return 0
    fi
    sleep 2
  done
  return 1
}

node_by_id()   { grep -oE "<node[^>]*resource-id=\"[^\"]*$1[^\"]*\"[^>]*>" "$UI_XML" | head -1; }
node_by_text() { grep -oE "<node[^>]*text=\"[^\"]*$1[^\"]*\"[^>]*>" "$UI_XML" | head -1; }
bounds_of()    { echo "$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | sed 's/bounds="//; s/"//'; }

bounds_center() { # "10,20 30,40" or "[10,20][30,40]"
  local b="${1//[\[\]]/ }"
  local x1 y1 x2 y2
  IFS=', ' read -r x1 y1 x2 y2 <<< "$b"
  echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
}

bounds_area() {
  local b="${1//[\[\]]/ }"
  local x1 y1 x2 y2
  IFS=', ' read -r x1 y1 x2 y2 <<< "$b"
  echo "$(( (x2 - x1) * (y2 - y1) ))"
}

tap_center() { # "x y"
  adb_shell input tap "$1" "$2"
  [[ "$DEBUG" == "1" ]] && log "tapped $1,$2"
  sleep 1
}

tap_by_id() { # <resource-id substring>
  local node b cx cy
  if dump_ui && node="$(node_by_id "$1")" && [[ -n "$node" ]] && b="$(bounds_of "$node")" && [[ -n "$b" ]]; then
    read -r cx cy <<< "$(bounds_center "$b")"
    tap_center "$cx" "$cy"
    return 0
  fi
  return 1
}

# Slow emulators (especially headless) sometimes throw ANR dialogs such as
# "System UI isn't responding" that cover the app. Dismiss them by tapping
# the "Wait" button so the test can proceed.
dismiss_dialogs() {
  local node b cx cy i
  for i in 1 2 3; do
    dump_ui || return 0
    # ANR dialog: "Wait"  /  cloud-media dialog: "Dismiss"
    node="$(grep -oE '<node[^>]*resource-id="android:id/aerr_wait"[^>]*>' "$UI_XML" | head -1)"
    if [[ -z "$node" ]]; then
      node="$(grep -oiE '<node[^>]*text="(Wait|Dismiss)"[^>]*>' "$UI_XML" | head -1)"
    fi
    [[ -z "$node" ]] && return 0
    b="$(bounds_of "$node")"
    [[ -z "$b" ]] && return 0
    read -r cx cy <<< "$(bounds_center "$b")"
    tap_center "$cx" "$cy"
    log "Dismissed a system dialog"
    sleep 2
  done
}

# Reset the app to a clean state and bring it to the foreground. force-stop
# also closes anything stacked on top, and killing the photo-picker processes
# clears windows/dialogs they own (e.g. "Choose cloud media app") that would
# otherwise cover the app even after our package is restarted.
bring_app_to_front() {
  adb_shell am force-stop "$PACKAGE" >/dev/null 2>&1
  adb_shell am force-stop com.google.android.photopicker >/dev/null 2>&1
  adb_shell am force-stop com.google.android.providers.media.module >/dev/null 2>&1
  sleep 1
  adb_shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1
  sleep 2
}

# true if btnAnalyze exists and is enabled in the app
analyze_enabled() {
  local node
  dump_ui || return 1
  node="$(node_by_id "btnAnalyze")"
  [[ -n "$node" && "$node" == *'enabled="true"'* ]]
}

# Locate a tappable photo thumbnail in the system photo picker; prints its
# bounds (or nothing). Different Android versions expose the grid differently,
# so we try several structures.
find_picker_photo() {
  local best="" best_area=0 line b a
  # Modern picker: photos carry a content-desc like "Photo taken on ..."
  while IFS= read -r line; do
    b="$(bounds_of "$line")"; [[ -z "$b" ]] && continue
    a="$(bounds_area "$b")"
    if (( a > best_area )); then best_area=$a; best="$line"; fi
  done < <(grep -oE '<node[^>]*content-desc="Photo[^"]*"[^>]*>' "$UI_XML")
  # clickable cells in the picker grid
  if [[ -z "$best" ]]; then
    while IFS= read -r line; do
      b="$(bounds_of "$line")"; [[ -z "$b" ]] && continue
      a="$(bounds_area "$b")"
      if (( a > best_area )); then best_area=$a; best="$line"; fi
    done < <(grep -oE '<node[^>]*class="android.view.View"[^>]*clickable="true"[^>]*>' "$UI_XML")
  fi
  # classic picker with ImageView thumbnails
  if [[ -z "$best" ]]; then
    while IFS= read -r line; do
      b="$(bounds_of "$line")"; [[ -z "$b" ]] && continue
      a="$(bounds_area "$b")"
      if (( a > best_area )); then best_area=$a; best="$line"; fi
    done < <(grep -oE '<node[^>]*class="android.widget.ImageView"[^>]*>' "$UI_XML")
  fi
  if [[ -n "$best" && "$best_area" -gt 20000 ]]; then
    bounds_of "$best"
  fi
}

# After tapping a photo, newer pickers show a bottom bar with a confirm
# control (often an unlabeled "Add"). Tap it (or "Add"/"Done" text) until
# we are back in the app with Analyze enabled.
confirm_picker_selection() {
  local node b cx cy x1 y1 x2 y2 maxx rightmost h i
  h="$(adb_shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | grep -oE '[0-9]+$')"
  [[ -z "$h" ]] && h=2400
  for i in 1 2 3 4 5; do
    sleep 1
    analyze_enabled && return 0
    dump_ui || continue
    # labeled confirm buttons (must START with Add/Done — otherwise the
    # selected photo's content-desc like "Selected Photo..." matches)
    node="$(grep -oiE '<node[^>]*(text|content-desc)="(Add|Done)[^"]*"[^>]*>' "$UI_XML" | head -1)"
    if [[ -n "$node" ]]; then
      b="$(bounds_of "$node")"
      if [[ -n "$b" ]]; then
        read -r cx cy <<< "$(bounds_center "$b")"
        tap_center "$cx" "$cy"
        log "Tapped confirm button in the picker"
        continue
      fi
    fi
    # unlabeled confirm: rightmost short clickable control in the bottom bar
    rightmost=""; maxx=0
    while IFS= read -r line; do
      b="$(bounds_of "$line")"; [[ -z "$b" ]] && continue
      IFS=', ' read -r x1 y1 x2 y2 <<< "${b//[\[\]]/ }"
      # bottom fifth of the screen and bar-button height (photos are taller)
      if (( y1 > h * 80 / 100 )) && (( (y2 - y1) < 300 )); then
        if (( x2 > maxx )); then maxx=$x2; rightmost="$b"; fi
      fi
    done < <(grep -oE '<node[^>]*clickable="true"[^>]*>' "$UI_XML")
    if [[ -n "$rightmost" ]]; then
      read -r cx cy <<< "$(bounds_center "$rightmost")"
      tap_center "$cx" "$cy"
      log "Tapped the picker's bottom-bar confirm"
    fi
  done
  return 1
}

# ── 5. Gallery flow ──────────────────────────────────────────────────────────
log "── Gallery flow ──"
dismiss_dialogs
bring_app_to_front
if ! tap_by_id "btnGallery"; then
  fail "could not find the Gallery button (is the app showing?)"
fi
log "Gallery tapped — waiting for the photo picker..."

GALLERY_OK=0
for attempt in $(seq 1 3); do
  # phase 1: find and tap a photo in the picker
  picked=0
  for _ in $(seq 1 4); do
    if dump_ui; then
      # The picker occasionally shows a "Choose cloud media app" dialog on
      # Play-enabled images — dismiss it before looking for photos.
      dismiss_btn="$(node_by_text "Dismiss")"
      if [[ -n "$dismiss_btn" ]]; then
        read -r cx cy <<< "$(bounds_center "$(bounds_of "$dismiss_btn")")"
        tap_center "$cx" "$cy"
        log "Dismissed 'Choose cloud media app' dialog"
        continue
      fi
      b="$(find_picker_photo)"
      if [[ -n "$b" ]]; then
        read -r cx cy <<< "$(bounds_center "$b")"
        tap_center "$cx" "$cy"
        log "Tapped photo in the picker"
        picked=1
        break
      fi
      # fallback: the picker may need the Photos tab opened first
      tab="$(node_by_text "Photos")"
      if [[ -z "$tab" ]]; then tab="$(node_by_text "Recent")"; fi
      if [[ -n "$tab" ]]; then
        read -r cx cy <<< "$(bounds_center "$(bounds_of "$tab")")"
        tap_center "$cx" "$cy"
        log "Opened Photos tab"
      fi
    fi
    sleep 2
  done
  [[ "$picked" == "1" ]] || break
  # phase 2: confirm the selection (newer pickers need an explicit "Add")
  if confirm_picker_selection; then
    GALLERY_OK=1
    break
  fi
  log "Photo selection not confirmed, retrying..."
  bring_app_to_front
  tap_by_id "btnGallery" || fail "could not reopen the photo picker"
  sleep 3
done

if [[ "$GALLERY_OK" == "1" ]]; then
  log "PASS: gallery flow — photo selected and Analyze enabled"
  GALLERY_RESULT="PASS"
else
  fail "could not complete the gallery flow (no photo selected in the picker)"
fi

# ── 6. Camera flow ───────────────────────────────────────────────────────────
log "── Camera flow ──"
dismiss_dialogs
bring_app_to_front
if ! tap_by_id "btnCamera"; then
  fail "could not find the Camera button"
fi
log "Camera tapped — waiting for the camera app..."

CAMERA_OK=0
for _ in $(seq 1 8); do
  if dump_ui; then
    shutter="$(grep -oiE '<node[^>]*(resource-id="[^"]*shutter[^"]*"|content-desc="[^"]*shutter[^"]*")[^>]*>' "$UI_XML" | head -1)"
    if [[ -n "$shutter" ]]; then
      read -r cx cy <<< "$(bounds_center "$(bounds_of "$shutter")")"
      tap_center "$cx" "$cy"
      log "Pressed the camera shutter"
      CAMERA_OK=1
      break
    fi
  fi
  sleep 2
done

if [[ "$CAMERA_OK" == "1" ]]; then
  sleep 3
  # confirm / save the shot if the camera app shows a confirmation
  confirmed=0
  for _ in $(seq 1 5); do
    if dump_ui; then
      node=""
      for pat in confirm done ic_done check; do
        node="$(node_by_id "$pat")"
        [[ -n "$node" ]] && break
      done
      if [[ -z "$node" ]]; then
        node="$(grep -oiE '<node[^>]*content-desc="[^"]*(Done|Confirm|OK)[^"]*"[^>]*>' "$UI_XML" | head -1)"
      fi
      if [[ -n "$node" ]]; then
        read -r cx cy <<< "$(bounds_center "$(bounds_of "$node")")"
        tap_center "$cx" "$cy"
        log "Confirmed the photo"
        confirmed=1
        break
      fi
    fi
    sleep 2
  done
  if [[ "$confirmed" == "0" ]]; then
    log "No confirm button found — pressing BACK to return to the app"
    adb_shell input keyevent KEYCODE_BACK
  fi
  sleep 3
  if analyze_enabled; then
    log "PASS: camera flow — photo captured and Analyze enabled"
    CAMERA_RESULT="PASS"
  else
    log "WARN: camera app ran but no photo was captured (Analyze still disabled)"
    CAMERA_RESULT="WARN"
  fi
else
  log "WARN: could not locate the camera shutter button (camera UI varies by image)"
  CAMERA_RESULT="WARN"
fi

# ── Summary ──────────────────────────────────────────────────────────────────
log ""
log "═══════════════════════════════════════════════"
log " DeepGuard emulator test summary"
log "   Device   : $SERIAL"
log "   Gallery  : $GALLERY_RESULT"
log "   Camera   : $CAMERA_RESULT"
log "═══════════════════════════════════════════════"
log ""

if [[ "$KEEP" == "1" ]]; then
  log "Emulator left running (--keep). Stop it later with: adb -s $SERIAL emu kill"
elif [[ -n "$STARTED_BY_US" ]]; then
  adb -s "$SERIAL" emu kill >/dev/null 2>&1
  log "Emulator stopped."
fi

if [[ "$GALLERY_RESULT" != "PASS" ]]; then exit 1; fi
if [[ "$CAMERA_RESULT" != "PASS" ]]; then exit 2; fi
exit 0