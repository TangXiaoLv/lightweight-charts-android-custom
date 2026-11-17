package com.tradingview.lightweightcharts.api.extension

/**
 * Generic extension host attached to one [com.tradingview.lightweightcharts.view.ChartsView].
 *
 * The host owns no business knowledge: it loads the declared JavaScript bundle, forwards opaque
 * messages and reports lifecycle / error events. Multiple host instances (one per chart view) are
 * fully isolated by [chartInstanceId].
 *
 * All methods must be called on the main thread.
 */
interface ChartExtensionHost {

    /** Unique id of the chart instance hosted by this extension host. */
    val chartInstanceId: String

    /**
     * Registers an extension. The bundle is loaded immediately when the chart runtime is ready,
     * otherwise registration is queued until the runtime becomes ready.
     */
    fun registerExtension(extension: ChartExtension)

    /** Unregisters and destroys the extension runtime. Safe to call for unknown ids. */
    fun unregisterExtension(extensionId: String)

    /** Whether the JavaScript runtime of [extensionId] reported ready. */
    fun isExtensionReady(extensionId: String): Boolean

    /**
     * Sends an opaque message to an extension. Returns `false` when the extension is unknown or
     * its pending queue is full.
     */
    fun sendMessage(extensionId: String, messageType: String, payloadJson: String): Boolean

    /** Binds an extension to a series. Returns `false` when the extension is unknown. */
    fun attachSeries(extensionId: String, seriesId: String): Boolean

    /** Releases the series binding of an extension. */
    fun detachSeries(extensionId: String)

    /** Adds a listener that receives raw extension events, ready/error notifications. */
    fun addEventListener(listener: ChartExtensionHostListener)

    /** Removes a previously added listener. */
    fun removeEventListener(listener: ChartExtensionHostListener)

    /**
     * Releases every extension runtime. Business data must stay in the feature module; this only
     * tears down the JavaScript side.
     */
    fun release()
}

/** Optional observer of the generic host lifecycle. */
interface ChartExtensionHostListener {
    fun onExtensionReady(event: ChartExtensionEvent) {}
    fun onExtensionEvent(event: ChartExtensionEvent) {}
    fun onExtensionError(error: ChartExtensionError) {}
    fun onExtensionDestroyed(extensionId: String) {}
}
