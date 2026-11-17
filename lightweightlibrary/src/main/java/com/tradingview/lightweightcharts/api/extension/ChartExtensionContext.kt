package com.tradingview.lightweightcharts.api.extension

/**
 * Per-extension handle used by Android code to talk to the JavaScript extension runtime.
 *
 * All methods must be called on the main thread. Commands issued before the runtime is ready are
 * buffered by the host (bounded) and flushed in order once the bundle reports ready.
 */
interface ChartExtensionContext {

    /** Extension id this context belongs to. */
    val extensionId: String

    /** Unique id of the chart instance hosting this extension. */
    val chartInstanceId: String

    /**
     * Sends an opaque message to the JavaScript extension.
     *
     * @param messageType extension-defined message type, for example `drawing.replaceAll`.
     * @param payloadJson extension-defined JSON payload (`{}` when there is no payload).
     * @return `true` when the message was handed to the bridge (or buffered until ready).
     */
    fun send(messageType: String, payloadJson: String): Boolean

    /**
     * Binds the extension runtime to a series. The JavaScript side resolves the series through the
     * chart runtime; [onExtensionSeriesAttached] is invoked after the binding succeeded.
     */
    fun attachSeries(seriesId: String)

    /** Releases the current series binding without destroying the extension. */
    fun detachSeries()

    /** Destroys the JavaScript runtime and unregisters the extension from the host. */
    fun dispose()

    /** Whether the JavaScript runtime already reported ready. */
    fun isReady(): Boolean
}
