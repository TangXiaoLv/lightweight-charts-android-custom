package com.tradingview.lightweightcharts.api.extension

/**
 * Immutable description of a chart extension.
 *
 * The host only understands this generic description: an extension id, a JavaScript bundle that
 * lives in the application assets and an optional opaque payload passed verbatim to the extension.
 */
data class ChartExtensionDescriptor(
    /** Stable identifier of the extension, unique per [com.tradingview.lightweightcharts.view.ChartsView]. */
    val extensionId: String,
    /**
     * Path of the JavaScript bundle inside the merged application assets, for example
     * `easychart/drawing/drawing-engine.bundle.js`. The path must not contain `..` segments.
     */
    val bundleAssetPath: String,
    /** Optional opaque payload forwarded to the extension during registration. */
    val initPayload: String? = null
) {
    init {
        require(extensionId.isNotBlank()) { "extensionId must not be blank" }
        require(bundleAssetPath.isNotBlank()) { "bundleAssetPath must not be blank" }
        require(!bundleAssetPath.contains("..")) { "bundleAssetPath must not contain '..'" }
        require(!bundleAssetPath.startsWith("/")) { "bundleAssetPath must be a relative asset path" }
        require(!bundleAssetPath.contains("://")) { "bundleAssetPath must be an asset path, not a URL" }
    }

    /** Returns the `file:///android_asset/...` URL used by the WebView to load the bundle. */
    fun toBundleUrl(): String = "file:///android_asset/$bundleAssetPath"
}
