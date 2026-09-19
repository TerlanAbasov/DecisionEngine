package com.quant.finance.decision.engine;

import com.quant.finance.decision.strategy.BarSeries;
import com.quant.finance.decision.strategy.TradingStrategy;
import com.quant.finance.decision.strategy.impl.PairsStrategy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import lombok.extern.slf4j.Slf4j;

/** Vectorised, next-bar backtesting engine. */
@Slf4j
public final class Backtester {

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
    private record Series(Instant[] dates, double[] close, double[] net, double[] bench,
                          double[] pos, double[] absPos, List<TradeResult> trades) {}

    /** {@link #computeSeries} plus a per-(strategy × stock) trace line. */
    private Series computeOne(BarSeries b, TradingStrategy strat,
                              Map<String, Double> params, BacktestConfig cfg) {
        long t0 = System.currentTimeMillis();
        Series s = computeSeries(b, strat, params, cfg);
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
                                 Map<String, Double> params, BacktestConfig cfg) {
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
        List<TradeResult> trades = extractTrades(pos, b.close, b.date, b.symbol, costRate, cfg.warmupBars, null);
        return new Series(b.date, b.close, net, bench, pos, absPos, trades);
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
     * Splits one symbol's executed position path into round trips that add up exactly to that
     * symbol's equity accounting (the same {@code pos}, bar returns and cost model as
     * {@link #computeSeries}), so {@code Σ trade.netReturn == Σ net}.
     *
     * <p>Position {@code pos[i]} earns bar {@code i}'s return, i.e. the move from
     * {@code close[i-1]} to {@code close[i]} — the fill is the close of the bar <em>before</em>
     * the first held bar. So a trade held over bars {@code ei..x-1} entered at {@code close[ei-1]}
     * and exited at {@code close[x-1]}; the timestamps are the open of bar {@code ei} / {@code x}
     * (= the close of the previous bar). Turnover cost is charged where it is in the equity
     * accounting: on the bar the position changes, split between the trade that ends and the
     * one that begins. A trade still open on the last bar is marked at that close with no exit
     * cost yet, again as in the equity.
     *
     * <p>{@code netReturn} and friends are per-symbol standalone (weight 1). {@code returnPct} is
     * the trade's contribution to a blend: every bar's gross P&amp;L and cost is scaled by that bar's
     * weight {@code barWeight[i]} ({@code null} = 1, the standalone view), so it stays exact when the
     * weight changes mid-trade (a symbol whose history starts or ends while the trade is open).
     * The one unattributed sliver: with {@code warmupBars > 0}
     * the exit cost of a position already held when warm-up ends belongs to a trade that started
     * inside the (ignored) warm-up, so it isn't in any trade.
     */
    private List<TradeResult> extractTrades(double[] pos, double[] close, Instant[] date,
                                            String symbol, double costRate, int warmupBars, double[] barWeight) {
        List<TradeResult> out = new ArrayList<>();
        int n = pos.length;
        int side = 0, entryBar = -1, held = 0;
        double gross = 0, cost = 0, exposure = 0, weighted = 0;
        for (int i = Math.max(0, warmupBars); i < n; i++) {
            double cur = pos[i];
            double prev = i > 0 ? pos[i - 1] : 0;
            int d = (int) Math.signum(cur);
            double w = barWeight == null ? 1.0 : barWeight[i];
            if (side != 0 && d != side) {
                // the open trade ends: its last held bar is i-1, so close[i-1] is the exit fill
                double exitCost = Math.abs(prev) * costRate;
                cost += exitCost; weighted -= w * exitCost;
                out.add(trade(symbol, side, date, close, entryBar, i - 1, i, held, gross, cost, exposure, weighted, false));
                side = 0;
            }
            if (d == 0) continue;
            if (side == 0) {
                side = d; entryBar = i; held = 0; gross = 0; exposure = Math.abs(cur);
                // entry cost: the whole position, unless it was already held at the same sign
                cost = Math.abs(cur - ((int) Math.signum(prev) == d ? prev : 0)) * costRate;
                weighted = -w * cost;
            } else {
                double resize = Math.abs(cur - prev) * costRate;   // resized while held
                cost += resize; weighted -= w * resize;
            }
            double barGross = cur * barReturn(close, i);
            gross += barGross; weighted += w * barGross;
            held++;
        }
        if (side != 0) out.add(trade(symbol, side, date, close, entryBar, n - 1, n - 1, held, gross, cost, exposure, weighted, true));
        return out;
    }

    private static TradeResult trade(String symbol, int side, Instant[] date, double[] close,
                                     int entryBar, int lastHeldBar, int exitDateBar, int held,
                                     double gross, double cost, double exposure, double returnPct, boolean open) {
        return new TradeResult(symbol, side > 0 ? "LONG" : "SHORT",
                date[entryBar], date[exitDateBar],
                close[Math.max(entryBar - 1, 0)], close[lastHeldBar],
                held, returnPct, gross, cost, exposure, open);
    }

    /**
     * Price-based round trips for the pairs strategy only (leg A, weight 0.5): a different
     * accounting model from {@link #extractTrades} — trade return from entry/exit price with a
     * flat round-trip cost. Kept as it was; pairs trades do not reconcile with the pairs equity.
     */
    private List<TradeResult> extractPairTrades(double[] pos, double[] close, Instant[] date,
                                                String symbol, double costRate, int fromIdx, double weight) {
        List<TradeResult> out = new ArrayList<>();
        int cur = 0, ei = -1;
        double epx = 0;
        for (int i = Math.max(0, fromIdx); i < pos.length; i++) {
            int d = (int) Math.signum(pos[i]);
            if (d != cur) {
                if (cur != 0) out.add(pairTrade(symbol, cur, date[ei], date[i], epx, close[i], i - ei,
                        costRate, weight, Math.abs(pos[ei]), false));
                if (d != 0 && close[i] > 0) { cur = d; ei = i; epx = close[i]; } else { cur = 0; ei = -1; }
            }
        }
        if (cur != 0) {
            int last = pos.length - 1;
            out.add(pairTrade(symbol, cur, date[ei], date[last], epx, close[last], last - ei,
                    costRate, weight, Math.abs(pos[ei]), true));
        }
        return out;
    }

    private static TradeResult pairTrade(String symbol, int side, Instant entry, Instant exit, double epx,
                                         double xpx, int bars, double costRate, double weight,
                                         double exposure, boolean open) {
        double gross = side * tradeReturn(epx, xpx), cost = 2 * costRate;
        return new TradeResult(symbol, side > 0 ? "LONG" : "SHORT", entry, exit, epx, xpx, bars,
                weight * (gross - cost), gross, cost, exposure, open);
    }

    public BacktestOutput runSingle(BarSeries b, TradingStrategy strat,
                                    Map<String, Double> params, BacktestConfig cfg) {
        Series s = computeSeries(b, strat, params, cfg);
        double[] eq = PerformanceMetrics.equityCurve(s.net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(s.bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(s.net, s.pos, s.absPos, s.bench, s.trades, cfg, s.dates);
        Map<String, Double> yearly = PerformanceMetrics.yearlyReturnsPct(s.net, s.dates);
        return new BacktestOutput(strat.name(), List.of(b.symbol), s.dates, eq, benchEq, dd, s.trades, m,
                Map.of(b.symbol, m.get("totalReturnPct")),
                Map.of(b.symbol, new SymbolResult(m, yearly)), yearly);
    }

    public BacktestOutput runPortfolio(List<BarSeries> data, TradingStrategy strat,
                                       Map<String, Double> params, BacktestConfig cfg) {
        return runPortfolio(data, strat, params, cfg, null, true);
    }

    public BacktestOutput runPortfolio(List<BarSeries> data, TradingStrategy strat,
                                       Map<String, Double> params, BacktestConfig cfg, Executor executor) {
        return runPortfolio(data, strat, params, cfg, executor, true);
    }

    /**
     * @param executor when non-null and there is more than one symbol, each symbol's
     *                 {@code computeSeries} (the CPU-heavy part) runs on the pool in parallel;
     *                 the (cheap) portfolio blend and metrics stay single-threaded.
     * @param symbolDetails compute each symbol's own metrics / yearly returns. Off for callers that
     *                 only read the portfolio (parameter sweeps run this thousands of times and
     *                 would discard them); the per-symbol maps are then empty.
     */
    public BacktestOutput runPortfolio(List<BarSeries> data, TradingStrategy strat,
                                       Map<String, Double> params, BacktestConfig cfg, Executor executor,
                                       boolean symbolDetails) {
        List<Series> series = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        TreeSet<Instant> allDates = new TreeSet<>();

        List<Series> computed;
        if (executor != null && data.size() > 1) {
            List<CompletableFuture<Series>> fs = data.stream()
                    .map(b -> CompletableFuture.supplyAsync(
                            () -> computeOne(b, strat, params, cfg), executor))
                    .toList();
            computed = fs.stream().map(CompletableFuture::join).toList();
        } else {
            computed = data.stream().map(b -> computeOne(b, strat, params, cfg)).toList();
        }
        for (int i = 0; i < data.size(); i++) {
            Series s = computed.get(i);
            series.add(s);
            symbols.add(data.get(i).symbol);
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

        // Each bar is the mean over the symbols that have that bar (1/cnt), so a symbol's weight is
        // 1/N only where every symbol has data. Re-cut each symbol's trades with that per-bar weight so
        // Σ returnPct reconciles exactly with the blended total, however the histories are staggered.
        double costRate = (cfg.commissionBps + cfg.slippageBps) / 1e4;
        List<TradeResult> allTrades = new ArrayList<>();
        for (int k = 0; k < series.size(); k++) {
            Series s = series.get(k);
            double[] w = new double[s.dates.length];
            for (int i = 0; i < w.length; i++) {
                Integer j = idx.get(s.dates[i]);
                w[i] = j == null || cnt[j] == 0 ? 0 : 1.0 / cnt[j];
            }
            allTrades.addAll(extractTrades(s.pos, s.close, s.dates, symbols.get(k), costRate, cfg.warmupBars, w));
        }

        // Each symbol's own standalone result, independent of the others — the blend just averages
        // these per-bar, so a lone symbol's numbers (same formulas as the portfolio's) are exact,
        // not an approximation. Its trades are the unweighted ones, so win rate / profit factor /
        // average win are per-trade figures for that symbol.
        Map<String, Double> symbolReturnsPct = new LinkedHashMap<>();
        Map<String, SymbolResult> symbolResults = new LinkedHashMap<>();
        for (int i = 0; symbolDetails && i < symbols.size(); i++) {
            Series s = series.get(i);
            Map<String, Double> sm = PerformanceMetrics.compute(s.net, s.pos, s.absPos, s.bench, s.trades, cfg, s.dates);
            symbolResults.put(symbols.get(i), new SymbolResult(sm, PerformanceMetrics.yearlyReturnsPct(s.net, s.dates)));
            symbolReturnsPct.put(symbols.get(i), sm.get("totalReturnPct"));
        }
        series.clear();   // per-bar arrays no longer needed — free them before metrics

        double[] eq = PerformanceMetrics.equityCurve(net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(net, pos, absPos, bench, allTrades, cfg, dates);
        return new BacktestOutput(strat.name(), symbols, dates, eq, benchEq, dd, allTrades, m, symbolReturnsPct,
                symbolResults, PerformanceMetrics.yearlyReturnsPct(net, dates));
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
        List<TradeResult> trades = extractPairTrades(posA, closeA, dates, a.symbol + "/" + b.symbol, costRate, cfg.warmupBars, 0.5);
        double[] eq = PerformanceMetrics.equityCurve(net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(net, posA, absPos, bench, trades, cfg, dates);
        if (tracePerSymbol)
            log.info("Backtest: pairs_trading × {}/{} — {} common bars, {} trades", a.symbol, b.symbol, n, trades.size());
        return new BacktestOutput("pairs_trading", List.of(a.symbol, b.symbol), dates, eq, benchEq, dd, trades, m,
                Map.of(), Map.of(), PerformanceMetrics.yearlyReturnsPct(net, dates));
    }

    /** Point-to-point trade return, guarded against a bad entry/exit price. */
    private static double tradeReturn(double entryPx, double exitPx) {
        return (entryPx > 0 && exitPx > 0) ? exitPx / entryPx - 1 : 0;
    }

}
