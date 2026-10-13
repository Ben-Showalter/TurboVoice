package com.turbovoice.app

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Locale

/**
 * Asks the user whether to install [release] (noting that Wi-Fi is preferred
 * for the download), and on Install downloads it via [loader] and hands it to
 * the system installer. [loader] should be dedicated to updates, so an
 * unrelated newer load can't supersede (and silently drop) the download.
 */
fun Activity.showUpdatePrompt(release: UpdateRelease, loader: BackgroundLoader) {
    val sizeMb = String.format(Locale.getDefault(), "%.1f", release.apkSizeBytes / 1_048_576.0)
    var message = getString(R.string.update_available_message, sizeMb)
    if (!UpdateChecker.isOnWifi(this)) {
        message += "\n\n" + getString(R.string.update_on_mobile_data)
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.update_available_title, release.versionName))
        .setMessage(message)
        .setPositiveButton(R.string.update_install) { _, _ -> downloadAndInstall(release, loader) }
        .setNegativeButton(R.string.update_later, null)
        .show()
}

private fun Activity.downloadAndInstall(release: UpdateRelease, loader: BackgroundLoader) {
    val progress = AlertDialog.Builder(this)
        .setMessage(R.string.update_downloading)
        .setCancelable(false)
        .show()
    loader.load(
        produce = {
            // BackgroundLoader drops a failed produce() silently; report it as null instead.
            try {
                UpdateChecker.download(this, release)
            } catch (e: Exception) {
                null
            }
        },
        consume = { apk ->
            if (isFinishing || isDestroyed) return@load
            progress.dismiss()
            if (apk == null) {
                Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
                return@load
            }
            try {
                startActivity(UpdateChecker.installIntent(this, apk))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
        },
    )
}
