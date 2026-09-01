package com.lightrss.reader

import com.thelightphone.sdk.shared.LightServiceMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keyboard-options payload, read the way a tool reads it on a phone it does not know.
 *
 * A tool is installed once and then outlives whatever LightOS build it was compiled against, so
 * the service on the other end of the wire drifts under it in both directions: it learns to send
 * fields the tool has never heard of, and it stops sending fields the tool used to require. The
 * second one is the dangerous half, because to kotlinx.serialization a nullable property with no
 * default is still a *required* property — a newer server that omits a null field instead of
 * writing `null` makes the decode throw, the throw escapes the coroutine that asked, and the
 * tool dies the moment it opens a screen with a text field in it. That is the whole bug: nothing
 * was wrong with the keyboard, only with reading the phone's answer about it.
 *
 * These pin both directions on the one payload that every text-entry screen fetches.
 */
class KeyboardOptionsPayloadTest {

    @Test
    fun `a payload that omits the emoji field still decodes`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(
            """{"displayVoice":true,"enableKeyAnimation":true}"""
        )

        assertNull(decoded.emojisAsString)
        assertTrue(decoded.displayVoice)
    }

    @Test
    fun `a payload that omits every field still decodes`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse("{}")

        assertNull(decoded.emojisAsString)
        assertNull(decoded.swipeEnabled)
    }

    @Test
    fun `a field the tool has never heard of is ignored`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(
            """{"emojisAsString":"\uD83D\uDE05","displayVoice":false,"enableKeyAnimation":false,
               "swipeEnabled":true,"somethingAddedNextYear":{"nested":1}}"""
        )

        assertEquals("\uD83D\uDE05", decoded.emojisAsString)
        assertEquals(false, decoded.displayVoice)
        assertEquals(true, decoded.swipeEnabled)
    }

    @Test
    fun `an explicit null is read as absent`() {
        val decoded = LightServiceMethod.GetKeyboardOptions.decodeResponse(
            """{"emojisAsString":null,"displayVoice":true,"enableKeyAnimation":true}"""
        )

        assertNull(decoded.emojisAsString)
    }

    @Test
    fun `user preferences survive a server that omits them`() {
        assertTrue(LightServiceMethod.GetUserPreferences.decodeResponse("{}").hapticsEnabled)
    }

    // The rule the keyboard taught is not about the keyboard. Every reply the server sends has
    // to decode on a build that predates it, and the ones below were still one omitted field —
    // or one unrecognised enum member — away from a throw.

    @Test
    fun `a token reply with no token decodes to a blank one`() {
        // Blank is not a usable token; ensureToken treats it as a failed grant. The decode
        // itself must survive so the failure is a logged false, not an exception.
        assertEquals("", LightServiceMethod.GetToken.decodeResponse("{}").token)
    }

    @Test
    fun `a version reply with no version decodes`() {
        assertEquals("", LightServiceMethod.GetVersion.decodeResponse("{}").version)
    }

    @Test
    fun `a permission component reply with no component decodes to a blank one`() {
        assertEquals("", LightServiceMethod.RequestPermissionComponent.decodeResponse("{}").componentName)
    }

    @Test
    fun `a permission result this build has never heard of coerces to Unknown`() {
        val decoded = LightServiceMethod.GetPermission.decodeResponse(
            """{"permissionResult":"GrantedWhileInUse"}"""
        )

        assertEquals(LightServiceMethod.GetPermission.Result.Unknown, decoded.permissionResult)
    }

    @Test
    fun `a permission reply with no result decodes to Unknown`() {
        assertEquals(
            LightServiceMethod.GetPermission.Result.Unknown,
            LightServiceMethod.GetPermission.decodeResponse("{}").permissionResult,
        )
    }
}
