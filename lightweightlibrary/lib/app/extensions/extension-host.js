

var ASSET_PREFIX = 'file:///android_asset/';
var HOST_VERSION = 1;
var MAX_BUFFERED_EVENTS = 256;
var MAX_PENDING_FACTORY_WAIT_MS = 5000;

var connected = false;
var chartRemoved = false;
var functionManager = null;
var runtime = null; // { getChart, getSeriesCache, getChartInstanceId }
var eventSink = null;
var bufferedEvents = [];
var sequence = 0;
var chartInstanceId = '';
var extensions = Object.create(null);
var factories = Object.create(null);
var scriptLoads = Object.create(null);
var seriesLifecycleInstalled = false;

function log() {
    if (console && console.log) {
        var args = Array.prototype.slice.call(arguments);
        args.unshift('[ChartExtensionHost]');
        console.log.apply(console, args);
    }
}

function nowSeq() {
    sequence += 1;
    return sequence;
}

function emit(extensionId, messageType, payload) {
    var envelope = {
        chartInstanceId: chartInstanceId,
        extensionId: extensionId || '',
        messageType: messageType,
        sequence: nowSeq(),
        payload: payload === undefined || payload === null ? '' : JSON.stringify(payload)
    };
    if (eventSink) {
        try {
            eventSink(envelope);
        } catch (e) {
            log('event sink failed', e);
        }
        return;
    }
    if (bufferedEvents.length < MAX_BUFFERED_EVENTS) {
        bufferedEvents.push(envelope);
    }
}

function reportError(extensionId, code, message, detail, stack) {
    emit(extensionId, 'extension.error', {
        code: code || 'EXTENSION_ERROR',
        message: message || 'Extension error',
        detail: detail || '',
        stack: stack || ''
    });
}

function isAllowedBundleUrl(url) {
    if (typeof url !== 'string' || url.length === 0) {
        return false;
    }
    if (url.indexOf(ASSET_PREFIX) !== 0) {
        return false;
    }
    if (url.indexOf('..') >= 0) {
        return false;
    }
    return true;
}

function loadScript(url) {
    if (scriptLoads[url]) {
        return scriptLoads[url];
    }
    var promise = new Promise(function (resolve, reject) {
        if (typeof document === 'undefined') {
            reject(new Error('document is not available'));
            return;
        }
        var script = document.createElement('script');
        script.src = url;
        script.async = false;
        script.onload = function () {
            resolve();
        };
        script.onerror = function () {
            reject(new Error('failed to load extension bundle: ' + url));
        };
        (document.head || document.documentElement).appendChild(script);
    });
    scriptLoads[url] = promise;
    return promise;
}

function waitForFactory(extensionId, timeoutMs) {
    var deadline = Date.now() + (timeoutMs || MAX_PENDING_FACTORY_WAIT_MS);
    return new Promise(function (resolve, reject) {
        (function poll() {
            if (factories[extensionId]) {
                resolve(factories[extensionId]);
                return;
            }
            if (Date.now() > deadline) {
                reject(new Error('extension bundle did not call ChartExtensionHost.register: ' + extensionId));
                return;
            }
            setTimeout(poll, 16);
        })();
    });
}

function resolveSeries(seriesId) {
    if (!runtime || typeof runtime.getSeriesCache !== 'function') {
        return null;
    }
    var cache = runtime.getSeriesCache();
    if (!cache || typeof cache.get !== 'function') {
        return null;
    }
    return cache.get(seriesId) || null;
}

function createContext(record) {
    return {
        extensionId: record.extensionId,
        chartInstanceId: chartInstanceId,
        hostVersion: HOST_VERSION,
        getChart: function () {
            return runtime && typeof runtime.getChart === 'function' ? runtime.getChart() : null;
        },
        getSeries: function () {
            return record.series;
        },
        getTimeScale: function () {
            var chart = this.getChart();
            return chart ? chart.timeScale() : null;
        },
        requestUpdate: function () {
            if (record.requestUpdate) {
                try {
                    record.requestUpdate();
                } catch (e) {
                    log('requestUpdate failed', e);
                }
            }
        },
        emit: function (messageType, payload) {
            if (typeof messageType !== 'string' || messageType.length === 0) {
                reportError(record.extensionId, 'EXTENSION_INVALID_MESSAGE', 'messageType must be a non-empty string');
                return;
            }
            if (messageType.indexOf('extension.') === 0) {
                reportError(record.extensionId, 'EXTENSION_RESERVED_MESSAGE', 'extension.* message types are reserved');
                return;
            }
            emit(record.extensionId, messageType, payload);
        },
        reportError: function (code, message, detail) {
            reportError(record.extensionId, code, message, detail);
        },
        log: function () {
            if (console && console.log) {
                var args = Array.prototype.slice.call(arguments);
                args.unshift('[' + record.extensionId + ']');
                console.log.apply(console, args);
            }
        }
    };
}

function createRecord(extensionId, factory) {
    var record = {
        extensionId: extensionId,
        api: null,
        series: null,
        seriesId: null,
        requestUpdate: null,
        disposed: false
    };
    record.context = createContext(record);
    try {
        record.api = factory(record.context) || {};
    } catch (e) {
        throw new Error('extension factory failed: ' + (e && e.message ? e.message : e));
    }
    return record;
}

function disposeRecord(record, reason, notifyJs) {
    if (!record || record.disposed) {
        return;
    }
    record.disposed = true;
    if (record.requestUpdate) {
        record.requestUpdate = null;
    }
    try {
        if (record.api && typeof record.api.onDispose === 'function') {
            record.api.onDispose(reason);
        }
    } catch (e) {
        log('onDispose failed', e);
    }
    record.series = null;
    record.seriesId = null;
    if (notifyJs) {
        emit(record.extensionId, 'extension.disposed', { reason: reason || '' });
    }
}

function disposeAll(reason) {
    Object.keys(extensions).forEach(function (extensionId) {
        disposeRecord(extensions[extensionId], reason, false);
        delete extensions[extensionId];
    });
}

function notifySeriesRemoved(seriesId) {
    Object.keys(extensions).forEach(function (extensionId) {
        var record = extensions[extensionId];
        if (record.seriesId !== seriesId) {
            return;
        }
        record.series = null;
        record.seriesId = null;
        record.requestUpdate = null;
        try {
            if (record.api && typeof record.api.onSeriesDetached === 'function') {
                record.api.onSeriesDetached(seriesId, 'seriesRemoved');
            }
        } catch (e) {
            log('onSeriesDetached failed', e);
        }
        emit(extensionId, 'extension.seriesDetached', { seriesId: seriesId, reason: 'seriesRemoved' });
    });
}

function installSeriesLifecycleHooks() {
    if (seriesLifecycleInstalled || !runtime || typeof runtime.getSeriesCache !== 'function') {
        return;
    }
    var cache = runtime.getSeriesCache();
    if (!cache || typeof cache.delete !== 'function') {
        return;
    }
    seriesLifecycleInstalled = true;
    var originalDelete = cache.delete;
    var originalClear = cache.clear;
    cache.delete = function (key) {
        var existed = typeof this.has === 'function' ? this.has(key) : false;
        var result = originalDelete.call(this, key);
        if (existed) {
            notifySeriesRemoved(key);
        }
        return result;
    };
    cache.clear = function () {
        var keys = [];
        this.forEach(function (value, key) {
            keys.push(key);
        });
        var result = originalClear.call(this);
        keys.forEach(notifySeriesRemoved);
        return result;
    };
}

function flushBufferedEvents() {
    var pending = bufferedEvents;
    bufferedEvents = [];
    pending.forEach(function (envelope) {
        try {
            eventSink(envelope);
        } catch (e) {
            log('event sink failed', e);
        }
    });
}

// ==================== Java -> JS functions ====================

function registerHandler(data, resolve) {
    var params = (data && data.params) || {};
    var extensionId = params.extensionId;
    var bundleUrl = params.bundleUrl;
    if (typeof extensionId !== 'string' || extensionId.length === 0) {
        resolve({ ok: false, code: 'EXTENSION_INVALID_ID', message: 'extensionId is required' });
        return;
    }
    if (typeof params.chartInstanceId === 'string' && params.chartInstanceId.length > 0) {
        chartInstanceId = params.chartInstanceId;
    }
    if (extensions[extensionId]) {
        resolve({ ok: true, extensionId: extensionId, reused: true });
        return;
    }
    if (chartRemoved) {
        resolve({ ok: false, code: 'EXTENSION_CHART_REMOVED', message: 'chart was removed' });
        return;
    }
    if (!isAllowedBundleUrl(bundleUrl)) {
        var message = 'extension bundle must be a file:///android_asset URL without "..": ' + bundleUrl;
        reportError(extensionId, 'EXTENSION_BUNDLE_URL_REJECTED', message);
        resolve({ ok: false, code: 'EXTENSION_BUNDLE_URL_REJECTED', message: message });
        return;
    }

    var loading = factories[extensionId]
        ? Promise.resolve(factories[extensionId])
        : loadScript(bundleUrl).then(function () {
            return waitForFactory(extensionId, MAX_PENDING_FACTORY_WAIT_MS);
        });

    loading.then(function (factory) {
        var record;
        try {
            record = createRecord(extensionId, factory);
        } catch (e) {
            reportError(extensionId, 'EXTENSION_BUNDLE_LOAD_FAILED', String(e && e.message ? e.message : e));
            resolve({ ok: false, code: 'EXTENSION_BUNDLE_LOAD_FAILED', message: String(e && e.message ? e.message : e) });
            return;
        }
        extensions[extensionId] = record;
        resolve({ ok: true, extensionId: extensionId });
        emit(extensionId, 'extension.ready', { hostVersion: HOST_VERSION });
        if (params.initPayload) {
            try {
                if (typeof record.api.onInit === 'function') {
                    record.api.onInit(String(params.initPayload));
                }
            } catch (e) {
                reportError(extensionId, 'EXTENSION_INIT_FAILED', String(e && e.message ? e.message : e));
            }
        }
    }).catch(function (error) {
        var text = String(error && error.message ? error.message : error);
        reportError(extensionId, 'EXTENSION_BUNDLE_LOAD_FAILED', text);
        resolve({ ok: false, code: 'EXTENSION_BUNDLE_LOAD_FAILED', message: text });
    });
}

function attachSeriesHandler(data, resolve) {
    var params = (data && data.params) || {};
    var record = extensions[params.extensionId];
    if (!record) {
        resolve({ ok: false, code: 'EXTENSION_NOT_FOUND', message: 'extension is not registered' });
        return;
    }
    var seriesId = params.seriesId;
    var series = resolveSeries(seriesId);
    if (!series) {
        resolve({ ok: false, code: 'EXTENSION_SERIES_NOT_FOUND', message: 'series is not available: ' + seriesId });
        return;
    }
    if (record.seriesId === seriesId && record.series === series) {
        resolve({ ok: true, reused: true });
        return;
    }
    if (record.series && record.seriesId && record.seriesId !== seriesId) {
        try {
            if (record.api && typeof record.api.onSeriesDetached === 'function') {
                record.api.onSeriesDetached(record.seriesId, 'rebind');
            }
        } catch (e) {
            log('onSeriesDetached failed', e);
        }
    }
    record.series = series;
    record.seriesId = seriesId;
    try {
        var result = record.api && typeof record.api.onSeriesAttached === 'function'
            ? record.api.onSeriesAttached(series, record.context.getChart())
            : null;
        if (result && typeof result.requestUpdate === 'function') {
            record.requestUpdate = result.requestUpdate;
        }
    } catch (e) {
        record.series = null;
        record.seriesId = null;
        var text = String(e && e.message ? e.message : e);
        reportError(record.extensionId, 'EXTENSION_SERIES_ATTACH_FAILED', text, '', e && e.stack);
        resolve({ ok: false, code: 'EXTENSION_SERIES_ATTACH_FAILED', message: text });
        return;
    }
    resolve({ ok: true });
    emit(record.extensionId, 'extension.seriesAttached', { seriesId: seriesId });
}

function detachSeriesHandler(data, resolve) {
    var params = (data && data.params) || {};
    var record = extensions[params.extensionId];
    if (!record) {
        resolve({ ok: false, code: 'EXTENSION_NOT_FOUND', message: 'extension is not registered' });
        return;
    }
    var seriesId = record.seriesId;
    try {
        if (record.api && typeof record.api.onSeriesDetached === 'function') {
            record.api.onSeriesDetached(seriesId, 'detached');
        }
    } catch (e) {
        log('onSeriesDetached failed', e);
    }
    record.series = null;
    record.seriesId = null;
    record.requestUpdate = null;
    resolve({ ok: true });
    emit(record.extensionId, 'extension.seriesDetached', { seriesId: seriesId });
}

function messageHandler(data, resolve) {
    var params = (data && data.params) || {};
    var record = extensions[params.extensionId];
    if (!record) {
        resolve({ ok: false, code: 'EXTENSION_NOT_FOUND', message: 'extension is not registered' });
        return;
    }
    try {
        if (record.api && typeof record.api.onMessage === 'function') {
            record.api.onMessage(params.messageType, params.payload || '');
        }
        resolve({ ok: true });
    } catch (e) {
        var text = String(e && e.message ? e.message : e);
        reportError(record.extensionId, 'EXTENSION_MESSAGE_FAILED', text, params.messageType, e && e.stack);
        resolve({ ok: false, code: 'EXTENSION_MESSAGE_FAILED', message: text });
    }
}

function unregisterHandler(data, resolve) {
    var params = (data && data.params) || {};
    var record = extensions[params.extensionId];
    if (record) {
        disposeRecord(record, 'unregistered', true);
        delete extensions[params.extensionId];
    }
    resolve({ ok: true });
}

function registerBridgeFunctions() {
    functionManager.registerFunction('chartExtension.register', registerHandler);
    functionManager.registerFunction('chartExtension.attachSeries', attachSeriesHandler);
    functionManager.registerFunction('chartExtension.detachSeries', detachSeriesHandler);
    functionManager.registerFunction('chartExtension.message', messageHandler);
    functionManager.registerFunction('chartExtension.unregister', unregisterHandler);
    functionManager.registerSubscription(
        'chartExtension.events',
        function (data, callback) {
            eventSink = callback;
            flushBufferedEvents();
            return callback;
        },
        function () {
            eventSink = null;
        }
    );
}

// ==================== public API ====================

var ChartExtensionHost = {
    version: HOST_VERSION,

    /**
     * Called by extension bundles while they are evaluated.
     * @param {string} extensionId stable extension id
     * @param {function} factory receives the extension context, returns the extension api
     */
    register: function (extensionId, factory) {
        if (typeof extensionId !== 'string' || extensionId.length === 0) {
            throw new Error('extensionId must be a non-empty string');
        }
        if (typeof factory !== 'function') {
            throw new Error('extension factory must be a function');
        }
        if (factories[extensionId]) {
            return;
        }
        factories[extensionId] = factory;
    },

    /** True after the native runtime connected the host. */
    isConnected: function () {
        return connected;
    },

    /**
     * Wires the host to the chart runtime. Called by the chart bundle once the bridge is ready.
     * Must only be called by the chart runtime boot code.
     */
    __connect: function (impl) {
        if (connected) {
            return;
        }
        if (!impl || !impl.getFunctionManager) {
            throw new Error('ChartExtensionHost.__connect requires getFunctionManager');
        }
        functionManager = impl.getFunctionManager();
        if (!functionManager) {
            throw new Error('function manager is not available');
        }
        runtime = {
            getChart: impl.getChart,
            getSeriesCache: impl.getSeriesCache,
            getChartInstanceId: impl.getChartInstanceId
        };
        if (typeof impl.getChartInstanceId === 'function') {
            var provided = impl.getChartInstanceId();
            if (typeof provided === 'string' && provided.length > 0) {
                chartInstanceId = provided;
            }
        }
        registerBridgeFunctions();
        installSeriesLifecycleHooks();
        connected = true;
        chartRemoved = false;
        log('connected, hostVersion=' + HOST_VERSION);
    },

    /** Called by the chart runtime right before the chart instance is removed. */
    __onChartRemoved: function () {
        chartRemoved = true;
        disposeAll('chartRemoved');
        eventSink = null;
        connected = false;
        functionManager = null;
        runtime = null;
        seriesLifecycleInstalled = false;
    },

    /** Called by the chart runtime when the page becomes hidden. */
    __onHostInterrupted: function (reason) {
        Object.keys(extensions).forEach(function (extensionId) {
            var record = extensions[extensionId];
            try {
                if (record.api && typeof record.api.onHostInterrupted === 'function') {
                    record.api.onHostInterrupted(reason || 'hidden');
                }
            } catch (e) {
                log('onHostInterrupted failed', e);
            }
            emit(extensionId, 'extension.hostInterrupted', { reason: reason || 'hidden' });
        });
    },

    /** Called by the chart runtime when the page becomes visible again. */
    __onHostResumed: function () {
        Object.keys(extensions).forEach(function (extensionId) {
            var record = extensions[extensionId];
            try {
                if (record.api && typeof record.api.onHostResumed === 'function') {
                    record.api.onHostResumed();
                }
            } catch (e) {
                log('onHostResumed failed', e);
            }
        });
    },

    /** Test hook: only for unit tests / development tooling. */
    __registerFactoryForTest: function (extensionId, factory) {
        factories[extensionId] = factory;
    },

    /** Test hook: returns the live extension record (or null). */
    __getRecordForTest: function (extensionId) {
        return extensions[extensionId] || null;
    }
};

// The host is exposed globally so extension bundles loaded later can call register().
window.ChartExtensionHost = ChartExtensionHost;

function installPageLifecycleHooks() {
    // Release every runtime when the page is hidden; Java owns the recovery snapshot.
    window.addEventListener('pagehide', function () {
        disposeAll('pageHidden');
    });
    window.addEventListener('beforeunload', function () {
        disposeAll('pageUnload');
    });
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') {
            ChartExtensionHost.__onHostInterrupted('pageHidden');
        } else {
            ChartExtensionHost.__onHostResumed();
        }
    });
}

installPageLifecycleHooks();

export function initExtensionHost(impl) {
    ChartExtensionHost.__connect(impl);
}

export function notifyChartRemoved() {
    ChartExtensionHost.__onChartRemoved();
}

export default ChartExtensionHost;