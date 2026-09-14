package com.telecall.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * This app's whole job is placing phone calls, so a device with no
 * telephony radio at all cannot do it — checked once at launch, before
 * sign-in, so nobody can work a queue from a desktop Android player
 * (BlueStacks, LDPlayer, NoxPlayer, MEmu) no matter how convenient the
 * bigger screen and keyboard are.
 *
 * AndroidManifest's own
 * `<uses-feature android:name="android.hardware.telephony" required="true">`
 * looks like it should already enforce this, but that attribute is only
 * ever checked by the Play Store's own device-compatibility filtering at
 * install time — the OS package installer does not look at it at all for
 * a sideloaded APK, which is exactly how this app is distributed. This
 * object is the real gate.
 */
object DeviceSupport {

    // Consumer PC Android players are built for games, which mostly
    // don't care about telephony, so they generally don't bother faking
    // FEATURE_TELEPHONY either — the hardware-feature check alone should
    // already catch them. This list is a second, independent signal in
    // case one ever does fake it; it is not meant to be exhaustive.
    private val knownDesktopPlayers = listOf(
        "bluestacks", "nox", "ldplayer", "memu", "genymotion"
    )

    fun isSupportedDevice(context: Context): Boolean {
        val hasTelephony = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val looksLikeKnownPlayer = knownDesktopPlayers.any {
            manufacturer.contains(it) || model.contains(it)
        }
        return hasTelephony && !looksLikeKnownPlayer
    }
}
