package com.tradingview.lightweightcharts.api.extension

/**
 * Structured error reported by the generic extension host or by a JavaScript extension.
 */
data class ChartExtensionError(
    val extensionId: String,
    val chartInstanceId: String,
    val code: String,
    val message: String,
    /** Optional extension-defined diagnostic detail. */
    val detail: String = "",
    /** Optional stack trace reported by JavaScript. */
    val stackTrace: String = ""
)
