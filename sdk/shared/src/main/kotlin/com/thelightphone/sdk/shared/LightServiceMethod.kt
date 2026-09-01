package com.thelightphone.sdk.shared

import com.thelightphone.sdk.shared.LightServiceMethod.SetRingtone.Request
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// A tool outlives the phone build it was compiled against, so both directions of the drift
// have to be survivable. `ignoreUnknownKeys` covers a field the phone has learned to send;
// `explicitNulls = false` covers the other half — a newer server omits a null field instead of
// writing `null`, and to the decoder a nullable field with no default is still a *required*
// field. Without this, a keyboard-options payload from a phone newer than the tool throws
// MissingFieldException, and that throw lands on a screen that is only trying to draw a
// keyboard. Every response below also carries a default for the same reason.
val lightJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // The same drift, aimed at an enum: a newer server can answer with a member this build has
    // never heard of, and without this the decode throws instead of falling back to the
    // property's default.
    coerceInputValues = true
}

/**
 * Defines a typed method that a client can call on the server's bound service.
 */
sealed interface LightServiceMethod<TRequest, TResponse> {

    val id: String
    val requestSerializer: KSerializer<TRequest>
    val responseSerializer: KSerializer<TResponse>

    fun encodeRequest(request: TRequest): String =
        lightJson.encodeToString(requestSerializer, request)

    fun decodeRequest(json: String): TRequest =
        lightJson.decodeFromString(requestSerializer, json)

    fun encodeResponse(response: TResponse): String =
        lightJson.encodeToString(responseSerializer, response)

    fun decodeResponse(json: String): TResponse =
        lightJson.decodeFromString(responseSerializer, json)

    /**
     * Define all service methods below. DO NOT CHANGE EXISTING METHODS
     */
    object GetToken : LightServiceMethod<Unit, GetToken.Response> {
        override val id = "GetToken"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        // A blank token is meaningless — the caller must treat it as a failed grant, which
        // `LightServiceConnection.ensureToken` does. The default only keeps the decode alive.
        data class Response(val token: String = "")
    }

    object GetVersion : LightServiceMethod<Unit, GetVersion.Response> {
        override val id = "GetVersion"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(val version: String = "")
    }

    object SetRingtone : LightServiceMethod<Request, Unit> {
        override val id = "SetRingtone"
        override val requestSerializer = serializer<Request>()
        override val responseSerializer = serializer<Unit>()

        @Serializable
        data class Request(val type: Int, val uri: String)
    }

    object GetKeyboardOptions : LightServiceMethod<Unit, GetKeyboardOptions.Response> {
        override val id = "GetKeyboardOptions"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(
            // "😅😅😅😅😅😅" -> keyboard will parse out emoji code points
            val emojisAsString: String? = null,
            val displayVoice: Boolean = true,
            val enableKeyAnimation: Boolean = true,
            // Sent by newer servers only, and absent on every phone that shipped before it.
            val swipeEnabled: Boolean? = null,
        )
    }

    object GetUserPreferences : LightServiceMethod<Unit, GetUserPreferences.Response> {
        override val id = "GetUserPreferences"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Response(
            val hapticsEnabled: Boolean = true,
        )
    }

    object GetPermission : LightServiceMethod<GetPermission.Request, GetPermission.Response> {
        enum class Result {
            Unknown, BlockedByServer, Granted, Denied
        }
        override val id = "GetPermission"
        override val requestSerializer = serializer<Request>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        data class Request(val permissionName: String)

        @Serializable
        data class Response(
            // Unknown, not Granted: a value this build cannot read must never widen access.
            // `coerceInputValues` in [lightJson] lands unrecognised members here too.
            val permissionResult: Result = Result.Unknown
        )
    }

    object RequestPermissionComponent : LightServiceMethod<Unit, RequestPermissionComponent.Response> {
        const val PERMISSION_NAME_KEY = "PermissionName"
        override val id = "RequestPermissionComponent"
        override val requestSerializer = serializer<Unit>()
        override val responseSerializer = serializer<Response>()

        @Serializable
        // A blank componentName cannot be launched; the caller treats it as an error.
        data class Response(val componentName: String = "")
    }
}

// TODO we're gonna forget to add manually, maybe use reflection?
val allMethods: Map<String, LightServiceMethod<*, *>> = listOf(
    LightServiceMethod.GetToken,
    LightServiceMethod.GetVersion,
    LightServiceMethod.SetRingtone,
    LightServiceMethod.GetKeyboardOptions,
    LightServiceMethod.GetUserPreferences,
    LightServiceMethod.GetPermission,
    LightServiceMethod.RequestPermissionComponent,
).associateBy { it.id }
