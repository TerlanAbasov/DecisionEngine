# QuantPlat — Java/Spring Boot + React + PostgreSQL

A research-grade quant trading & backtesting platform. Scan a stock universe with
100 strategies, toggle each on/off, backtest against 5+ years of market data
over any date range, and get a per-strategy performance report.

Built as the **research/signal layer** upstream of a live execution engine
(a Java IB `ExecutionEngine`): this side decides *what* to trade — a new LONG/SHORT
signal can be forwarded straight into ExecutionEngine's order pipeline (see
[ExecutionEngine integration](#executionengine-integration) below); your execution
engine decides *how* to fill it.

- **Backend:** Java 21, Spring Boot 3.3, Spring Data JPA (Gradle build)
- **Database:** PostgreSQL, versioned with Liquibase
- **Frontend:** React 18 + Vite + Recharts
- **Data:** Interactive Brokers (pluggable) with a realistic synthetic generator for offline dev

---

## Yes, it has a database

Seven PostgreSQL tables, versioned with Liquibase (`src/main/resources/changelog/`):

| Table | Purpose |
|---|---|
| `strategy_config` | Each strategy's enabled flag + parameter overrides |
| `universe_symbol` | Your scan universe (the stock list) |
| `price_bar` | Cached OHLCV market data (unique on symbol+date) |
| `backtest_run` | Parameters of each backtest execution |
| `backtest_result` | Metrics + equity/drawdown curves per run |
| `trade` | Every round-trip trade from a backtest |
| `signal` | Current scanner signals |

---

## Run it

### Option A — Docker (full stack, one command)
```bash
docker compose up --build
# frontend  http://localhost:5173
# backend   http://localhost:8080
# postgres  localhost:5432 (quant/quant)
```

### Option B — local dev
```bash
# postgres (or point DB_URL/DB_USER/DB_PASS at an existing instance)
docker run -d -p 5432:5432 -e POSTGRES_DB=quantplat -e POSTGRES_USER=quant -e POSTGRES_PASSWORD=quant postgres:16-alpine

# backend — no --spring.profiles.active needed, defaults to the `local` profile
# (application-local.yml: jdbc:postgresql://localhost:5432/quantplat, quant/quant)
cd backend
./gradlew bootRun

# frontend
cd frontend
npm install
npm run dev            # http://localhost:5173 (proxies /api to :8080)
```

Config lives in three profile files: `application.yml` (always loaded, just the app
name), `application-local.yml` (default — real Postgres with dev-friendly fallback
credentials), and `application-prod.yml` (same shape, but `DB_URL`/`DB_USER`/`DB_PASS`
are required with no fallback — run with `--spring.profiles.active=prod`).

On first start the app seeds the 100 strategies and a default universe, and lazily
generates/caches synthetic bars the first time a symbol is backtested.

---

## The stock list is yours

The universe is **not hardcoded** — it lives in the `universe_symbol` table and
you manage it from the **Universe** tab or the API:

```bash
curl localhost:8080/api/universe
curl -X PUT localhost:8080/api/universe \
     -H 'Content-Type: application/json' \
     -d '["AAPL","MSFT","JPM","XOM"]'
```

The seeded large caps only appear when the table is empty; once you set your own
list it is never overridden. Any symbol works on the synthetic source; on IB each
must be a valid contract.

---

## Real market data: Alpaca (or Interactive Brokers)

Data access is behind the `MarketDataClient` interface, selected by `quantplat.data-source`:

- `synthetic` (default) — realistic offline bars, no dependencies.
- `alpaca` — pulls daily bars from [Alpaca Market Data](https://alpaca.markets/) (`AlpacaMarketDataClient`).
  Set `ALPACA_API_KEY_ID` / `ALPACA_API_SECRET_KEY` (a free paper account works — the
  data API only needs the key pair, not a funded account). Uses the free `iex` feed by
  default; switch to `sip` via `quantplat.alpaca.feed` if you have a paid subscription.
- `ib` — wire your existing Java IB `ExecutionEngine` (or a TWS API client) inside
  `IbMarketDataClient.fetchHistory()`: request 5+ years of daily TRADES bars,
  map each to a `PriceBarEntity(source="ib")`, return the list.

The service layer caches bars in `price_bar`, so the source is only hit on first load,
an explicit refresh (`POST /api/market-data/pull?symbols=AAPL,MSFT`), or a poll tick.

Set the source via env (`QUANTPLAT_DATASOURCE=alpaca` — note: no dash, Spring's
relaxed env-var binding strips it) or `application-local.yml`.

### Polling loop

`MarketDataPoller` runs on a schedule (`quantplat.poll.interval-ms`, default 15 min):
it refreshes the universe's bars from the active data source and re-runs the strategy
scan, so `/api/signals` reflects fresh decisions without a manual `/api/scan` call. One
symbol failing to fetch doesn't stop the others. Disable with `quantplat.poll.enabled=false`.

---

## Strategy catalog (100 + pairs)

- **Trend (20):** sma_cross, ema_cross, macd, triple_ma, adx_trend, supertrend, kalman_trend,
  hull_ma_trend, dema_cross, tema_cross, vwma_trend, aroon_trend, vortex_trend, trix_signal,
  linreg_slope, parabolic_sar, ema_ribbon, elder_ray, coppock_curve, donchian_midline
- **Mean reversion (18):** rsi2, rsi14, bollinger_reversion, zscore_reversion, williams_r,
  stochastic, cci_reversion, mfi_reversion, cmo_reversion, bollinger_pctb, rsi21, rsi2_extreme,
  dpo_reversion, keltner_reversion, vwap_band_reversion, atr_band_reversion, ultimate_oscillator,
  stoch_rsi
- **Momentum (15):** roc_momentum, high_52w_breakout, dual_momentum, vol_scaled_momentum,
  rsi_momentum, multi_horizon_momentum, macd_histogram_slope, force_index, cci_momentum,
  aroon_oscillator, roc_acceleration, hull_momentum, trix_momentum, streak_momentum, ma_stack_momentum
- **Breakout (16):** donchian, turtle_system, keltner_breakout, atr_channel_breakout,
  bollinger_squeeze, nr7_breakout, cci_breakout, vortex_breakout, donchian_squeeze,
  volatility_expansion_breakout, pivot_point_breakout, camarilla_breakout,
  range_expansion_breakout, three_bar_breakout, macd_zero_cross, linreg_channel_breakout
- **Volume (10):** obv_trend, vwap_reversion, volume_spike_breakout, mfi_trend,
  chaikin_money_flow, ease_of_movement, accum_distribution, volume_price_trend,
  relative_volume_zscore, vwma_volume_cross
- **Hybrid (8):** macd_rsi_combo, dual_ma_atr_stop, triple_confirmation, trend_volume_combo,
  breakout_momentum_combo, rsi_pullback_trend_filter, adaptive_regime_switch, supertrend_rsi_combo
- **Pattern (6):** gap_reversion, inside_bar_breakout, outside_bar_reversal,
  consecutive_trend_bars, hammer_reversal, wide_range_bar_fade
- **Seasonal (7):** seasonality_tom, day_of_week_filter, january_effect, sell_in_may,
  quarter_end_effect, santa_claus_rally, mid_month_effect
- **Stat-arb:** pairs_trading (market-neutral, `POST /api/backtests/pairs`)

Add one by extending `AbstractStrategy` and adding it to `StrategyCatalog.all()` —
it then appears in the API, UI toggles, scanner, and backtests automatically.
`StrategyCatalogTest` runs every catalog entry against synthetic data on every build,
checking for exceptions, NaN leaks, and out-of-range signals.

---

## ExecutionEngine integration

`ExecutionEngineClient` forwards a decided LONG/SHORT signal to your Java IB
`ExecutionEngine`'s TradingView-webhook-shaped alert intake
(`POST /api/v1/alerts/tv-hook`), which feeds its existing alert -> order pipeline
(`AlertScheduler` -> `StrategyService` -> `TradeService` -> a live IB order). FLAT
signals are never forwarded — that webhook only models entries, not closes.

- `EXECUTION_ENGINE_URL` (`quantplat.execution-engine.base-url`) — ExecutionEngine's base
  URL (e.g. `http://localhost:8081`). Blank (default) disables forwarding entirely;
  `GET /api/execution/status` reports whether it's configured.
- `quantplat.execution-engine.auto-forward` (default `false`) — when `true`, every newly-flipped
  LONG/SHORT signal from `ScannerService.scan()` (manual `/api/scan` calls and the poller's
  scheduled scans alike) is pushed automatically. One symbol failing to forward doesn't stop
  the rest.
- `POST /api/execution/send` — push one specific signal on demand, regardless of the
  auto-forward setting. Body is a `SignalDto` (the same shape `/api/scan` and `/api/signals`
  already return).
- `quantplat.execution-engine.exchange` / `quote-currency` / `asset-class` — defaults
  (`SMART` / `USD` / `STK`) filled into the alert payload; DecisionEngine doesn't track
  these per-symbol today.

---

## Engine design

- **No look-ahead:** a strategy emits a target position at bar *t*'s close; the
  engine fills on the next bar (`execLag=1`).
- **Costs always on:** commission + slippage (bps/side) charged on turnover.
- **Portfolio backtest:** equal-weight across the universe, date-aligned.
- Metrics: CAGR, Sharpe, Sortino, Calmar, max drawdown, volatility, exposure,
  win rate, profit factor, expectancy, avg win/loss, avg bars held.

---

## REST API

| Method & path | Purpose |
|---|---|
| `GET /api/strategies` | list strategies + enabled state |
| `PUT /api/strategies/{name}/enabled?enabled=` | toggle a strategy |
| `GET/PUT /api/universe` | read / replace the stock list |
| `POST /api/market-data/pull?symbols=` | fetch & cache bars |
| `POST /api/backtests` | backtest one strategy → report |
| `POST /api/backtests/run-all` | backtest all enabled → leaderboard |
| `POST /api/backtests/pairs` | market-neutral pairs backtest |
| `GET /api/backtests/{id}` | load a saved run report |
| `GET /api/backtests?strategy=` | history of runs |
| `POST /api/scan` | current signals across enabled strategies |
| `GET /api/signals` | most recent persisted signals |
| `GET /api/execution/status` | whether ExecutionEngine forwarding is configured |
| `POST /api/execution/send` | forward one signal to ExecutionEngine now |

---

## Layout

```
backend/   Spring Boot app
  src/main/java/com/quantplat/
    strategy/        pure-Java indicators, framework, 100 strategies (+ pairs)
    engine/          next-bar backtester, metrics
    domain/          JPA entities (7 tables)
    repository/      Spring Data repositories
    data/            MarketDataClient (synthetic + Alpaca + IB), MarketDataService, MarketDataPoller
    execution/       ExecutionEngineClient — forwards signals to ExecutionEngine's alert intake
    service/         strategy / backtest / scanner / universe services
    web/             REST controllers
    config/          CORS + startup seeder
frontend/  React + Vite + Recharts UI (Backtest, Strategies, Universe, Scanner)
docker-compose.yml   postgres + backend + frontend
```

**Disclaimer:** for research and education. The strategies are well-known
templates, not profit guarantees; backtested results are not indicative of future
performance. Nothing here is investment advice.
