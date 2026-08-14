package com.turbovoice.app

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** "It would open a dialog to press any key, then it would save/memorize
 *  that key" — a plain Activity rather than an actual AlertDialog, since
 *  a Dialog's own window can behave inconsistently for raw key capture
 *  across OEM builds (a lesson learned the hard way elsewhere in this
 *  project) — a full-screen Activity's onKeyDown is a much more direct,
 *  reliable way to intercept exactly one key press. Back is deliberately
 *  the one exception, kept free as a way to cancel out without setting
 *  anything, rather than captured like every other key. */
class KeyCaptureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_capture)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event)
        }
        TriggerKeyStorage.setTriggerKeycode(this, keyCode)
        Toast.makeText(this, "Trigger key set: ${KeyEvent.keyCodeToString(keyCode)}", Toast.LENGTH_SHORT).show()
        finish()
        return true
    }
}
