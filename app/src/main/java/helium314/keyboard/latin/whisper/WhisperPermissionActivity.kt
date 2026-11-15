// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.whisper

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** A keyboard cannot ask for permissions itself; this invisible activity asks for the microphone. */
class WhisperPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Microphone allowed – tap the mic key again", Toast.LENGTH_SHORT).show()
        finish()
    }
}
