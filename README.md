# TurboVoice — system-wide voice-to-text for the Kyocera DuraXV Extreme (E4810)

Press this phone's dedicated **Mic/Assistant button** from inside any
app's text field, speak, release — the transcribed text gets pasted
right there. Works across any app, not just TurboText, because it's
built as an Accessibility Service rather than a keyboard or a feature of
any one app.

Modeled on Google's own **Voice Access** app — disassembling it showed
it's built the same underlying way this is (an AccessibilityService using
`findFocus`/`ACTION_SET_TEXT`-family actions to read and write whatever
field is focused, in whatever app is in front). What's different: Voice
Access's own UI is a floating button you tap, and this phone has no
touchscreen at all, so that part was replaced with a hardware-key press
instead — the same `onKeyEvent` pattern already proven reliable in
TurboText's own `KeyButtonAccessibilityService`.

**Why this specific button:** it's a separate physical button from both
Volume Up and the actual PTT button, built for a voice-assistant feature
that isn't otherwise well-suited to what's actually needed here — a good
candidate to repurpose. Nothing else on this device reacts to it, and
TurboText's own accessibility service listens for the *PTT* button
specifically (to flash the outer-screen icon) — a different button
entirely.

TurboText does, however, have its own built-in voice-to-text (its
Soft-Right key, inside Compose/Conversation/etc.), so TurboVoice checks
`rootInActiveWindow`'s package name on every mic-button press and leaves
the button completely alone (doesn't consume it, doesn't record) whenever
TurboText (`com.turbotext.app`) is the app currently on screen — avoids
the two apps fighting over `RECORD_AUDIO`/the focused field at once.

**Feedback:** a short vibration confirms the button press actually
registered — one pulse when recording starts, another when the
transcribed text is successfully dropped into the field.

## Setup

1. Build and install the app. On first launch it creates an empty
   `groq_api_key.txt` file in a `Turbo Key` folder in Internal storage
   (needs storage access granted first — see below).
2. **Groq API key** — get one free at https://console.groq.com/keys, then
   open `Turbo Key/groq_api_key.txt` (in Internal storage, via any file
   manager) and paste it in, nothing else in the file. The same key works
   for both TurboVoice and TurboText if you use both.
3. Open TurboVoice from the app list. It shows a live checklist of what's
   still needed:
   - **Microphone permission** — tap "Grant Microphone Permission".
     Required for recording at all.
   - **Accessibility service enabled** — tap "Open Accessibility Settings"
     from the app, find TurboVoice in the list, turn it on. Required for
     voice input to work at all.
   - **Overlay permission** — tap "Open Overlay Permission Settings"
     (opens the Default apps settings screen — overlay permission is
     under Special app access > Display over other apps from there). On
     API 26+ this usually isn't needed at all — the status overlay uses
     `TYPE_ACCESSIBILITY_OVERLAY`, which an accessibility service is
     allowed to show without this permission. It only matters as a
     fallback on older Android versions. Either way, it's just the small
     "Listening…" indicator — voice input itself works without it.
   - **Storage access** — tap "Grant Storage Access". Needed so the app
     can read the API key file above.

### Remote key provisioning (optional)

Instead of editing the file by hand, the API key can also be set by
sending a specially-formatted text message — same idea as TurboText's own
provisioning texts:

1. Grant SMS permission and set a trusted number from the app (the button
   "Grant SMS Permission" and "Set Trusted Provisioning Number") — only
   texts from that exact number are ever treated as a provisioning command.
2. Base64-encode the key, e.g. `echo -n "gsk_..." | base64`.
3. Text `TURBOVOICE_SETUP:<encoded key>` from the trusted number.

One real caveat: unlike TurboText (which, as the default SMS app, can
quietly swallow its own provisioning texts before they hit the inbox),
TurboVoice has no control over the default SMS app and can't suppress
this message — the base64 text will still show up as a normal received
message in whatever app handles texting on the device (e.g. TurboText).

## How to use it

Tap into any text field, in any app — a text message, a note, a search
box, anything. Press and hold the **Mic/Assistant button**, speak,
release. The transcription is pasted in at the cursor position — not
appended to the end, at the cursor specifically, so it lands correctly
even if you were editing partway through existing text.

## Known limitations

- **Volume Up was tried first and abandoned.** A real device test showed
  it pops up the phone's own volume UI, which becomes the focused window
  itself (with no editable field) — stealing focus away from whatever was
  actually focused a moment before. The Mic/Assistant button doesn't have
  this problem.
- **Requires a field to already be focused** before pressing the
  Mic/Assistant button — it won't tap into a field for you first. If
  nothing's focused, the status overlay says so and nothing records.
- **Every app renders text fields somewhat differently.** The
  accessibility text-insertion actions used here are the standard,
  correct API for this, but a few apps (especially ones with heavily
  custom text-editing views) may not honor them the same way a plain
  `EditText` does. Not something to assume is broken without testing
  against the specific app first.
- A sibling project on this same hardware (VoiceTyper) reportedly already
  confirmed this general approach works — but this specific app, with its
  own specific keycodes and clipboard-paste mechanism, was confirmed
  working end-to-end on real hardware as of this build. Worth retesting
  after any future changes rather than assuming it stays working.
