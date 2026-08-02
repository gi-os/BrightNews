package com.lightrss.reader

import android.Manifest // light-sdk-allow: permission check for the WRITE_SECURE_SETTINGS grant
import android.content.Context // light-sdk-allow: Settings.Secure needs a real ContentResolver
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Lifts LightOS's forced greyscale while a picture is on screen.
 *
 * The Light Phone III's panel is a **full-colour AMOLED**. Its black-and-white look is Android's
 * accessibility colour correction — the daltonizer — pinned to simulate-monochromacy, which is a
 * secure setting and a SurfaceFlinger colour-matrix change, so switching
 * `accessibility_display_daltonizer_enabled` off shows true colour instantly with no restart.
 * LightOS does the same thing itself: photos and video play in colour on a phone that is
 * otherwise grey.
 *
 * Ported from `LightCamera`'s `ColorMode`, which is itself a straight port of `LightChat`'s.
 * The reference counting and foreground handling below are load-bearing and were arrived at the
 * hard way; they are copied rather than re-derived.
 *
 * Two things differ here, both because this is a Light SDK tool rather than a standalone app.
 *
 * The `android.content.Context` import and the `contentResolver` access are blocked by the SDK's
 * build policy, and are marked exempt line by line rather than by deleting the rules — see
 * `LightSdkPlugin.ALLOW_MARKER`. A tool reaching secure settings deserves to be conspicuous, and
 * these are the only four lines in the app that do.
 *
 * And there is no `Application` to hook, so the foreground handling hangs off `LightScreen`'s
 * own `onAppPause` rather than a lifecycle observer. Leaving the app puts the phone back to grey
 * immediately, which matters: forgetting to would leave the whole phone in colour behind you.
 *
 * Writing the setting needs `WRITE_SECURE_SETTINGS`, which is `signature|privileged|development`
 * and so grantable over adb exactly once:
 *
 * ```
 * adb shell pm grant com.lightrss.reader android.permission.WRITE_SECURE_SETTINGS
 * ```
 *
 * Without the grant every call here quietly does nothing — the `SecurityException` is swallowed
 * — and images stay grey like the rest of the phone. It degrades rather than breaks.
 */
object ColorMode {

    private const val TAG = "ColorMode"
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    /**
     * The daltonizer mode to put back — LightOS pins 0, simulate monochromacy. Non-null exactly
     * while we are holding the phone in colour.
     */
    private var savedMode: Int? = null

    /**
     * How many screens want colour, not whether one does.
     *
     * A reader can open the linked page over the top of the article it came from, so two screens
     * that both want colour are alive at once. With a boolean, whichever released first would
     * drop colour out from under the other.
     */
    private var holders = 0

    fun acquire(context: Context) {
        holders++
        if (holders == 1) lift(context)
    }

    fun release(context: Context) {
        if (holders > 0) holders--
        if (holders == 0) restore(context)
    }

    /** The app left the foreground: the rest of the phone should be grey again at once. */
    fun onAppHidden(context: Context) = restore(context)

    /**
     * Back in the foreground — re-lift if anything still wants colour. Deliberately does not
     * touch [holders]: leaving the app is not the same as leaving the article.
     */
    fun onAppVisible(context: Context) {
        if (holders > 0) lift(context)
    }

    /** True if the one-time adb grant has been given. Used to explain itself in Settings. */
    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun lift(context: Context) {
        val resolver = context.contentResolver // light-sdk-allow: the daltonizer is a secure setting
        // Already in colour — somebody else's doing, or the user's. Leave it alone entirely,
        // including on the way out: restoring greyscale we never removed would be us turning a
        // colour phone monochrome.
        if (runCatching { Settings.Secure.getInt(resolver, ENABLED, 0) }.getOrDefault(0) != 1) {
            return
        }
        val mode = runCatching { Settings.Secure.getInt(resolver, MODE, 0) }.getOrDefault(0)
        try {
            Settings.Secure.putInt(resolver, ENABLED, 0)
            savedMode = mode
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; staying greyscale")
        }
    }

    private fun restore(context: Context) {
        val mode = savedMode ?: return
        val resolver = context.contentResolver // light-sdk-allow: the daltonizer is a secure setting
        try {
            Settings.Secure.putInt(resolver, MODE, mode)
            Settings.Secure.putInt(resolver, ENABLED, 1)
            savedMode = null
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS revoked mid-hold; can't restore greyscale")
        }
    }
}

/**
 * Holds the phone in colour for as long as the calling screen is on show.
 *
 * Display-wide, not per-view: Android has no way to colourise one surface. It reads as
 * picture-only anyway, because everything else this app draws is white on black — the only thing
 * on screen with hues in it is the image.
 */
@Composable
fun ColourEffect(enabled: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(enabled, context) {
        if (enabled) ColorMode.acquire(context)
        onDispose { if (enabled) ColorMode.release(context) }
    }

    // Leaving the app has to put the phone back, and composition does not end when it does —
    // without this you would swipe home mid-article and leave the whole phone in colour behind
    // you, with nothing on screen to explain why or any obvious way to undo it. LightCamera hangs
    // this off its Application; a tool has none, so it comes off the screen's own lifecycle.
    DisposableEffect(enabled, lifecycleOwner, context) {
        if (!enabled) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> ColorMode.onAppHidden(context)
                Lifecycle.Event.ON_START -> ColorMode.onAppVisible(context)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
