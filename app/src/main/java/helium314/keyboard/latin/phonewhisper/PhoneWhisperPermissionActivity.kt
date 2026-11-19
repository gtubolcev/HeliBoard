// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.phonewhisper

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import helium314.keyboard.latin.R

/**
 * Transparent trampoline: an input method cannot ask for runtime permissions itself.
 * Requests the microphone and the permission to use Phone Whisper's dictation.
 */
class PhoneWhisperPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = PhoneWhisperClient(this, NoEvents).missingPermissions()
        if (missing.isEmpty() || android.os.Build.VERSION.SDK_INT < 23) {
            finish()
            return
        }
        requestPermissions(missing, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Toast.makeText(
            this,
            if (ok) R.string.phone_whisper_permissions_granted else R.string.phone_whisper_permissions_denied,
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private object NoEvents : PhoneWhisperClient.Events {
        override fun onConnectionChanged(connected: Boolean) {}
        override fun onInfo(streaming: Boolean, micButtons: String, cleanup: Boolean) {}
        override fun onStarted(live: Boolean) {}
        override fun onState(state: String) {}
        override fun onLevel(level: Float) {}
        override fun onPartial(text: String) {}
        override fun onSegment(text: String, forced: Boolean) {}
        override fun onResult(text: String, notice: String) {}
        override fun onError(message: String, code: String?) {}
        override fun onCleaned(id: Int, text: String, notice: String) {}
    }
}
