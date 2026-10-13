package com.turbovoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    /** Runs the update check/download off the main thread (see [maybeCheckForUpdate]). */
    private val updateLoader = BackgroundLoader()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.openOverlayButton).setOnClickListener {
            // ACTION_MANAGE_OVERLAY_PERMISSION with a package: URI (the
            // "jump straight to this app's permission page" variant)
            // doesn't resolve on this OEM. TurboText's working "Default
            // apps" button uses the general ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            // screen instead — overlay permission is reachable from there
            // (Special app access > Display over other apps), same as it
            // is for TurboText.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Open Settings > Apps > Special app access > Display over other apps",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<Button>(R.id.requestMicButton).setOnClickListener {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.requestStorageButton).setOnClickListener {
            requestStorageAccess()
        }
        findViewById<Button>(R.id.requestSmsButton).setOnClickListener {
            requestSmsPermission.launch(Manifest.permission.RECEIVE_SMS)
        }
        findViewById<Button>(R.id.setTrustedNumberButton).setOnClickListener {
            promptTrustedNumber()
        }
        findViewById<Button>(R.id.setTriggerKeyButton).setOnClickListener {
            startActivity(Intent(this, KeyCaptureActivity::class.java))
        }
        findViewById<Button>(R.id.clearTriggerKeyButton).setOnClickListener {
            TriggerKeyStorage.setTriggerKeycode(this, -1)
            Toast.makeText(this, "Trigger key cleared — voice input disabled until a new one is set", Toast.LENGTH_LONG).show()
            refreshStatus()
        }
        findViewById<Button>(R.id.checkUpdatesButton).setOnClickListener {
            checkForUpdate()
        }
    }

    /** Phone number input, not full text — a plain EditText with
     *  inputType phone works fine off the physical keypad here, unlike
     *  the full T9 text entry TurboText needed for composing messages. */
    private fun promptTrustedNumber() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            setText(ProvisioningSettings.getTrustedNumber(this@MainActivity) ?: "")
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Trusted Provisioning Number")
            .setMessage(
                "The only phone number allowed to remotely set the Groq API key via a " +
                    "specially-formatted text message. Leave blank to disable."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val number = input.text.toString().trim()
                ProvisioningSettings.setTrustedNumber(this, number.ifBlank { null })
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Cheap no-op once the file already exists — retried here (rather
        // than only once at first launch) so the folder/file appear as
        // soon as storage access gets granted, without needing a reinstall.
        GroqConfig.ensureKeyFileExists()
        refreshStatus()
        maybeCheckForUpdate()
    }

    override fun onDestroy() {
        updateLoader.cancel()
        super.onDestroy()
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Same lesson as the overlay button above: go straight for the
            // general "All files access" list screen rather than the
            // per-app deep link, since that class of direct-to-app-page
            // intent has proven unreliable on this OEM.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Open Settings > Apps > Special app access > All files access > TurboVoice",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun refreshStatus() {
        val micOn = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val accessibilityOn = isAccessibilityServiceEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val storageOn = hasStorageAccess()
        val apiKeySet = GroqConfig.getApiKey().isNotBlank()
        val smsOn = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val trustedNumberSet = !ProvisioningSettings.getTrustedNumber(this).isNullOrBlank()
        val triggerKeycode = TriggerKeyStorage.getTriggerKeycode(this)
        val triggerDescription = if (triggerKeycode != null) {
            KeyEvent.keyCodeToString(triggerKeycode)
        } else {
            "not set — voice input is disabled until one is chosen below"
        }

        statusText.text = buildString {
            append(if (micOn) "✓" else "✗").append(" Microphone permission granted\n")
            append(if (accessibilityOn) "✓" else "✗").append(" Accessibility service enabled\n")
            append(if (overlayOn) "✓" else "✗").append(" Overlay permission granted (for the \"Listening…\" indicator — not required for voice input itself to work)\n")
            append(if (storageOn) "✓" else "✗").append(" Storage access granted (needed to read the API key file)\n")
            append(if (apiKeySet) "✓" else "✗").append(" Groq API key set in Internal storage/Turbo Key/groq_api_key.txt\n")
            append(if (smsOn) "✓" else "✗").append(" SMS permission granted (optional — for remote key provisioning)\n")
            append(if (trustedNumberSet) "✓" else "✗").append(" Trusted provisioning number set (optional)\n")
            append("Trigger key: ").append(triggerDescription).append("\n")
            append("Installed version: ").append(UpdateChecker.installedVersion(this@MainActivity)).append("\n\n")
            append("How to use: tap into any text field in any app, then hold the trigger key, speak, and release. The transcribed text is pasted in at the cursor position.")
        }
    }

    // ---------------------------------------------------------------- Updates

    /**
     * Checks GitHub for a newer release about once a week when this screen is
     * opened (same as FlipLaunch's Home screen) and, if there is one, asks the
     * user whether to install it. The Check for Updates button checks on demand.
     */
    private fun maybeCheckForUpdate() {
        val now = System.currentTimeMillis()
        val last = ProvisioningSettings.getLastUpdateCheckAt(this)
        // A last-check time in the future means the clock was turned back; check anyway.
        if (now - last in 0 until UpdateChecker.CHECK_INTERVAL_MS) return
        // Recorded up front so resumes while the check is in flight don't start another.
        ProvisioningSettings.setLastUpdateCheckAt(this, now)
        updateLoader.load(
            produce = {
                try {
                    Result.success(UpdateChecker.fetchLatest(this))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            consume = { result ->
                result.onFailure {
                    // Offline or rate-limited: try again in a day rather than a week.
                    ProvisioningSettings.setLastUpdateCheckAt(
                        this,
                        now - UpdateChecker.CHECK_INTERVAL_MS + UpdateChecker.RETRY_AFTER_FAILURE_MS,
                    )
                }
                val release = result.getOrNull() ?: return@load
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    showUpdatePrompt(release, updateLoader)
                } else {
                    // This screen was left mid-check; ask on the next resume instead.
                    ProvisioningSettings.setLastUpdateCheckAt(this, 0L)
                }
            },
        )
    }

    /** Manual counterpart to the weekly automatic check ([maybeCheckForUpdate]). */
    private fun checkForUpdate() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        updateLoader.load(
            produce = {
                try {
                    Result.success(UpdateChecker.fetchLatest(this))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            consume = { result ->
                val release = result.getOrNull()
                when {
                    result.isFailure ->
                        Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    release == null ->
                        Toast.makeText(this, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                    else -> showUpdatePrompt(release, updateLoader)
                }
            },
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${TurboVoiceAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
