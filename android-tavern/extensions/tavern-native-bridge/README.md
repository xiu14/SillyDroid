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

Status timeline:

- `generation_pending` ("preparing"): scheduled after the send-button input
  event returns, so the Android bridge does not block SillyTavern from
  inserting the user message. Also emitted on `GENERATION_AFTER_COMMANDS` for
  swipes / regenerations / continues, unless the send button already reported
  it for this round.
- `generation_started` ("thinking"): emitted when the response headers of the
  `/api/.../generate` request arrive, i.e. the first packet. SillyTavern's
  server forwards the upstream response only after the upstream answers, and
  the llm-gateway sends headers only after the first upstream chunk. The first
  `STREAM_TOKEN_RECEIVED` is a fallback. Slash commands that interrupt
  generation never reach this point.
- `generation_content_started` ("writing"): the streamed text contains a
  `<content>` tag.

The chat view is scrolled to the latest message every time SillyTavern renders
a chat (`CHAT_CHANGED`, including reloading the same chat). Manual navigation
such as `/chat-jump` or touching the page cancels any pending scroll attempts.

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
