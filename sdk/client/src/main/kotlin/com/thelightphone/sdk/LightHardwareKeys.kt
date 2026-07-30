package com.thelightphone.sdk

import android.view.KeyEvent

/**
 * First look at the phone's hardware keys, for a tool that has a use for one.
 *
 * The LPIII's brightness wheel, wheel click and camera button arrive at the focused window as
 * ordinary key events. A tool cannot see them: the window belongs to [LightActivity], and the
 * build rules deliberately keep a tool away from the activity, the window and `LocalView`, so
 * there is nowhere in tool code to override `dispatchKeyEvent`. This is the one seam.
 *
 * [handler] is called from [LightActivity.dispatchKeyEvent], before the event reaches the view
 * hierarchy — which is the only position from which a key can be claimed ahead of a focused
 * WebView or text field. Return true to consume the event; return false and it carries on
 * untouched.
 *
 * One handler, replaced rather than added to, because there is one window and the key either
 * belongs to the tool or it does not. Volatile because the key arrives on the main thread and
 * the handler is usually installed from a composition on the same thread, but nothing in the
 * API promises that.
 */
object LightHardwareKeys {

    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null
}
