// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.phonewhisper

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import helium314.keyboard.latin.R

/** Transparent trampoline: an input method cannot ask for the microphone permission itself. */
class PhoneWhisperPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
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
}
