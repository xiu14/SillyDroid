# Tavern Native Bridge

Thin SillyTavern extension for the Tavern Android shell.

It listens to SillyTavern generation events and forwards structured status
events to `window.AndroidBridge.postEvent(...)`. The Android shell uses these
events for Live Updates / Fluid Cloud, notifications, vibration, sound, and the
in-app foreground pill.

## Version

```text
0.1.3
```

`generation_started` is emitted after SillyTavern's
`GENERATION_AFTER_COMMANDS` event, so slash commands that interrupt generation
do not incorrectly show "thinking" in Tavern.

`generation_pending` is scheduled after the send-button input event returns, so
the Android bridge does not block SillyTavern from inserting the user message.

The chat view is scrolled to the latest message only once when the active chat
changes. Manual navigation such as `/chat-jump` cancels any pending initial
scroll attempts.

## Install

Copy this folder to your SillyTavern server:

```text
public/scripts/extensions/third-party/tavern-native-bridge
```

Then restart SillyTavern or reload the web page. If it does not load
automatically, enable `Tavern Native Bridge` from the Extensions panel.

## Events Sent To Android

```text
st_bridge_ready
generation_pending
generation_started
generation_streaming
generation_content_started
generation_ended
generation_stopped
```

The event payload includes:

```text
source
at
href
characterName
```

## Fallback

The Android shell still contains the old injected generation hook as a fallback.
It waits before installing the fallback hook. If this extension reports ready in
time, the shell does not inject the old hook.

This keeps old SillyTavern instances working while allowing newer instances to
use the cleaner extension-based bridge.
