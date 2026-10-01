package dev.blocks.android.api

/** The reachable Service returned bytes that do not satisfy the generated active contract. */
class IncompatibleServiceApiException(cause: Throwable? = null) : RuntimeException(
    "The Blocks Service response does not satisfy the active contract",
    cause,
)
