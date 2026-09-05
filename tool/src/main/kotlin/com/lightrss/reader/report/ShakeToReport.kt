package com.lightrss.reader.report

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/**
 * The shake, wired to the whole app rather than to one screen.
 *
 * LightNews installs its reporter as one composable in the Activity's theme, above every screen.
 * A Light SDK tool has no such place: `LightActivity` composes only the screen on top of the
 * stack, so anything hung off a screen's composition dies the moment you navigate away, and a
 * shake on the reader would go unheard because the home screen that registered it is no longer
 * composed. So the sensor is registered once, against the Activity's own lifecycle — the one
 * thing that outlives every screen — and unregisters itself on DESTROY.
 *
 * Opening the screen goes through the home screen's `navigateTo`. `LightActivity.navigateTo`
 * pushes onto the stack whatever screen is current, so the root screen can raise a report screen
 * on top of a reader three levels deep without knowing it is there.
 *
 * On RESUME the accelerometer starts; on PAUSE it stops. That is what keeps a 50Hz stream from
 * being a battery question: it runs only while News is on the screen.
 */
object ShakeToReport {

    private var owner: WeakReference<LifecycleOwner>? = null
    private var detector: ShakeDetector? = null

    /** True while the report screen is up, so the shake that opened it cannot open a second. */
    @Volatile
    var open: Boolean = false

    /**
     * Idempotent per Activity. The home screen calls this from its composition on every show,
     * and only the first call against a given lifecycle does anything.
     */
    fun install(lifecycleOwner: LifecycleOwner, context: Context, onShake: () -> Unit) {
        if (owner?.get() === lifecycleOwner) return
        detector?.stop()

        val d = ShakeDetector(context.applicationContext) {
            if (!open) {
                open = true
                onShake()
            }
        }
        if (!d.available) return

        owner = WeakReference(lifecycleOwner)
        detector = d
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.addObserver(
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> d.start()
                        Lifecycle.Event.ON_PAUSE -> d.stop()
                        Lifecycle.Event.ON_DESTROY -> {
                            d.stop()
                            lifecycle.removeObserver(this)
                            if (owner?.get() === lifecycleOwner) {
                                owner = null
                                detector = null
                            }
                        }
                        else -> Unit
                    }
                }
            },
        )
        // Installed from a composition, so the Activity is already resumed and the RESUME
        // event has been and gone.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) d.start()
    }

    /** The report screen has closed; the next shake may open another. */
    fun closed() {
        open = false
        detector?.forget()
    }
}
