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
- **Data:** [Alpaca Market Data](https://alpaca.markets/)

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
fetches/caches bars from Alpaca the first time a symbol is backtested.

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
list it is never overridden. Each symbol must be a valid Alpaca-tradable ticker.

---

## Real market data: Alpaca

Data access is behind the `MarketDataClient` interface, with `AlpacaMarketDataClient`
as the sole implementation — it pulls bars from
[Alpaca Market Data](https://alpaca.markets/). Set `ALPACA_API_KEY_ID` /
`ALPACA_API_SECRET_KEY` (a free paper account works — the data API only needs the key
pair, not a funded account). Uses the free `iex` feed by default; switch to `sip` via
`quantplat.alpaca.feed` if you have a paid subscription.

Bar granularity is `quantplat.alpaca.timeframe` (default `1Day`), passed straight through
to Alpaca's bars API — set it to `1Week` (weekly), `1Hour` (hourly), or a minute value
like `1Min`/`5Min`/`15Min` if you need intraday data. `price_bar.bar_time` stores a full
UTC timestamp (not just a calendar date) so multiple bars per day don't collide, and
`BarSeries.date` carries that same timestamp through every strategy and the backtester.

The service layer caches bars in `price_bar`, so Alpaca is only hit on first load,
an explicit refresh (`POST /api/market-data/pull?symbols=AAPL,MSFT`), or a poll tick.

### Polling loop

`MarketDataPoller` runs on a schedule (`quantplat.poll.interval-ms`, default 15 min):
it refreshes the universe's bars from Alpaca and re-runs the strategy scan, so
`/api/signals` reflects fresh decisions without a manual `/api/scan` call. One symbol
failing to fetch doesn't stop the others. Disable with `quantplat.poll.enabled=false`.

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

## ExecutionEngine integration (auto trading)

Package `com.quant.finance.decision.autotrade`. A job watches every enabled strategy on every Universe
symbol **in the strategy's own timeframe** and, when one turns LONG or SHORT on a bar that has just
completed, sends ExecutionEngine's `TradeController` (`POST /api/v1/trades/command`) a `BUY` / `SELL`
for the configured quantity. It is **off by default** and switched on and off from the *Auto Trading* tab
(or `POST /api/autotrade/enable` / `disable`).

- `EXECUTION_ENGINE_URL` (`decision.execution-engine.url`) — ExecutionEngine's base URL (e.g.
  `http://localhost:8081`). Blank disables everything: the job cannot be switched on and nothing is sent.
- `decision.autotrade.tick-seconds` (default `30`) — how often the job looks for new bars. What it fetches on a
  tick is decided per symbol and timeframe: a 15-minute strategy is looked at once its next bar is complete,
  a daily one about once a day.
- Settings (saved in the database, edited in the UI): quantity in shares, order type (`MKT` or `LMT` at the signal
  bar's close), time in force, an optional strategy and symbol list (empty = all enabled strategies / the whole
  Universe) and a cap on commands per run.

How it stays safe:

- Only **new** signals count: a strategy whose position did not change sends nothing, and a strategy going FLAT
  sends nothing (ExecutionEngine has no per-symbol close command). Bars that completed before the job was switched
  on, and signals more than two bars old, are ignored.
- Every command is written to `auto_trade_command` **before** it is sent, unique per (strategy, symbol, timeframe, bar),
  so a bar can never trigger the same command twice, not even across a restart. A command is never retried.
- ExecutionEngine reports failures in the text of an HTTP 200 reply, so the reply is judged, not just the status. Each
  command ends `SENT`, `REJECTED` (it refused), `FAILED` (no answer: it may or may not have arrived) or `SKIPPED` (over the
  per-run limit). The Auto Trading tab lists them with ExecutionEngine's answer.
- `POST /api/autotrade/forward` sends one scanned signal by hand (the Scanner's *Forward* button).

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
    data/            MarketDataClient (Alpaca), MarketDataService, MarketDataPoller
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
