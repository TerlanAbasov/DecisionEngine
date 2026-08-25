# QuantPlat — Java/Spring Boot + React + PostgreSQL

A research-grade quant trading & backtesting platform. Scan a stock universe with
31+ strategies, toggle each on/off, backtest against 5+ years of Interactive
Brokers data over any date range, and get a per-strategy performance report.

Built as the **research/signal layer** upstream of a live execution engine
(e.g. a Java IB `ExecutionEngine`): this side decides *what* to trade; your
execution engine decides *how* to fill it.

- **Backend:** Java 21, Spring Boot 3.3, Spring Data JPA (Gradle build)
- **Database:** PostgreSQL (H2 for zero-setup local dev)
- **Frontend:** React 18 + Vite + Recharts
- **Data:** Interactive Brokers (pluggable) with a realistic synthetic generator for offline dev

---

## Yes, it has a database

Seven PostgreSQL tables, managed by JPA (`schema-reference.sql` documents them):

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
# backend (in-memory H2, zero setup)
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
#   H2 console: http://localhost:8080/h2-console

# frontend
cd frontend
npm install
npm run dev            # http://localhost:5173 (proxies /api to :8080)
```

For PostgreSQL locally instead of H2, start Postgres and run without the `dev`
profile (defaults: `jdbc:postgresql://localhost:5432/quantplat`, `quant`/`quant`,
override via `DB_URL`/`DB_USER`/`DB_PASS`).

On first start the app seeds the 31 strategies and a default universe, and lazily
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

## Using real Interactive Brokers data

Data access is behind the `MarketDataClient` interface with two implementations,
selected by `quantplat.data-source`:

- `synthetic` (default) — realistic offline bars, no dependencies.
- `ib` — wire your existing Java IB `ExecutionEngine` (or a TWS API client) inside
  `IbMarketDataClient.fetchHistory()`: request 5+ years of daily TRADES bars,
  map each to a `PriceBarEntity(source="ib")`, return the list. The service layer
  persists them to `price_bar`, so IB is only hit on first load or an explicit
  refresh (`POST /api/market-data/pull?symbols=AAPL,MSFT`).

Set the source via env (`QUANTPLAT_DATA-SOURCE=ib`) or `application.yml`.

---

## Strategy catalog (31 + pairs)

- **Trend (7):** sma_cross, ema_cross, macd, triple_ma, adx_trend, supertrend, kalman_trend
- **Mean reversion (6):** rsi2, rsi14, bollinger_reversion, zscore_reversion, williams_r, stochastic
- **Momentum (5):** roc_momentum, high_52w_breakout, dual_momentum, vol_scaled_momentum, rsi_momentum
- **Breakout (6):** donchian, turtle_system, keltner_breakout, atr_channel_breakout, bollinger_squeeze, nr7_breakout
- **Volume (3):** obv_trend, vwap_reversion, volume_spike_breakout
- **Hybrid/pattern/seasonal (4):** macd_rsi_combo, dual_ma_atr_stop, gap_reversion, seasonality_tom
- **Stat-arb:** pairs_trading (market-neutral, `POST /api/backtests/pairs`)

Add one by extending `AbstractStrategy` and adding it to `StrategyCatalog.all()` —
it then appears in the API, UI toggles, scanner, and backtests automatically.

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

---

## Layout

```
backend/   Spring Boot app
  src/main/java/com/quantplat/
    strategy/        pure-Java indicators, framework, 31 strategies (+ pairs)
    engine/          next-bar backtester, metrics
    domain/          JPA entities (7 tables)
    repository/      Spring Data repositories
    data/            MarketDataClient (synthetic + IB), MarketDataService
    service/         strategy / backtest / scanner / universe services
    web/             REST controllers
    config/          CORS + startup seeder
frontend/  React + Vite + Recharts UI (Backtest, Strategies, Universe, Scanner)
docker-compose.yml   postgres + backend + frontend
```

**Disclaimer:** for research and education. The strategies are well-known
templates, not profit guarantees; backtested results are not indicative of future
performance. Nothing here is investment advice.
