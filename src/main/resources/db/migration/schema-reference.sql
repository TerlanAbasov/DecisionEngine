-- Reference schema for QuantPlat (PostgreSQL).
--
-- The running app uses Hibernate ddl-auto=update to build this automatically, so
-- you do NOT need to run this by hand for local/dev use. For production, switch
-- application.yml to ddl-auto=validate, add the Flyway dependency, and rename this
-- file to V1__init.sql so migrations own the schema.

CREATE TABLE IF NOT EXISTS strategy_config (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    category    VARCHAR(255),
    direction   VARCHAR(255),
    description VARCHAR(512),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    params_json TEXT
);

CREATE TABLE IF NOT EXISTS universe_symbol (
    id     BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS price_bar (
    id       BIGSERIAL PRIMARY KEY,
    symbol   VARCHAR(255) NOT NULL,
    bar_date DATE NOT NULL,
    open     DOUBLE PRECISION,
    high     DOUBLE PRECISION,
    low      DOUBLE PRECISION,
    close    DOUBLE PRECISION,
    volume   DOUBLE PRECISION,
    source   VARCHAR(255),
    CONSTRAINT uk_symbol_date UNIQUE (symbol, bar_date)
);
CREATE INDEX IF NOT EXISTS ix_price_symbol ON price_bar (symbol);

CREATE TABLE IF NOT EXISTS backtest_run (
    id             BIGSERIAL PRIMARY KEY,
    strategy_name  VARCHAR(255) NOT NULL,
    symbols_csv    TEXT,
    start_date     DATE,
    end_date       DATE,
    capital        DOUBLE PRECISION,
    commission_bps DOUBLE PRECISION,
    slippage_bps   DOUBLE PRECISION,
    allow_short    BOOLEAN,
    status         VARCHAR(255),
    created_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_run_strategy ON backtest_run (strategy_name);

CREATE TABLE IF NOT EXISTS backtest_result (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT NOT NULL UNIQUE REFERENCES backtest_run (id),
    metrics_json  TEXT,
    dates_json    TEXT,
    equity_json   TEXT,
    benchmark_json TEXT,
    drawdown_json TEXT
);

CREATE TABLE IF NOT EXISTS trade (
    id         BIGSERIAL PRIMARY KEY,
    run_id     BIGINT NOT NULL REFERENCES backtest_run (id),
    symbol     VARCHAR(255),
    side       VARCHAR(255),
    entry_date DATE,
    exit_date  DATE,
    entry_px   DOUBLE PRECISION,
    exit_px    DOUBLE PRECISION,
    bars       INTEGER,
    return_pct DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS ix_trade_run ON trade (run_id);

CREATE TABLE IF NOT EXISTS signal (
    id             BIGSERIAL PRIMARY KEY,
    strategy_name  VARCHAR(255) NOT NULL,
    symbol         VARCHAR(255),
    signal         VARCHAR(255),
    weight         DOUBLE PRECISION,
    bars_in_state  INTEGER,
    is_new         BOOLEAN,
    close_px       DOUBLE PRECISION,
    as_of_date     DATE,
    created_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_signal_strategy ON signal (strategy_name);
CREATE INDEX IF NOT EXISTS ix_signal_symbol ON signal (symbol);
