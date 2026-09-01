package dev.blocks.android.api

import org.json.JSONObject

enum class ApiErrorCode(val wireValue: String) {
    INVALID_REQUEST("invalid_request"),
    UNAUTHENTICATED("unauthenticated"),
    UNAUTHORIZED("unauthorized"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    UPGRADE_REQUIRED("upgrade_required"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): ApiErrorCode = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class ApiError(
    val code: ApiErrorCode,
    val message: String,
)

internal fun parseApiError(body: String): ApiError? = runCatching {
    val error = JSONObject(body).getJSONObject("error")
    ApiError(
        code = ApiErrorCode.fromWire(error.getString("code")),
        message = error.getString("message"),
    )
}.getOrNull()
