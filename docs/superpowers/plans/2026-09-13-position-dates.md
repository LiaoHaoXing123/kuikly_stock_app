# Period refresh and dated portfolio calendar

- Reproduce professional chart D/W/M changes through the native-to-WebView payload; display period and actual candle count inside the chart.
- Persist an optional startDate per WatchHolding. Require it when saving a positive position in the watchlist. Legacy undated positions require explicit completion before calendar calculation.
- Rebuild calendar estimates from each position's own start date, quantity and cost. First eligible trading day uses cost; subsequent days use previous close. Sum across stocks, and omit incomplete portfolio days instead of showing partial totals as complete.
- Recompute when holdings change; do not reuse pre-date legacy estimates. This is a current-position estimate, not a transaction ledger or accounting for adds/sells/dividends.
- Test mixed start dates, missing history, first-day cost, persistence and changed holdings. Build/install to the running verified Pixel_6_35 (currently emulator-5554) and inspect period switching and the date form.

Validation: Gradle shared unit tests and Android debug assembly passed (167 tests, zero failures/errors/skips); adb install -r succeeded on emulator-5554. JavaScript syntax checked with Node. Runtime chart logs show D=21, W=5, M=2 before the network backfill; the chart now labels these counts and resizes on each period. Longer history retention and weekly/monthly aggregation regression covered by unit tests. History length remains dependent on the provider response.

UI: watchlist date form rendered and invalid 2026-02-30 was rejected on save. The dummy form was cancelled; no fabricated position was saved. Multi-stock calculation, different entry dates, first-day basis, missing quotes and recomputation exercised with deterministic tests. Dated calendar uses a v2 cache, preserving legacy v1 cache without presenting legacy undated estimates.
