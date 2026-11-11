# Screen control (opt-in)

Muse can tap, swipe, type and screenshot on this device — but only after
the user turns it on, and only one explicit command at a time.

This is a **generic primitive layer**, not an app driver. The gadget never
decides what to tap, never answers anything, and never acts on its own.
Muse reads the screen (`screen.read` / `screen.screenshot`), decides, then
sends one explicit tap. There is no quiz logic, no answer bank, no
auto-tapping and no auto-answering anywhere in this app.

## What it can do

| Command | Parameters | Result |
|---|---|---|
| `screen.tap` | `x` + `y` (pixels, 0..20000), **or** `node_index` from `screen.read` | `{"status": "dispatched", ...}` — verify with `screen.read` / `screen.screenshot` |
| `screen.tap_text` | `text` (1..200 chars) | `{"status": "tapped", "text": ...}` — exact match first, then case-insensitive, then contains |
| `screen.swipe` | `x1`, `y1`, `x2`, `y2`; optional `duration_ms` (50..5000, default 300) | `{"status": "dispatched", ...}` |
| `screen.type` | `text` (1..1000 chars); optional `node_index` | `{"status": "typed", "length": N}` — types into the focused field; tap the field first |
| `screen.read` | optional `max_nodes` (1..200, default 100) | `{"package", "count", "truncated", "nodes": [{index, text, content_description, class, package, bounds [l,t,r,b], center [x,y], clickable, editable, password, focused}]}` |
| `screen.screenshot` | none | `{"path": "screenshots/screen-YYYYMMDD-HHmmss.png", "width", "height", "size_bytes"}` — read it back with `file.read` (64 KiB chunks) |
| `screen.back` | none | `{"status": "dispatched", "action": "back"}` |
| `screen.home` | none | `{"status": "dispatched", "action": "home"}` |
| `app.switch` | `target` (app package, same as `tv.launch`) **or** `action: "recents"` | `{"status": "dispatched", ...}` for launch; `{"status": "dispatched", "action": "recents"}` for Recents |

`screen.tap` and `screen.swipe` report **dispatched**, like `tv.launch`:
Android's gesture API only confirms acceptance, not that the tap landed.
Always confirm with a fresh `screen.read` or `screen.screenshot` before
the next tap.

## Safety guards (in code, not just in docs)

- **Opt-in only.** Every `screen.*` / `app.switch` command fails until the
  user enables the AccessibilityService in Settings > Accessibility. The
  error message tells the user exactly where to go.
- **Password / login screens are refused.** Tap and type throw an error
  when the current screen holds a focused/editable password field.
  `screen.read` reports such text as `"[password]"`, never the real text.
- **No auto-tapping.** Scenes and automations cannot contain the
  screen-control commands (`screen.tap`, `screen.tap_text`, `screen.swipe`,
  `screen.type`, `screen.read`, `screen.screenshot`, `screen.back`,
  `screen.home`, `app.switch`) — `automation.put` rejects them
  (`androidtv/runtime.py`). Display commands (`screen.show` /
  `screen.clear` / `screen.status`) can still be used in scenes. There is
  no loop, poller or scheduler that taps on its own.
- **Screenshot is one still image, not recording.** It uses MediaProjection
  with a one-time system prompt. The grant lives only in memory and must
  be given again after an app restart. The PNG is saved only in the
  gadget workspace (`screenshots/…`) and is never uploaded by the app.
- **No notification listener, no root, no broad storage.** The service
  reads only the foreground window tree, on demand, for one command.

## How it is wired (same pattern as the existing commands)

Python owns the catalog: `androidtv/commands.py` `SPECS` declares each
command and its argument types. `Runtime._run` (`androidtv/runtime.py`)
sends any command in `SPECS` to the native bridge
(`DeviceBridge.invoke`). Kotlin owns the hardware:
`DeviceBridge.dispatch` handles `screen.tap`, `screen.tap_text`,
`screen.swipe`, `screen.type`, `screen.read`, `screen.back`,
`screen.home` and `app.switch` (reusing `TvControl.launch` for the
package case). `screen.screenshot` is special-cased in `DeviceBridge.invoke`
like `vision.analyze`: it runs on the Python worker thread, because its
ImageReader listener posts to the main looper and would deadlock on the
main thread.

- `ScreenControlAccessibilityService.kt` — the AccessibilityService:
  gestures (`dispatchGesture`), node clicks, `ACTION_SET_TEXT`, global
  actions, and the live window-tree walk. `canPerformGestures` is set in
  `res/xml/accessibility_service_config.xml`.
- `ScreenshotCapture.kt` — MediaProjection one-frame capture → PNG in
  the workspace. `ScreenshotRequestActivity.kt` — the transparent
  activity that shows the system Allow prompt (MediaProjection can only
  be requested from an Activity).
- `device.capabilities` now reports a `screen_control` object:
  `accessibility_enabled`, `screenshot_granted`, `overlay_granted`.

## Build (Android Studio, on a computer with the Android SDK)

The VM that wrote this code has no Java/Gradle/Android SDK, so this was
**not built or tested**. Build it on a computer (or in Android Studio):

```sh
git submodule update --init
./gradlew :app:assembleDebug   # JDK 17, Android SDK 35, Python 3.11 on PATH
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a 64-bit / 16 KiB device (OPPO A55 is arm64, so this is the right one
if the universal APK misbehaves), use the modern variant instead:

```sh
./gradlew -PmodernRuntime=true :app:assembleDebug   # needs Python 3.13
```

Install with `adb install -r` to update without uninstalling — uninstalling
erases the pairing and SDK token. If Android Studio reports a missing
symbol, check `docs/BUILD.md` first: JDK 17, SDK platform 35 and
`python3.11` (or 3.13 for modern) on PATH are the usual causes.

## Grant on the phone (OPPO A55, Android 13)

All four are in the app under **Device → Diagnostics & setup**. The
Diagnostics screen shows each one as `true` / `false` under Prerequisites:

1. **Overlay** — already granted (`overlay granted: true`). Tap **Grant
   overlay** if it ever says `false`; needed for `tv.launch` / `app.switch`
   from the background.
2. **Battery unrestricted** — tap **Battery settings** → choose
   **Unrestricted** (it showed `false` on 11 Nov 2026). Otherwise
   ColorOS may kill the gadget service in the background.
3. **Accessibility** — tap **Grant Accessibility** → Android opens
   Settings > Accessibility → Downloaded apps → **Muse Gadget
   Everywhere** → turn it **On** (on OPPO/ColorOS it may be under
   Settings > Additional settings > Accessibility). Until this is on,
   every `screen.*` command returns the enable instructions instead of
   acting.
4. **Screenshot** — tap **Grant Screenshot** → tap **Allow** on the
   system prompt. This grant must be given again after every app restart.

Then, in a Muse chat, test in this order:

```json
{"command": "device.capabilities", "params": {}}
{"command": "screen.read", "params": {}}
{"command": "screen.screenshot", "params": {}}
```

`device.capabilities` should show `screen_control.accessibility_enabled:
true` and `screenshot_granted: true`. `screen.read` lists the nodes on
the current screen; the screenshot PNG comes back through `file.read`
with path `screenshots/screen-….png`. Only then try one tap — for
example `screen.tap_text` on a visible button — and confirm with a fresh
`screen.read`.

## Known limits / compile risks (not verified — no SDK in this VM)

- Kotlin was written against the Android API by hand and re-read, but
  **never compiled**: a wrong import, a deprecated call, or a Chaquopy
  / Gradle mismatch can only be found by the first real build. The most
  likely trouble spots are `ScreenshotRequestActivity` (deprecated
  `startActivityForResult`), the `FOREGROUND_SERVICE_MEDIA_PROJECTION`
  permission (API 34+), and `AccessibilityServiceInfo` flag constants.
- `dispatchGesture` needs the app's AccessibilityService to be both
  enabled **and** connected; if Android has not bound it yet, the first
  tap can fail — retry after a few seconds.
- `screen.read` indices are only valid until the screen changes. If a
  tap opens a new screen, read again before the next tap.
- Screenshots fail when the screen is off or locked, and some apps
  (banking, DRM video) show a black frame by design — that is Android,
  not a bug in this code.
- The OPPO A55 runs Android 13 (SDK 33); the app targets SDK 35, which
  is the project's existing baseline, so no new target-SDK risk was
  introduced by this change.
