package com.turbovoice.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/** System-wide voice-to-text: hold a hardware key from inside any app,
 *  anywhere, and whatever's said gets transcribed (via Groq's Whisper
 *  API, same as TurboText's own voice input) and inserted into whichever
 *  text field currently has focus in that app.
 *
 *  Modeled on Google's own Voice Access app — disassembling it confirmed
 *  it's built the same underlying way this is: an AccessibilityService
 *  using findFocus(FOCUS_INPUT) + ACTION_SET_TEXT to read and write the
 *  focused field in whatever app is in front, regardless of which app
 *  that is. That part transfers directly.
 *
 *  What doesn't transfer: Voice Access's own UI is a floating button you
 *  tap to start listening. This phone has no touchscreen at all, so a
 *  tap target is a non-starter here, the same reason FlipText/TurboText
 *  couldn't use anything Google or Kyocera built with touch in mind
 *  either. This uses a hardware key instead — the exact same onKeyEvent
 *  pattern TurboText's own KeyButtonAccessibilityService already proved
 *  reliable on this device.
 *
 *  Trigger: entirely user-selectable via MainActivity's "Set Trigger
 *  Key" button (see TriggerKeyStorage / KeyCaptureActivity, ported from
 *  TurboLaunch's own shortcut-key picker). No key is hardcoded and
 *  nothing is intercepted until the user picks one — onKeyEvent below
 *  is a no-op with no trigger key set. Steer users away from VOLUME_UP
 *  specifically: a real device log showed "no focused editable field —
 *  not starting recording" on every attempt, because pressing it pops
 *  up the phone's own volume UI, which apparently becomes the focused
 *  window itself (it has no editable field, so
 *  findFocusedEditableNode() correctly comes up empty), stealing focus
 *  away from whatever field was actually focused a moment earlier. This
 *  device's dedicated Mic/Assistant button (KEYCODE_F4 /
 *  KEYCODE_SPEAKER_IN) doesn't have that problem and is a good
 *  suggested default for users to pick, since nothing else on this
 *  device reacts to it and TurboText's own accessibility service
 *  listens for PTT specifically, not this button — but it's just a
 *  suggestion, not special-cased in code. Safe to consume outright and
 *  start recording immediately on press rather than requiring a
 *  long-press debounce, since there's no competing normal function to
 *  accidentally interrupt. Confirmed working already in this same
 *  repo's sibling VoiceTyper app on this exact hardware. */
class TurboVoiceAccessibilityService : AccessibilityService() {

    private var isRecording = false
    private var voiceHelper: GroqVoiceInputHelper? = null
    private var windowManager: WindowManager? = null
    private var statusOverlay: View? = null
    private var vibrator: Vibrator? = null

    // Set when the lead-in space (see startRecording) was actually pasted,
    // so an error/no-speech result knows to clean it back out instead of
    // leaving a stray space sitting in the field.
    private var pastedLeadingSpace = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        voiceHelper = GroqVoiceInputHelper(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator

        // Set programmatically as well as via the XML config — belt and
        // suspenders, since this OEM's Android build has been inconsistent
        // about honoring manifest-declared accessibility flags reliably
        // (same lesson learned building VoiceTyper on this same device).
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
        Log.i(TAG, "TurboVoice accessibility service connected, watching for keycode(s) ${currentTriggerDescription()}")
    }

    /** True if [event] is the user's chosen trigger key. False (never
     *  triggers) if the user hasn't set one yet — there is no fallback
     *  default key. */
    private fun isTriggerKeyEvent(event: KeyEvent): Boolean {
        val triggerKeycode = TriggerKeyStorage.getTriggerKeycode(this) ?: return false
        return event.keyCode == triggerKeycode
    }

    private fun currentTriggerDescription(): String {
        val triggerKeycode = TriggerKeyStorage.getTriggerKeycode(this)
        return if (triggerKeycode != null) "keycode $triggerKeycode" else "none set"
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isTriggerKeyEvent(event)) return super.onKeyEvent(event)
        if (isTurboTextForeground()) {
            // TurboText has its own built-in voice-to-text (its Soft-Right
            // key). Leaving this button completely alone while TurboText is
            // the app on screen avoids the two apps fighting over
            // RECORD_AUDIO and the focused field at the same time.
            Log.i(TAG, "TurboText is foreground — not intercepting mic button")
            return super.onKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && !isRecording) {
                    startRecording()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (isRecording) {
                    stopRecordingAndInsert()
                }
            }
        }
        // Consume the key either way — this both stops it reaching
        // whatever app is focused AND stops the button's normal
        // (not-great-anyway) voice-assistant function, which is what
        // makes it safe to repurpose.
        return true
    }

    private fun startRecording() {
        val focused = findFocusedEditableNode()
        if (focused == null) {
            Log.i(TAG, "no focused editable field — not starting recording")
            showStatusOverlay("No text field selected", autoHideMs = 1200)
            return
        }
        isRecording = voiceHelper?.startRecording() ?: false
        if (isRecording) {
            vibrateShort()
            // Paste a single space right away, before Groq has even been
            // called. On this OEM, empty fields report their hint text
            // ("Type a message…") back as if it were real content, and the
            // isShowingHintText/hintText flags meant to catch that can't be
            // trusted either — so rather than trying to detect the hint,
            // this makes the field genuinely non-empty up front, which
            // makes the hint disappear the normal way. ACTION_PASTE (not
            // ACTION_SET_TEXT) is used so this lands at the cursor without
            // needing to read/reconstruct whatever's already in the field.
            pastedLeadingSpace = pasteTextAtCursor(focused, " ")
            showStatusOverlay("Listening…")
        } else {
            showStatusOverlay("Couldn't start recording", autoHideMs = 1200)
        }
        focused.recycle()
    }

    private fun stopRecordingAndInsert() {
        isRecording = false
        showStatusOverlay("Transcribing…")
        voiceHelper?.stopRecordingAndTranscribe(
            onResult = { text ->
                removeStatusOverlay()
                insertIntoFocusedField(text)
            },
            onError = { msg ->
                Log.w(TAG, "voice error: $msg")
                if (pastedLeadingSpace) removeLeadingSpace()
                pastedLeadingSpace = false
                showStatusOverlay(msg, autoHideMs = 2000)
            }
        )
    }

    /** Walks every current window looking for one with a focused,
     *  editable node — findFocus(FOCUS_INPUT) alone can return
     *  non-editable focused nodes in some apps (e.g. a focused but
     *  read-only list item), so isEditable is checked explicitly too,
     *  and every window (not just the front one) is checked in case the
     *  editable field is in a different window than the topmost one
     *  (a keyboard's own suggestion strip, for instance). */
    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val windowList = windows ?: return null
        for (window in windowList) {
            val root = window.root ?: continue
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) return focused
            focused?.recycle()
        }
        return null
    }

    private fun insertIntoFocusedField(text: String) {
        pastedLeadingSpace = false
        if (text.isBlank()) return
        val node = findFocusedEditableNode()
        if (node == null) {
            Log.w(TAG, "no focused editable field to insert into anymore")
            showStatusOverlay("Lost the text field — nothing inserted", autoHideMs = 2000)
            return
        }
        // Pastes at the cursor — lands right after the lead-in space from
        // startRecording, and (same as that space) doesn't need to read or
        // rebuild whatever text was already in the field.
        val success = pasteTextAtCursor(node, text)
        if (success) vibrateShort()
        Log.i(TAG, "inserted transcribed text into focused field: success=$success")
        node.recycle()
    }

    /** Whether TurboText is the app currently on screen — checked so the
     *  mic button can be left alone for TurboText's own built-in voice
     *  input instead of TurboVoice hijacking it too. */
    private fun isTurboTextForeground(): Boolean {
        val root = rootInActiveWindow ?: return false
        val isTurboText = root.packageName == TURBOTEXT_PACKAGE
        root.recycle()
        return isTurboText
    }

    /** Brief tactile confirmation — there's no touchscreen and, outside of
     *  the small status overlay, otherwise no feedback at all that a press
     *  registered or that text actually landed. */
    private fun vibrateShort() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(40)
            }
        } catch (e: Exception) {
            // Not load-bearing for voice input itself.
            Log.w(TAG, "couldn't vibrate", e)
        }
    }

    /** Pastes [content] at the current cursor/selection via the clipboard,
     *  preserving whatever the user actually had copied — this briefly
     *  takes over the clipboard to do the paste, so the previous clip is
     *  restored right after rather than being left clobbered. */
    private fun pasteTextAtCursor(node: AccessibilityNodeInfo, content: String): Boolean {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val previousClip = clipboard.primaryClip
        clipboard.setPrimaryClip(ClipData.newPlainText("turbovoice", content))
        val success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (previousClip != null) {
            clipboard.setPrimaryClip(previousClip)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        }
        return success
    }

    /** Cleans up the lead-in space from startRecording when recording ends
     *  without any text to insert (error, no speech, etc) — otherwise a
     *  stray space would be left behind in the field. Reading node.text
     *  here is safe (unlike before pasting the space) since the field is
     *  now genuinely non-empty, not showing a hint. */
    private fun removeLeadingSpace() {
        val node = findFocusedEditableNode() ?: return
        val text = node.text?.toString()
        val cursor = node.textSelectionEnd
        if (text != null && cursor in 1..text.length && text[cursor - 1] == ' ') {
            val newText = text.removeRange(cursor - 1, cursor)
            val setTextArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor - 1)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor - 1)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        }
        node.recycle()
    }

    /** A small, non-interactive overlay label — not a tap target, this
     *  phone has no touchscreen to tap it with anyway, just a "here's
     *  what's happening" indicator since there's otherwise no feedback
     *  at all that a press registered. FLAG_NOT_TOUCHABLE means it can
     *  never intercept input even if a future device this runs on does
     *  have a touchscreen.
     *
     *  TYPE_ACCESSIBILITY_OVERLAY on API 26+: an AccessibilityService is
     *  allowed to add this window type on its own authority, without the
     *  SYSTEM_ALERT_WINDOW "draw over other apps" permission.
     *
     *  Below API 26, TYPE_ACCESSIBILITY_OVERLAY doesn't exist, so this
     *  falls back to the older TYPE_PHONE, which *does* need
     *  SYSTEM_ALERT_WINDOW. An earlier attempt at that fallback threw
     *  BadTokenException ("permission denied for window type 2002") on a
     *  real device — not because the fallback approach is wrong, but
     *  because it never checked canDrawOverlays() first, so it was firing
     *  before the user had actually granted the permission from
     *  MainActivity's "Open Overlay Permission Settings" button. Checking
     *  first and skipping (rather than crashing/logging noise) when it's
     *  not yet granted avoids repeating that. */
    private fun showStatusOverlay(message: String, autoHideMs: Long? = null) {
        removeStatusOverlay()
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(this)) {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        } else {
            Log.i(TAG, "pre-API26 device without overlay permission — skipping status overlay")
            return
        }
        try {
            val tv = TextView(this).apply {
                text = message
                setBackgroundColor(0xCC000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(28, 14, 28, 14)
                textSize = 14f
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 60
            windowManager?.addView(tv, params)
            statusOverlay = tv
            if (autoHideMs != null) {
                handler.postDelayed({ if (statusOverlay === tv) removeStatusOverlay() }, autoHideMs)
            }
        } catch (e: Exception) {
            // Voice input still works either way — this is purely a
            // "what's happening" indicator, not load-bearing for the
            // actual feature.
            Log.w(TAG, "couldn't show status overlay", e)
        }
    }

    private fun removeStatusOverlay() {
        statusOverlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Already removed, or window gone — harmless either way.
            }
        }
        statusOverlay = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — everything here is driven by onKeyEvent, but the
        // base class requires this override regardless.
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "TurboVoiceService"

        // TurboText's applicationId (see its app/build.gradle) — used to
        // detect when it's the foreground app so this service can leave
        // the mic button alone for TurboText's own built-in voice input.
        private const val TURBOTEXT_PACKAGE = "com.turbotext.app"
    }
}
