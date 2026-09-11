package dev.blocks.android.api

/** The reachable Service is not serving the accepted API 0.4 response contract yet. */
class IncompatibleServiceApiException(cause: Throwable? = null) : RuntimeException(
    "The Blocks Service is not yet serving the accepted API 0.4 contract",
    cause,
)
