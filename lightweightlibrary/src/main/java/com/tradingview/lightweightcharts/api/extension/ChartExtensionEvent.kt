package com.tradingview.lightweightcharts.api.extension

/**
 * Opaque message emitted by a JavaScript extension.
 */
data class ChartExtensionEvent(
    val extensionId: String,
    val chartInstanceId: String,
    val messageType: String,
    /** JSON payload. Empty string when the event has no payload. */
    val payloadJson: String,
    /** Monotonic sequence scoped to one chart instance; useful for de-duplication. */
    val sequence: Long
)
