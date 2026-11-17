# Fork-local patches (EasyTrade fork)

This fork adds generic chart-extension infrastructure consumed by `easychart`'s Drawing SDK. It
contains **no Drawing business code**; every message routed through the host is opaque.

## JavaScript source

The wrapper JavaScript source lives in `lightweightlibrary/lib` and is compiled into
`scripts/app/main.js` by `npm run compile` (webpack). The generated `scripts/` directory is
git-ignored, so changes must go into `lib/`; never edit `main.js` directly.

Fork-local source changes:

1. **New** `lib/app/extensions/extension-host.js`
   Generic `window.ChartExtensionHost`:
   - `register(extensionId, factory)` for extension bundles;
   - controlled `file:///android_asset/...` script loading (rejects other URLs and `..`);
   - `chartExtension.register / attachSeries / detachSeries / message / unregister` functions;
   - `chartExtension.events` subscription with per-instance sequence numbers;
   - reserved lifecycle events `extension.ready / seriesAttached / seriesDetached / error / disposed /
     hostInterrupted`;
   - series lifecycle observation via the existing `SeriesCache` (no polling);
   - pagehide / visibilitychange cleanup; duplicate attach guards.

2. **`lib/app/index.js`**: imports `initExtensionHost` and wires it after
   `functionsController.registerFunctions()` with the chart, function manager and series cache.

3. **`lib/app/chart-registration-functions-controller.js`**: calls `notifyChartRemoved()` after
   `chart.remove()` so runtimes are disposed before the chart disappears.

`index.html` remains unchanged (it only loads `scripts/app/main.js`); the host is part of the
compiled bundle.

When merging a new upstream release, re-apply these three source changes and keep
`extension-host.js` as-is unless the runtime bridge API changed.

## Kotlin/Java API

New package `com.tradingview.lightweightcharts.api.extension`:

- `ChartExtensionDescriptor` — extension id + asset bundle path (+ optional init payload).
- `ChartExtension` — lifecycle/event callbacks (all have default bodies in Kotlin; Java
  implementations override the ones they need).
- `ChartExtensionContext` — `send`, `attachSeries`, `detachSeries`, `dispose`, `isReady`.
- `ChartExtensionEvent` / `ChartExtensionError`.
- `ChartExtensionHost` / `ChartExtensionHostListener`.

Implementation: `runtime/extension/ChartExtensionHostImpl.kt`. `ChartsView` exposes
`val extensions: ChartExtensionHost` lazily and forwards view state changes
(`Preparing/Ready/Error`) so registered extensions are re-created after a runtime reset.

## Constraints

- Extension bundles are loaded only from merged Android assets via
  `file:///android_asset/<path>`; no arbitrary JS string execution API is exposed to callers.
- The host performs no business parsing, no drawing/indicator semantics.
- Asset existence is validated before registration; failures surface as
  `EXTENSION_ASSET_NOT_FOUND`.
- Commands issued before the JS runtime is ready are queued (bounded) and flushed in order.
- Every chart view owns an independent host instance (`chartInstanceId`) and counters.
