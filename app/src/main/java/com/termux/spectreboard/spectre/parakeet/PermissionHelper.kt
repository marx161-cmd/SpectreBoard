// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) audio/PermissionHelper.kt,
// trimmed to the record-permission check (deep-link intent dropped - SpectreBoard has its
// own permission flow).
package com.termux.spectreboard.spectre.parakeet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Utility for checking [Manifest.permission.RECORD_AUDIO].
 *
 * The IME itself cannot present a permission dialog - only an Activity can. A `false` result
 * from [hasRecordPermission] should be surfaced to the user via SpectreBoard's own flow.
 */
object PermissionHelper {

    /** Returns `true` if [Manifest.permission.RECORD_AUDIO] has been granted for [context]. */
    fun hasRecordPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
}
