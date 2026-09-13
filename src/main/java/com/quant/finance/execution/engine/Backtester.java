package com.quant.finance.execution.engine;

import com.quant.finance.execution.strategy.BarSeries;
import com.quant.finance.execution.strategy.TradingStrategy;
import com.quant.finance.execution.strategy.impl.PairsStrategy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Vectorised, next-bar backtesting engine. */
public final class Backtester {

    private static final Logger log = LoggerFactory.getLogger(Backtester.class);

    /** Emit a per-(strategy × stock) INFO line while running a portfolio. */
    private final boolean tracePerSymbol;

    public Backtester() { this(true); }
    public Backtester(boolean tracePerSymbol) { this.tracePerSymbol = tracePerSymbol; }

    /**
     * Cap on a single bar's price move fed into P&L. A liquid instrument doesn't move ±75%
     * bar-to-bar; anything past this is a bad tick, a gap in the data, or an unadjusted
     * split, and left unclamped it detonates the equity path.
     */
    private static final double MAX_BAR_RETURN = 0.75;

    /** Close-to-close return for bar {@code i}, guarded against zero/negative prices and clamped. */
    private static double barReturn(double[] close, int i) {
        if (i <= 0) return 0;
        double p0 = close[i - 1], p1 = close[i];
        if (!(p0 > 0) || !(p1 > 0)) return 0;
        double r = p1 / p0 - 1;
        return r > MAX_BAR_RETURN ? MAX_BAR_RETURN : (r < -MAX_BAR_RETURN ? -MAX_BAR_RETURN : r);
    }

    /** Per-symbol computed series aligned to the symbol's own dates. */
    private record Series(Instant[] dates, double[] net, double[] bench,
                          double[] pos, double[] absPos, List<TradeResult> trades) {}

    /** {@link #computeSeries} plus a per-(strategy × stock) trace line. */
    private Series computeOne(BarSeries b, TradingStrategy strat,
                              Map<String, Double> params, BacktestConfig cfg, double tradeWeight) {
        long t0 = System.currentTimeMillis();
        Series s = computeSeries(b, strat, params, cfg, tradeWeight);
        if (tracePerSymbol && log.isInfoEnabled()) {
            double ret = 0;
            for (double n : s.net) if (!Double.isNaN(n)) ret += n;
            log.info("Backtest: {} × {} — {} bars, {} trades, ret {}% ({} ms)",
                    strat.name(), b.symbol, b.size(), s.trades.size(),
                    Math.round(ret * 10000) / 100.0, System.currentTimeMillis() - t0);
        }
        return s;
    }

    private Series computeSeries(BarSeries b, TradingStrategy strat,
                                 Map<String, Double> params, BacktestConfig cfg, double tradeWeight) {
        int n = b.size();
        double[] target = strat.generateSignals(b, params);
        for (int i = 0; i < n; i++) {
            if (!cfg.allowShort && target[i] < 0) target[i] = 0;
            target[i] *= cfg.positionSize;                 // scale gross exposure
        }

        double[] pos = new double[n];        // executed position (shifted by execLag)
        for (int i = 0; i < n; i++) {
            int src = i - cfg.execLag;
            pos[i] = src >= 0 ? target[src] : 0;
        }
        pos = applyRiskExits(pos, b.close, cfg);           // stop-loss / take-profit force-exits

        double[] net = new double[n], bench = new double[n], absPos = new double[n];
        double costRate = (cfg.commissionBps + cfg.slippageBps) / 1e4;
        for (int i = 0; i < n; i++) {
            double ret = barReturn(b.close, i);
            double prev = i > 0 ? pos[i - 1] : 0;
            double turnover = Math.abs(pos[i] - prev);
            net[i] = pos[i] * ret - turnover * costRate;
            bench[i] = ret;
            absPos[i] = Math.abs(pos[i]);
            if (i < cfg.warmupBars) { net[i] = 0; absPos[i] = 0; }   // ignore burn-in P&L
        }
        List<TradeResult> trades = extractTrades(pos, b.close, b.date, b.symbol, costRate, cfg.warmupBars, tradeWeight);
        return new Series(b.date, net, bench, pos, absPos, trades);
    }

    /**
     * Once an open trade's unrealised return breaches -stopLossPct or +takeProfitPct it is
     * flattened for the rest of that bar and stays flat until the strategy stops asking for
     * that same direction (a fresh signal or a flip re-arms entry).
     */
    private double[] applyRiskExits(double[] raw, double[] close, BacktestConfig cfg) {
        if (cfg.stopLossPct <= 0 && cfg.takeProfitPct <= 0) return raw;
        double sl = cfg.stopLossPct / 100.0, tp = cfg.takeProfitPct / 100.0;
        double[] out = raw.clone();
        int curDir = 0, blockedDir = 0;
        double epx = 0;
        for (int i = 0; i < raw.length; i++) {
            double want = raw[i];
            int wantDir = (int) Math.signum(want);
            if (blockedDir != 0 && wantDir != blockedDir) blockedDir = 0;
            if (blockedDir != 0) { out[i] = 0; curDir = 0; continue; }
            if (wantDir != curDir) { curDir = wantDir; epx = close[i]; out[i] = want; continue; }
            if (curDir != 0) {
                double ret = curDir * (close[i] / epx - 1);
                if ((sl > 0 && ret <= -sl) || (tp > 0 && ret >= tp)) {
                    out[i] = 0; blockedDir = curDir; curDir = 0;
                } else {
                    out[i] = want;
                }
            }
        }
        return out;
    }

    /**
     * @param weight portfolio weight of this symbol's sleeve. In a portfolio backtest the
     *               blended return is the mean of the per-symbol returns (1/N each), so each
     *               trade's return is scaled to its contribution to the whole book — that way
     *               Σ trade.returnPct reconciles with the reported total return.
     */
    private List<TradeResult> extractTrades(double[] pos, double[] close, Instant[] date,
                                            String symbol, double costRate, int fromIdx, double weight) {
        List<TradeResult> out = new ArrayList<>();
        int cur = 0, ei = -1;
        double epx = 0;
        for (int i = Math.max(0, fromIdx); i < pos.length; i++) {
            int d = (int) Math.signum(pos[i]);
            if (d != cur) {
                if (cur != 0) {
                    double ret = weight * (cur * tradeReturn(epx, close[i]) - 2 * costRate);
                    out.add(new TradeResult(symbol, cur > 0 ? "LONG" : "SHORT",
                            date[ei], date[i], round2(epx), round2(close[i]), i - ei, ret));
                }
                if (d != 0 && close[i] > 0) { cur = d; ei = i; epx = close[i]; } else { cur = 0; ei = -1; }
            }
        }
        if (cur != 0) {
            int last = pos.length - 1;
            double ret = weight * (cur * tradeReturn(epx, close[last]) - 2 * costRate);
            out.add(new TradeResult(symbol, cur > 0 ? "LONG" : "SHORT",
                    date[ei], date[last], round2(epx), round2(close[last]), last - ei, ret));
        }
        return out;
    }

    public BacktestOutput runSingle(BarSeries b, TradingStrategy strat,
                                    Map<String, Double> params, BacktestConfig cfg) {
        Series s = computeSeries(b, strat, params, cfg, 1.0);
        double[] eq = PerformanceMetrics.equityCurve(s.net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(s.bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(s.net, s.pos, s.absPos, s.bench, s.trades, cfg, s.dates);
        return new BacktestOutput(strat.name(), List.of(b.symbol), s.dates, eq, benchEq, dd, s.trades, m);
    }

    public BacktestOutput runPortfolio(List<BarSeries> data, TradingStrategy strat,
                                       Map<String, Double> params, BacktestConfig cfg) {
        return runPortfolio(data, strat, params, cfg, null);
    }

    /**
     * @param executor when non-null and there is more than one symbol, each symbol's
     *                 {@code computeSeries} (the CPU-heavy part) runs on the pool in parallel;
     *                 the (cheap) portfolio blend and metrics stay single-threaded.
     */
    public BacktestOutput runPortfolio(List<BarSeries> data, TradingStrategy strat,
                                       Map<String, Double> params, BacktestConfig cfg, Executor executor) {
        List<Series> series = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        List<TradeResult> allTrades = new ArrayList<>();
        TreeSet<Instant> allDates = new TreeSet<>();
        double tradeWeight = 1.0 / Math.max(1, data.size());   // equal-weight sleeve per symbol

        List<Series> computed;
        if (executor != null && data.size() > 1) {
            List<CompletableFuture<Series>> fs = data.stream()
                    .map(b -> CompletableFuture.supplyAsync(
                            () -> computeOne(b, strat, params, cfg, tradeWeight), executor))
                    .toList();
            computed = fs.stream().map(CompletableFuture::join).toList();
        } else {
            computed = data.stream().map(b -> computeOne(b, strat, params, cfg, tradeWeight)).toList();
        }
        for (int i = 0; i < data.size(); i++) {
            Series s = computed.get(i);
            series.add(s);
            symbols.add(data.get(i).symbol);
            allTrades.addAll(s.trades);
            allDates.addAll(Arrays.asList(s.dates));
        }
        computed = null;   // release the immutable holder; per-symbol series live in `series` now

        // Blend = equal-weight mean of the per-symbol streams on a union date axis.
        // Scatter-add each symbol's bars into the shared arrays via one index map — no
        // per-symbol Map<Instant,Double> (that boxed millions of Doubles and OOM'd the box).
        Instant[] dates = allDates.toArray(new Instant[0]);
        int nd = dates.length;
        Map<Instant, Integer> idx = new HashMap<>(nd * 2);
        for (int i = 0; i < nd; i++) idx.put(dates[i], i);

        double[] net = new double[nd], bench = new double[nd], pos = new double[nd], absPos = new double[nd];
        int[] cnt = new int[nd];
        for (Series s : series) {
            for (int i = 0; i < s.dates.length; i++) {
                Integer j = idx.get(s.dates[i]);
                if (j == null) continue;
                net[j] += s.net[i]; bench[j] += s.bench[i];
                pos[j] += s.pos[i]; absPos[j] += s.absPos[i];
                cnt[j]++;
            }
        }
        for (int i = 0; i < nd; i++) if (cnt[i] > 0) {
            net[i] /= cnt[i]; bench[i] /= cnt[i]; pos[i] /= cnt[i]; absPos[i] /= cnt[i];
        }
        series.clear();   // per-bar arrays no longer needed — free them before metrics

        double[] eq = PerformanceMetrics.equityCurve(net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(net, pos, absPos, bench, allTrades, cfg, dates);
        return new BacktestOutput(strat.name(), symbols, dates, eq, benchEq, dd, allTrades, m);
    }

    public BacktestOutput runPairs(BarSeries a, BarSeries b, PairsStrategy strat, BacktestConfig cfg) {
        // align on common dates
        Map<Instant, Integer> ib = new HashMap<>();
        for (int i = 0; i < b.date.length; i++) ib.put(b.date[i], i);
        List<Instant> common = new ArrayList<>();
        List<Double> ca = new ArrayList<>(), cb = new ArrayList<>();
        for (int i = 0; i < a.date.length; i++) {
            Integer j = ib.get(a.date[i]);
            if (j != null) { common.add(a.date[i]); ca.add(a.close[i]); cb.add(b.close[j]); }
        }
        int n = common.size();
        double[] pa = new double[n], pb = new double[n];
        for (int i = 0; i < n; i++) { pa[i] = ca.get(i); pb[i] = cb.get(i); }
        double[][] sig = strat.signals(pa, pb);
        double[] posA = new double[n], posB = new double[n];
        for (int i = 0; i < n; i++) {
            int src = i - cfg.execLag;
            posA[i] = src >= 0 ? sig[0][src] * cfg.positionSize : 0;
            posB[i] = src >= 0 ? sig[1][src] * cfg.positionSize : 0;
        }
        double[] net = new double[n], bench = new double[n], absPos = new double[n];
        double costRate = (cfg.commissionBps + cfg.slippageBps) / 1e4;
        for (int i = 0; i < n; i++) {
            double ra = barReturn(pa, i);
            double rb = barReturn(pb, i);
            double prevA = i > 0 ? posA[i - 1] : 0, prevB = i > 0 ? posB[i - 1] : 0;
            double turnover = Math.abs(posA[i] - prevA) + Math.abs(posB[i] - prevB);
            net[i] = 0.5 * posA[i] * ra + 0.5 * posB[i] * rb - turnover * costRate;
            bench[i] = 0.5 * ra + 0.5 * rb;
            absPos[i] = Math.abs(posA[i]);
            if (i < cfg.warmupBars) { net[i] = 0; absPos[i] = 0; }
        }
        Instant[] dates = common.toArray(new Instant[0]);
        double[] closeA = new double[n];
        for (int i = 0; i < n; i++) closeA[i] = pa[i];
        // net = 0.5*legA + 0.5*legB, and trades are extracted from leg A only, so weight the A-leg trades by 0.5
        List<TradeResult> trades = extractTrades(posA, closeA, dates, a.symbol + "/" + b.symbol, costRate, cfg.warmupBars, 0.5);
        double[] eq = PerformanceMetrics.equityCurve(net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(net, posA, absPos, bench, trades, cfg, dates);
        if (tracePerSymbol)
            log.info("Backtest: pairs_trading × {}/{} — {} common bars, {} trades", a.symbol, b.symbol, n, trades.size());
        return new BacktestOutput("pairs_trading", List.of(a.symbol, b.symbol), dates, eq, benchEq, dd, trades, m);
    }

    /** Point-to-point trade return, guarded against a bad entry/exit price. */
    private static double tradeReturn(double entryPx, double exitPx) {
        return (entryPx > 0 && exitPx > 0) ? exitPx / entryPx - 1 : 0;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
