package com.turbovoice.app

import android.os.Environment
import java.io.File

/**
 * The Groq API key lives in a plain text file the user edits directly —
 * Internal storage / Turbo Key / groq_api_key.txt — rather than being
 * baked into the app, so it's never sitting in source control. Get one
 * free at https://console.groq.com/keys. Same key works for both
 * TurboVoice and TurboText if you use both.
 */
object GroqConfig {
    private const val FOLDER_NAME = "Turbo Key"
    private const val FILE_NAME = "groq_api_key.txt"

    fun keyFile(): File =
        File(File(Environment.getExternalStorageDirectory(), FOLDER_NAME), FILE_NAME)

    /** Creates the folder and an empty key file on first run if they don't
     *  already exist, so there's somewhere obvious to paste a key in.
     *  Safe to call repeatedly — a no-op once the file exists. Fails
     *  silently if storage permission hasn't been granted yet;
     *  MainActivity's checklist surfaces that separately. */
    fun ensureKeyFileExists() {
        try {
            val file = keyFile()
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        } catch (e: Exception) {
            // Storage permission not granted yet, or some other
            // filesystem hiccup — not fatal, just means the key file
            // isn't there yet for the user to fill in.
        }
    }

    fun getApiKey(): String =
        try {
            keyFile().readText().trim()
        } catch (e: Exception) {
            ""
        }
}
