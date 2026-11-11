"""One catalog for cloud registration, local controls and validation."""
from copy import deepcopy
import json
import math
from musegadget.executor import COMMAND_SPECS as _UPSTREAM
_BASE = deepcopy(_UPSTREAM)


def field(kind, description):
    return {"type": kind, "description": description}


def spec(description, required=None, optional=None, timeout=30000):
    return {"description": description, "required": required or {},
            "optional": optional or {}, "timeout_ms": timeout}


TEXT = field("string", "Text.")
NAME = field("string", "Name or identifier.")
URL = field("string", "HTTP(S) media URL or a relative workspace file path.")
SPECS = {
    "device.capabilities": spec("Discover this Android device's features, permission readiness and workspace."),
    "device.status": spec("Actual Muse connection state, pending events and recent command outcomes; no credentials."),
    "apps.list": spec("List launchable apps with their human names and package identifiers."),
    "screen.show": spec("Show a card on this device. Buttons produce screen.action events. Requires foreground or overlay grant.",
                        optional={"title": TEXT, "text": TEXT, "image": URL,
                                  "buttons": field("array", "Up to 6 objects: id, label."),
                                  "ttl_s": field("integer", "Hide after 1..86400 seconds; omit to keep open.")}),
    "screen.clear": spec("Close the current content screen."),
    "screen.status": spec("Read what the content screen actually rendered."),
    # Screen control (opt-in AccessibilityService + MediaProjection).
    # Generic single-shot primitives only: Muse reads the screen, then sends
    # ONE explicit tap/type per command. No quiz logic, no answer bank, no
    # auto-tapping, and scenes/automations cannot contain these commands.
    # Tap/type are refused on password / login screens; password text is
    # redacted in screen.read. screen.tap reports "dispatched", like
    # tv.launch — verify with screen.read or screen.screenshot.
    "screen.tap": spec("Tap one screen point, or one node from screen.read. Needs x+y together, or node_index.",
                       optional={"x": field("integer", "Screen x in pixels, 0..20000."),
                                 "y": field("integer", "Screen y in pixels, 0..20000."),
                                 "node_index": field("integer", "Index from screen.read.")}),
    "screen.tap_text": spec("Tap the first on-screen node whose text matches exactly (then contains).",
                            {"text": TEXT}),
    "screen.swipe": spec("Swipe from one point to another.",
                         {"x1": field("integer", "Start x in pixels, 0..20000."),
                          "y1": field("integer", "Start y in pixels, 0..20000."),
                          "x2": field("integer", "End x in pixels, 0..20000."),
                          "y2": field("integer", "End y in pixels, 0..20000.")},
                         {"duration_ms": field("integer", "Swipe duration 50..5000 ms, default 300.")}),
    "screen.type": spec("Type text into the focused field, or into the node at node_index. Tap the field first.",
                        {"text": TEXT},
                        {"node_index": field("integer", "Index from screen.read.")}),
    "screen.read": spec("List visible on-screen nodes with text, bounds and clickable flag. Password text is redacted.",
                        optional={"max_nodes": field("integer", "How many nodes to list, 1..200, default 100.")}),
    "screen.screenshot": spec("Capture one still screenshot (user-granted MediaProjection) and save it to the workspace. Read it back with file.read.",
                              timeout=45000),
    "screen.back": spec("Press Android Back."),
    "screen.home": spec("Press Android Home."),
    "app.switch": spec("Switch app: launch by package (same as tv.launch) or open Recents.",
                       optional={"target": NAME,
                                 "action": field("string", "recents")}),
    "media.play": spec("Play audio/video on this Android device without needing a Cast receiver. Returns a job and observed player state.",
                       {"url": URL}, {"title": TEXT, "mime": TEXT,
                                      "queue": field("array", "Additional media URL strings, max 50."),
                                      "subtitle": URL}),
    "media.control": spec("Control our own native player; does not control another app.",
                          {"action": field("string", "pause, resume, stop, next, previous, seek, volume.")},
                          {"value": field("number", "seek: seconds; volume: 0..1.")}),
    "media.status": spec("Read observed native player state, position, queue and playback errors."),
    "speech.say": spec("Speak text using the installed Android TTS engine. Returns a job; speech.status confirms completion.",
                       {"text": TEXT}, {"language": field("string", "BCP-47 language tag, e.g. zh-TW or en-US."),
                                        "rate": field("number", "Speech rate 0.5..2.0.")}),
    "speech.stop": spec("Stop speaking."),
    "speech.status": spec("TTS initialization, supported current voice and observed utterance state."),
    "voice.listen": spec("Open the visible push-to-talk screen. Recording starts only when the user taps Listen."),
    "camera.capture": spec("Open the visible camera screen. The user grants permission and taps Take photo; files stay in workspace."),
    "vision.analyze": spec("Offline bundled OCR and barcode recognition of a workspace image; no image upload.",
                           {"path": field("string", "Relative workspace image path.")},
                           {"mode": field("string", "text, barcode, or both (default).")}, 45000),
    "sensors.read": spec("Read battery, power, network and available ambient/motion sensor samples. Unsupported values stay absent."),
    "cast.discover": spec("Discover Cast receivers on the LAN, with stable IDs and friendly names.", timeout=15000),
    "cast.devices": spec("List remembered Cast devices and room names."),
    "cast.name": spec("Assign a friendly name/room to an already discovered Cast receiver.",
                      {"id": NAME, "name": NAME}, {"room": NAME}),
    "events.list": spec("Read recent local events and delivery state; does not publish them."),
    "events.emit": spec("Create a local event. Notify is opt-in, and expires rather than sending a stale message.",
                        {"type": NAME}, {"data": field("object", "Event fields."),
                                         "notify": field("boolean", "Forward to Muse if locally enabled. Default false."),
                                         "ttl_s": field("integer", "Expiry 1..86400 seconds, default 300.")}),
    "automation.list": spec("List saved local scenes and event/interval rules."),
    "automation.put": spec("Save an offline scene or rule. Only bounded display/media/speech and locally-enabled home actions are allowed.",
                           {"id": NAME, "actions": field("array", "1..10 objects: command and params.")},
                           {"event": NAME, "interval_s": field("integer", "At least 60 seconds. No catch-up execution."),
                            "match": field("object", "Exact event fields to match."),
                            "cooldown_s": field("integer", "Minimum seconds between event runs, default 30."),
                            "enabled": field("boolean", "Default true.")}),
    "automation.run": spec("Run one saved scene locally.", {"id": NAME}, timeout=120000),
    "automation.delete": spec("Remove one saved scene/rule.", {"id": NAME}),
    "home.status": spec("Home Assistant connection/readiness, without credentials."),
    "home.states": spec("Read entities from the Home Assistant configured locally.", optional={"entity_id": NAME}),
    "home.call": spec("Call a Home Assistant action. Local settings must allow actions; explicit entity IDs required.",
                      {"domain": NAME, "service": NAME, "entity_id": field("string", "One entity ID or comma-separated IDs.")},
                      {"data": field("object", "Additional action data.")}),
    "mqtt.status": spec("Read configured MQTT connection/subscription state."),
    "mqtt.publish": spec("Publish beneath the locally configured topic prefix, only when local settings allow publishing.",
                         {"topic": NAME, "payload": TEXT}),
    "media.cache": spec("Download an HTTP(S) resource into the workspace for offline playback; maximum 64 MiB.",
                        {"url": field("string", "HTTP(S) URL without embedded credentials."),
                         "path": field("string", "Relative workspace destination.")}, timeout=120000),
}


def catalog(developer=False):
    from androidtv.tv import TV_COMMAND_SPECS
    result = deepcopy({k: v for k, v in _BASE.items()
                       if k in ("system.run", "file.read", "file.write", "device.health")})
    result.update(deepcopy(TV_COMMAND_SPECS))
    result.update(deepcopy(SPECS))
    result["tv.launch"]["description"] = "Launch an Android app by package or open an HTTP(S) URL. Reports dispatch, not visibility."
    result["tv.cast"]["optional"]["device"] = field("string", "Remembered receiver ID, friendly name, or room; use cast.discover first.")
    for name in ("file.read", "file.write"):
        result[name]["required"]["path"]["description"] = "Path inside the device workspace. Relative paths preferred; no symlinks."
    result["system.run"]["description"] = "TRUSTED DEVELOPER MODE ONLY: Android /system/bin/sh as app UID, with access to app credentials."
    if not developer:
        result.pop("system.run")
    return result


def validate(command, params, specs):
    if not isinstance(params, dict):
        raise ValueError("params must be an object")
    if len(json.dumps(params, allow_nan=False).encode()) > 128 * 1024:
        raise ValueError("request exceeds 128 KiB")
    entry = specs.get(command)
    if entry is None:
        raise ValueError("unsupported command")
    required, optional = entry["required"], entry["optional"]
    if set(required) - params.keys():
        raise ValueError("missing: " + ", ".join(sorted(set(required) - params.keys())))
    if params.keys() - (required.keys() | optional.keys()):
        raise ValueError("unknown parameters")
    types = {"string": str, "integer": int, "number": (int, float), "boolean": bool,
             "object": dict, "array": list}
    for key, value in params.items():
        kind = (required.get(key) or optional[key])["type"]
        if not isinstance(value, types[kind]) or (kind in ("integer", "number") and isinstance(value, bool)):
            raise ValueError(f"{key} must be {kind}")
        if isinstance(value, float) and not math.isfinite(value):
            raise ValueError(f"{key} must be finite")
