package com.quantplat.engine;

import com.quantplat.strategy.BarSeries;
import com.quantplat.strategy.TradingStrategy;
import com.quantplat.strategy.impl.PairsStrategy;

import java.time.Instant;
import java.util.*;

/** Vectorised, next-bar backtesting engine. */
public final class Backtester {

    /** Per-symbol computed series aligned to the symbol's own dates. */
    private record Series(Instant[] dates, double[] net, double[] bench,
                          double[] absPos, List<TradeResult> trades) {}

    private Series computeSeries(BarSeries b, TradingStrategy strat,
                                 Map<String, Double> params, BacktestConfig cfg) {
        int n = b.size();
        double[] target = strat.generateSignals(b, params);
        if (!cfg.allowShort) for (int i = 0; i < n; i++) if (target[i] < 0) target[i] = 0;

        double[] pos = new double[n];        // executed position (shifted by execLag)
        for (int i = 0; i < n; i++) {
            int src = i - cfg.execLag;
            pos[i] = src >= 0 ? target[src] : 0;
        }
        double[] net = new double[n], bench = new double[n], absPos = new double[n];
        double costRate = (cfg.commissionBps + cfg.slippageBps) / 1e4;
        for (int i = 0; i < n; i++) {
            double ret = i > 0 ? b.close[i] / b.close[i - 1] - 1 : 0;
            double prev = i > 0 ? pos[i - 1] : 0;
            double turnover = Math.abs(pos[i] - prev);
            net[i] = pos[i] * ret - turnover * costRate;
            bench[i] = ret;
            absPos[i] = Math.abs(pos[i]);
        }
        List<TradeResult> trades = extractTrades(pos, b.close, b.date, b.symbol, costRate);
        return new Series(b.date, net, bench, absPos, trades);
    }

    private List<TradeResult> extractTrades(double[] pos, double[] close, Instant[] date,
                                            String symbol, double costRate) {
        List<TradeResult> out = new ArrayList<>();
        int cur = 0, ei = -1;
        double epx = 0;
        for (int i = 0; i < pos.length; i++) {
            int d = (int) Math.signum(pos[i]);
            if (d != cur) {
                if (cur != 0) {
                    double ret = cur * (close[i] / epx - 1) - 2 * costRate;
                    out.add(new TradeResult(symbol, cur > 0 ? "LONG" : "SHORT",
                            date[ei], date[i], round2(epx), round2(close[i]), i - ei, ret));
                }
                if (d != 0) { cur = d; ei = i; epx = close[i]; } else { cur = 0; ei = -1; }
            }
        }
        if (cur != 0) {
            int last = pos.length - 1;
            double ret = cur * (close[last] / epx - 1) - 2 * costRate;
            out.add(new TradeResult(symbol, cur > 0 ? "LONG" : "SHORT",
                    date[ei], date[last], round2(epx), round2(close[last]), last - ei, ret));
        }
        return out;
    }

    public BacktestOutput runSingle(BarSeries b, TradingStrategy strat,
                                    Map<String, Double> params, BacktestConfig cfg) {
        Series s = computeSeries(b, strat, params, cfg);
        double[] eq = PerformanceMetrics.equityCurve(s.net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(s.bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(s.net, s.absPos, s.trades, cfg.capital);
        return new BacktestOutput(strat.name(), List.of(b.symbol), s.dates, eq, benchEq, dd, s.trades, m);
    }

    public BacktestOutput runPortfolio(List<BarSeries> data, TradingStrategy strat,
                                       Map<String, Double> params, BacktestConfig cfg) {
        List<Series> series = new ArrayList<>();
        List<String> symbols = new ArrayList<>();
        List<TradeResult> allTrades = new ArrayList<>();
        TreeSet<Instant> allDates = new TreeSet<>();
        for (BarSeries b : data) {
            Series s = computeSeries(b, strat, params, cfg);
            series.add(s);
            symbols.add(b.symbol);
            allTrades.addAll(s.trades);
            allDates.addAll(Arrays.asList(s.dates));
        }
        Instant[] dates = allDates.toArray(new Instant[0]);
        List<Map<Instant, Double>> netMaps = new ArrayList<>(), benchMaps = new ArrayList<>(), posMaps = new ArrayList<>();
        for (Series s : series) {
            Map<Instant, Double> nm = new HashMap<>(), bm = new HashMap<>(), pm = new HashMap<>();
            for (int i = 0; i < s.dates.length; i++) { nm.put(s.dates[i], s.net[i]); bm.put(s.dates[i], s.bench[i]); pm.put(s.dates[i], s.absPos[i]); }
            netMaps.add(nm); benchMaps.add(bm); posMaps.add(pm);
        }
        double[] net = new double[dates.length], bench = new double[dates.length], absPos = new double[dates.length];
        for (int i = 0; i < dates.length; i++) {
            Instant d = dates[i];
            double sn = 0, sb = 0, sp = 0; int c = 0;
            for (int k = 0; k < series.size(); k++) {
                Double v = netMaps.get(k).get(d);
                if (v != null) { sn += v; sb += benchMaps.get(k).get(d); sp += posMaps.get(k).get(d); c++; }
            }
            if (c > 0) { net[i] = sn / c; bench[i] = sb / c; absPos[i] = sp / c; }
        }
        double[] eq = PerformanceMetrics.equityCurve(net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(net, absPos, allTrades, cfg.capital);
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
            posA[i] = src >= 0 ? sig[0][src] : 0;
            posB[i] = src >= 0 ? sig[1][src] : 0;
        }
        double[] net = new double[n], bench = new double[n], absPos = new double[n];
        double costRate = (cfg.commissionBps + cfg.slippageBps) / 1e4;
        for (int i = 0; i < n; i++) {
            double ra = i > 0 ? pa[i] / pa[i - 1] - 1 : 0;
            double rb = i > 0 ? pb[i] / pb[i - 1] - 1 : 0;
            double prevA = i > 0 ? posA[i - 1] : 0, prevB = i > 0 ? posB[i - 1] : 0;
            double turnover = Math.abs(posA[i] - prevA) + Math.abs(posB[i] - prevB);
            net[i] = 0.5 * posA[i] * ra + 0.5 * posB[i] * rb - turnover * costRate;
            bench[i] = 0.5 * ra + 0.5 * rb;
            absPos[i] = Math.abs(posA[i]);
        }
        Instant[] dates = common.toArray(new Instant[0]);
        double[] closeA = new double[n];
        for (int i = 0; i < n; i++) closeA[i] = pa[i];
        List<TradeResult> trades = extractTrades(posA, closeA, dates, a.symbol + "/" + b.symbol, costRate);
        double[] eq = PerformanceMetrics.equityCurve(net, cfg.capital);
        double[] benchEq = PerformanceMetrics.equityCurve(bench, cfg.capital);
        double[] dd = PerformanceMetrics.drawdown(eq);
        Map<String, Double> m = PerformanceMetrics.compute(net, absPos, trades, cfg.capital);
        return new BacktestOutput("pairs_trading", List.of(a.symbol, b.symbol), dates, eq, benchEq, dd, trades, m);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
