package com.telecall.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * This app's whole job is placing phone calls, so a device with no
 * telephony radio at all cannot do it — checked once at launch, before
 * sign-in, so nobody can work a queue from a desktop Android player
 * (BlueStacks, LDPlayer, NoxPlayer, MEmu) or a keyboard-and-trackpad
 * Android laptop (e.g. Floydwiz Technologies' Primebook — real
 * hardware, not an emulator, but still not a phone) no matter how
 * convenient the bigger screen is.
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

    // Two different reasons a device can end up here, same fix either
    // way: a PC emulator (BlueStacks, LDPlayer, NoxPlayer, MEmu,
    // Genymotion) is built for games, which mostly don't care about
    // telephony, so hasSystemFeature(FEATURE_TELEPHONY) alone should
    // already catch most of them — this list is a second, independent
    // signal in case one ever fakes it. Real Android-laptop hardware
    // (Floydwiz Technologies' Primebook, confirmed live: an agent's
    // WiFi-only Primebook reported FEATURE_TELEPHONY = true despite
    // having no cellular modem at all, so the hardware-feature check
    // did not catch it) needs to be named explicitly, since it isn't
    // virtualized and won't share an emulator's other tells. Not meant
    // to be exhaustive either way — add to this list as new ones turn up
    // in the dashboard's App versions table.
    private val knownNonPhoneDevices = listOf(
        "bluestacks", "nox", "ldplayer", "memu", "genymotion",
        "floydwiz", "primebook"
    )

    fun isSupportedDevice(context: Context): Boolean {
        val hasTelephony = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val looksLikeKnownNonPhone = knownNonPhoneDevices.any {
            manufacturer.contains(it) || model.contains(it)
        }
        return hasTelephony && !looksLikeKnownNonPhone
    }
}
