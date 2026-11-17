package com.tradingview.lightweightcharts.runtime.extension

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tradingview.lightweightcharts.Logger
import com.tradingview.lightweightcharts.api.extension.ChartExtension
import com.tradingview.lightweightcharts.api.extension.ChartExtensionContext
import com.tradingview.lightweightcharts.api.extension.ChartExtensionDescriptor
import com.tradingview.lightweightcharts.api.extension.ChartExtensionError
import com.tradingview.lightweightcharts.api.extension.ChartExtensionEvent
import com.tradingview.lightweightcharts.api.extension.ChartExtensionHost
import com.tradingview.lightweightcharts.api.extension.ChartExtensionHostListener
import com.tradingview.lightweightcharts.api.serializer.Deserializer
import com.tradingview.lightweightcharts.runtime.controller.WebMessageController
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID

/**
 * Default [ChartExtensionHost] implementation.
 *
 * Generic by design: every message is an opaque `messageType` + `payloadJson` pair. The only
 * reserved message types are the `extension.*` lifecycle notifications emitted by the JavaScript
 * host itself.
 */
class ChartExtensionHostImpl(
    private val controller: WebMessageController,
    private val assets: AssetManager,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : ChartExtensionHost {

    override val chartInstanceId: String = UUID.randomUUID().toString()

    private val extensions = LinkedHashMap<String, ExtensionRecord>()
    private val listeners = mutableListOf<ChartExtensionHostListener>()
    private var eventSubscription: ((JsonElement) -> Unit)? = null
    private var released = false
    private var runtimeReady = true
    private var subscribed = false

    private val eventDeserializer = object : Deserializer<JsonElement>() {
        override fun deserialize(json: JsonElement): JsonElement = json
    }

    init {
        ensureEventSubscription()
    }

    // ==================== ChartExtensionHost ====================

    override fun registerExtension(extension: ChartExtension) {
        ensureMainThread()
        if (released) {
            return
        }
        val descriptor = extension.descriptor
        val existing = extensions[descriptor.extensionId]
        if (existing != null) {
            if (existing.descriptor == descriptor) {
                Logger.d("Extension already registered: ${descriptor.extensionId}")
                return
            }
            Logger.w("Replacing extension with different descriptor: ${descriptor.extensionId}")
            unregisterInternal(descriptor.extensionId, notifyJs = true)
        }
        if (!assetExists(descriptor.bundleAssetPath)) {
            reportError(
                extension = extension,
                code = ERROR_ASSET_NOT_FOUND,
                message = "Extension bundle is not present in merged asset: ${descriptor.bundleAssetPath}"
            )
            return
        }

        val record = ExtensionRecord(extension, ContextImpl(extension.descriptor))
        extensions[descriptor.extensionId] = record
        if (runtimeReady) {
            registerInternal(record)
        }
    }

    override fun unregisterExtension(extensionId: String) {
        ensureMainThread()
        unregisterInternal(extensionId, notifyJs = true)
    }

    override fun isExtensionReady(extensionId: String): Boolean =
        extensions[extensionId]?.ready == true

    override fun sendMessage(extensionId: String, messageType: String, payloadJson: String): Boolean {
        ensureMainThread()
        val record = extensions[extensionId] ?: return false
        if (!record.ready) {
            if (record.pendingMessages.size >= MAX_PENDING_MESSAGES) {
                reportError(
                    extension = record.extension,
                    code = ERROR_QUEUE_FULL,
                    message = "Extension message queue is full ($messageType)"
                )
                return false
            }
            record.pendingMessages.addLast(BufferedMessage(messageType, payloadJson))
            return true
        }
        return dispatchMessage(record, messageType, payloadJson)
    }

    override fun attachSeries(extensionId: String, seriesId: String): Boolean {
        ensureMainThread()
        val record = extensions[extensionId] ?: return false
        record.pendingSeriesId = seriesId
        if (!record.ready) {
            return true
        }
        attachSeriesInternal(record, seriesId)
        return true
    }

    override fun detachSeries(extensionId: String) {
        ensureMainThread()
        val record = extensions[extensionId] ?: return
        record.pendingSeriesId = null
        val seriesId = record.seriesId ?: return
        record.seriesId = null
        callJs(
            FUNCTION_DETACH_SERIES,
            paramsOf(extensionId = extensionId, seriesId = seriesId)
        )
    }

    override fun addEventListener(listener: ChartExtensionHostListener) {
        ensureMainThread()
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun removeEventListener(listener: ChartExtensionHostListener) {
        ensureMainThread()
        listeners.remove(listener)
    }

    override fun release() {
        if (released) {
            return
        }
        ensureMainThread()
        released = true
        extensions.keys.toList().forEach { unregisterInternal(it, notifyJs = true) }
        extensions.clear()
        listeners.clear()
        eventSubscription?.let { callback ->
            try {
                controller.callUnsubscribe(FUNCTION_EVENTS, callback)
            } catch (e: RuntimeException) {
                Logger.w("Failed to unsubscribe extension events: ${e.message}")
            }
        }
        eventSubscription = null
        subscribed = false
        runtimeReady = false
    }

    // ==================== runtime lifecycle ====================

    /**
     * Called by the chart view when the WebView runtime is about to be lost (reload, page finish
     * failure, chart removal). Extensions are torn down but stay registered; they are re-created
     * on the next [onChartRuntimeReady].
     */
    fun onChartRuntimeLost(reason: String) {
        if (released) {
            return
        }
        ensureMainThread()
        Logger.d("Extension runtime lost ($reason), chartInstanceId=$chartInstanceId")
        runtimeReady = false
        subscribed = false
        extensions.values.forEach { record ->
            record.registrationTimeout?.let { mainHandler.removeCallbacks(it) }
            record.registrationTimeout = null
            record.registrationInFlight = false
            record.ready = false
            record.readyNotified = false
            record.pendingMessages.clear()
            record.pendingSeriesId = null
            record.seriesId = null
            record.extension.onExtensionDestroyed()
            listeners.toList().forEach { it.onExtensionDestroyed(record.extension.descriptor.extensionId) }
        }
    }

    /** Called when the WebView runtime is ready again; all registered extensions are reloaded. */
    fun onChartRuntimeReady() {
        if (released) {
            return
        }
        ensureMainThread()
        runtimeReady = true
        ensureEventSubscription()
        extensions.values.forEach { record ->
            if (!record.ready) {
                registerInternal(record)
            }
        }
    }

    // ==================== registration ====================

    private fun registerInternal(record: ExtensionRecord) {
        if (record.registrationInFlight) {
            return
        }
        record.registrationInFlight = true
        val descriptor = record.descriptor
        val params = mutableMapOf<String, Any>(
            PARAM_EXTENSION_ID to descriptor.extensionId,
            PARAM_CHART_INSTANCE_ID to chartInstanceId,
            PARAM_BUNDLE_URL to descriptor.toBundleUrl()
        )
        descriptor.initPayload?.let { params[PARAM_INIT_PAYLOAD] = it }

        val timeout = Runnable {
            record.registrationInFlight = false
            if (!record.ready && !released) {
                reportError(
                    extension = record.extension,
                    code = ERROR_RUNTIME_TIMEOUT,
                    message = "Extension runtime did not become ready in ${REGISTRATION_TIMEOUT_MS}ms"
                )
            }
        }
        record.registrationTimeout = timeout
        mainHandler.postDelayed(timeout, REGISTRATION_TIMEOUT_MS)

        try {
            controller.callFunction(
                FUNCTION_REGISTER,
                params,
                { result: JsonElement -> handleRegisterResult(record, result) },
                eventDeserializer
            )
        } catch (e: RuntimeException) {
            mainHandler.removeCallbacks(timeout)
            record.registrationInFlight = false
            reportError(
                extension = record.extension,
                code = ERROR_BRIDGE_UNAVAILABLE,
                message = "Failed to register extension: ${e.message}"
            )
        }
    }

    private fun handleRegisterResult(record: ExtensionRecord, result: JsonElement) {
        ensureMainThread()
        record.registrationInFlight = false
        record.registrationTimeout?.let { mainHandler.removeCallbacks(it) }
        record.registrationTimeout = null
        if (released || extensions[record.descriptor.extensionId] !== record) {
            return
        }
        if (result.isJsonObject && !result.asJsonObject.booleanOrNull("ok", false)) {
            val error = result.asJsonObject
            reportError(
                extension = record.extension,
                code = error.stringOrNull("code") ?: ERROR_BUNDLE_LOAD_FAILED,
                message = error.stringOrNull("message") ?: "Extension bundle failed to load",
                detail = error.stringOrNull("detail") ?: ""
            )
            return
        }
        if (record.ready) {
            return
        }
        record.ready = true
        // A series binding is established before the extension receives its snapshot so that the
        // first message can already reference a valid series.
        val pendingSeries = record.pendingSeriesId
        if (pendingSeries != null) {
            attachSeriesInternal(record, pendingSeries)
        } else {
            notifyReady(record)
        }
    }

    private fun attachSeriesInternal(record: ExtensionRecord, seriesId: String) {
        val params = paramsOf(extensionId = record.descriptor.extensionId, seriesId = seriesId)
        try {
            controller.callFunction(
                FUNCTION_ATTACH_SERIES,
                params,
                { result: JsonElement -> handleAttachSeriesResult(record, seriesId, result) },
                eventDeserializer
            )
        } catch (e: RuntimeException) {
            reportError(
                extension = record.extension,
                code = ERROR_BRIDGE_UNAVAILABLE,
                message = "Failed to attach series: ${e.message}"
            )
        }
    }

    private fun handleAttachSeriesResult(record: ExtensionRecord, seriesId: String, result: JsonElement) {
        ensureMainThread()
        if (released) {
            return
        }
        if (result.isJsonObject && !result.asJsonObject.booleanOrNull("ok", false)) {
            val error = result.asJsonObject
            reportError(
                extension = record.extension,
                code = error.stringOrNull("code") ?: ERROR_SERIES_ATTACH_FAILED,
                message = error.stringOrNull("message") ?: "Failed to attach series $seriesId",
                detail = error.stringOrNull("detail") ?: ""
            )
            notifyReady(record)
            return
        }
        record.seriesId = seriesId
        if (!record.readyNotified) {
            notifyReady(record)
        } else {
            record.extension.onExtensionSeriesAttached(seriesId)
        }
    }

    private fun notifyReady(record: ExtensionRecord) {
        if (record.readyNotified) {
            return
        }
        record.readyNotified = true
        record.extension.onExtensionReady(record.context)
        val event = ChartExtensionEvent(
            extensionId = record.descriptor.extensionId,
            chartInstanceId = chartInstanceId,
            messageType = RESERVED_READY,
            payloadJson = "{}",
            sequence = 0
        )
        listeners.toList().forEach { it.onExtensionReady(event) }
        // Messages queued before ready are dispatched after onExtensionReady so that the
        // extension receives its snapshot first.
        flushPending(record)
    }

    private fun flushPending(record: ExtensionRecord) {
        while (record.pendingMessages.isNotEmpty() && record.ready && !released) {
            val message = record.pendingMessages.pollFirst() ?: break
            dispatchMessage(record, message.messageType, message.payloadJson)
        }
    }

    private fun dispatchMessage(record: ExtensionRecord, messageType: String, payloadJson: String): Boolean {
        val params = mapOf(
            PARAM_EXTENSION_ID to record.descriptor.extensionId,
            PARAM_MESSAGE_TYPE to messageType,
            PARAM_PAYLOAD to payloadJson
        )
        return try {
            controller.callFunction(FUNCTION_MESSAGE, params)
            true
        } catch (e: RuntimeException) {
            reportError(
                extension = record.extension,
                code = ERROR_BRIDGE_UNAVAILABLE,
                message = "Failed to send extension message '$messageType': ${e.message}"
            )
            false
        }
    }

    private fun unregisterInternal(extensionId: String, notifyJs: Boolean) {
        val record = extensions.remove(extensionId) ?: return
        record.registrationTimeout?.let { mainHandler.removeCallbacks(it) }
        record.registrationTimeout = null
        record.ready = false
        record.pendingMessages.clear()
        if (notifyJs && record.readyNotified) {
            callJs(FUNCTION_UNREGISTER, paramsOf(extensionId = extensionId))
        }
        record.extension.onExtensionDestroyed()
        listeners.toList().forEach { it.onExtensionDestroyed(extensionId) }
    }

    // ==================== events ====================

    private fun ensureEventSubscription() {
        if (subscribed || released) {
            return
        }
        subscribed = true
        val callback: (JsonElement) -> Unit = { element -> onEventJson(element) }
        eventSubscription = callback
        try {
            controller.callSubscribe(FUNCTION_EVENTS, emptyMap(), callback, eventDeserializer)
        } catch (e: RuntimeException) {
            subscribed = false
            Logger.e("Failed to subscribe extension events: ${e.message}")
        }
    }

    private fun onEventJson(element: JsonElement) {
        ensureMainThread()
        if (released || !element.isJsonObject) {
            return
        }
        val json = element.asJsonObject
        val extensionId = json.stringOrNull("extensionId") ?: return
        val instanceId = json.stringOrNull("chartInstanceId") ?: return
        if (instanceId != chartInstanceId) {
            Logger.w("Dropped stale extension event: instance=$instanceId expected=$chartInstanceId")
            return
        }
        val messageType = json.stringOrNull("messageType") ?: return
        val sequence = json.longOrNull("sequence") ?: 0L
        val payload = json.stringOrNull("payload") ?: ""
        val record = extensions[extensionId] ?: return

        when (messageType) {
            RESERVED_READY -> Unit // handled by the register result
            RESERVED_SERIES_ATTACHED -> {
                val seriesId = payloadJsonString(payload, "seriesId") ?: record.pendingSeriesId
                if (seriesId != null) {
                    record.seriesId = seriesId
                    record.extension.onExtensionSeriesAttached(seriesId)
                }
            }
            RESERVED_SERIES_DETACHED -> {
                val seriesId = record.seriesId
                record.seriesId = null
                if (seriesId != null) {
                    record.extension.onExtensionSeriesDetached(seriesId)
                }
            }
            RESERVED_ERROR -> {
                reportError(
                    extension = record.extension,
                    code = payloadJsonString(payload, "code") ?: ERROR_EXTENSION_ERROR,
                    message = payloadJsonString(payload, "message") ?: "Extension error",
                    detail = payloadJsonString(payload, "detail") ?: "",
                    stackTrace = payloadJsonString(payload, "stack") ?: ""
                )
            }
            RESERVED_DISPOSED -> {
                record.ready = false
                record.readyNotified = false
            }
            else -> {
                val event = ChartExtensionEvent(
                    extensionId = extensionId,
                    chartInstanceId = chartInstanceId,
                    messageType = messageType,
                    payloadJson = payload,
                    sequence = sequence
                )
                record.extension.onExtensionEvent(event)
                listeners.toList().forEach { it.onExtensionEvent(event) }
            }
        }
    }

    // ==================== helpers ====================

    private fun reportError(
        extension: ChartExtension,
        code: String,
        message: String,
        detail: String = "",
        stackTrace: String = ""
    ) {
        val error = ChartExtensionError(
            extensionId = extension.descriptor.extensionId,
            chartInstanceId = chartInstanceId,
            code = code,
            message = message,
            detail = detail,
            stackTrace = stackTrace
        )
        Logger.e("Extension error: $error")
        extension.onExtensionError(error)
        listeners.toList().forEach { it.onExtensionError(error) }
    }

    private fun callJs(functionName: String, params: Map<String, Any>) {
        try {
            controller.callFunction(functionName, params)
        } catch (e: RuntimeException) {
            Logger.w("Failed to call extension bridge '$functionName': ${e.message}")
        }
    }

    private fun paramsOf(extensionId: String, seriesId: String? = null): Map<String, Any> {
        val params = mutableMapOf<String, Any>(
            PARAM_EXTENSION_ID to extensionId,
            PARAM_CHART_INSTANCE_ID to chartInstanceId
        )
        seriesId?.let { params[PARAM_SERIES_ID] = it }
        return params
    }

    private fun assetExists(path: String): Boolean {
        return try {
            assets.open(path).use { }
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ChartExtensionHost must be used from the main thread"
        }
    }

    private inner class ContextImpl(
        private val descriptor: ChartExtensionDescriptor
    ) : ChartExtensionContext {
        override val extensionId: String get() = descriptor.extensionId
        override val chartInstanceId: String get() = this@ChartExtensionHostImpl.chartInstanceId

        override fun send(messageType: String, payloadJson: String): Boolean =
            sendMessage(descriptor.extensionId, messageType, payloadJson)

        override fun attachSeries(seriesId: String) {
            this@ChartExtensionHostImpl.attachSeries(descriptor.extensionId, seriesId)
        }

        override fun detachSeries() {
            this@ChartExtensionHostImpl.detachSeries(descriptor.extensionId)
        }

        override fun dispose() {
            this@ChartExtensionHostImpl.unregisterExtension(descriptor.extensionId)
        }

        override fun isReady(): Boolean = this@ChartExtensionHostImpl.isExtensionReady(descriptor.extensionId)
    }

    private class ExtensionRecord(
        val extension: ChartExtension,
        val context: ChartExtensionContext
    ) {
        val descriptor: ChartExtensionDescriptor get() = extension.descriptor
        var ready: Boolean = false
        var readyNotified: Boolean = false
        var registrationInFlight: Boolean = false
        var registrationTimeout: Runnable? = null
        var seriesId: String? = null
        var pendingSeriesId: String? = null
        val pendingMessages = ArrayDeque<BufferedMessage>()
    }

    private data class BufferedMessage(val messageType: String, val payloadJson: String)

    private companion object {
        const val FUNCTION_REGISTER = "chartExtension.register"
        const val FUNCTION_ATTACH_SERIES = "chartExtension.attachSeries"
        const val FUNCTION_DETACH_SERIES = "chartExtension.detachSeries"
        const val FUNCTION_MESSAGE = "chartExtension.message"
        const val FUNCTION_UNREGISTER = "chartExtension.unregister"
        const val FUNCTION_EVENTS = "chartExtension.events"

        const val PARAM_EXTENSION_ID = "extensionId"
        const val PARAM_CHART_INSTANCE_ID = "chartInstanceId"
        const val PARAM_BUNDLE_URL = "bundleUrl"
        const val PARAM_INIT_PAYLOAD = "initPayload"
        const val PARAM_SERIES_ID = "seriesId"
        const val PARAM_MESSAGE_TYPE = "messageType"
        const val PARAM_PAYLOAD = "payload"

        const val RESERVED_READY = "extension.ready"
        const val RESERVED_SERIES_ATTACHED = "extension.seriesAttached"
        const val RESERVED_SERIES_DETACHED = "extension.seriesDetached"
        const val RESERVED_ERROR = "extension.error"
        const val RESERVED_DISPOSED = "extension.disposed"

        const val ERROR_ASSET_NOT_FOUND = "EXTENSION_ASSET_NOT_FOUND"
        const val ERROR_BUNDLE_LOAD_FAILED = "EXTENSION_BUNDLE_LOAD_FAILED"
        const val ERROR_BRIDGE_UNAVAILABLE = "EXTENSION_BRIDGE_UNAVAILABLE"
        const val ERROR_QUEUE_FULL = "EXTENSION_QUEUE_FULL"
        const val ERROR_RUNTIME_TIMEOUT = "EXTENSION_RUNTIME_TIMEOUT"
        const val ERROR_SERIES_ATTACH_FAILED = "EXTENSION_SERIES_ATTACH_FAILED"
        const val ERROR_EXTENSION_ERROR = "EXTENSION_ERROR"

        const val MAX_PENDING_MESSAGES = 64
        const val REGISTRATION_TIMEOUT_MS = 15_000L
    }
}

// ==================== JSON helpers ====================

internal fun JsonObject.stringOrNull(name: String): String? {
    val element = get(name) ?: return null
    return if (element.isJsonPrimitive) element.asString else null
}

internal fun JsonObject.booleanOrNull(name: String, fallback: Boolean): Boolean {
    val element = get(name) ?: return fallback
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
        element.asBoolean
    } else {
        fallback
    }
}

internal fun JsonObject.longOrNull(name: String): Long? {
    val element = get(name) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asLong else null
}

internal fun payloadJsonString(payload: String, name: String): String? {
    if (payload.isBlank()) {
        return null
    }
    return try {
        com.google.gson.JsonParser.parseString(payload).let { element ->
            if (element.isJsonObject) element.asJsonObject.stringOrNull(name) else null
        }
    } catch (e: RuntimeException) {
        null
    }
}
