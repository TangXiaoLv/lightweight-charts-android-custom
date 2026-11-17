package com.tradingview.lightweightcharts.api.extension

/**
 * Generic chart extension contract.
 *
 * Implementations live in feature modules (for example `easychart`) and are attached to a chart
 * through [ChartExtensionHost]. The host never interprets extension messages; every
 * `messageType` / `payloadJson` pair is opaque to the chart wrapper.
 */
interface ChartExtension {

    /** Descriptor declared by the extension. */
    val descriptor: ChartExtensionDescriptor

    /**
     * Called once the JavaScript bundle is loaded and the extension runtime is created.
     * The context is the only way to talk to the JavaScript side.
     */
    fun onExtensionReady(context: ChartExtensionContext) {}

    /**
     * Called for every message emitted by the JavaScript extension.
     *
     * @param event opaque event; use [ChartExtensionEvent.messageType] / [ChartExtensionEvent.payloadJson].
     */
    fun onExtensionEvent(event: ChartExtensionEvent) {}

    /** Called when the host or the JavaScript runtime reports a structured error. */
    fun onExtensionError(error: ChartExtensionError) {}

    /** Called after the JavaScript runtime accepted a target series binding. */
    fun onExtensionSeriesAttached(seriesId: String) {}

    /** Called after the target series binding was released (series removed or extension detached). */
    fun onExtensionSeriesDetached(seriesId: String) {}

    /**
     * Called when the runtime is torn down (chart destroyed, WebView reloaded or host released).
     * The extension state that must survive a rebuild has to be owned by the Android side.
     */
    fun onExtensionDestroyed() {}
}
