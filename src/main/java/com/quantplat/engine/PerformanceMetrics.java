package com.quantplat.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerformanceMetrics {
    private PerformanceMetrics() {}
    private static final int TRADING_DAYS = 252;

    public static double[] equityCurve(double[] net, double capital) {
        double[] eq = new double[net.length];
        double v = capital;
        for (int i = 0; i < net.length; i++) { v *= (1 + (Double.isNaN(net[i]) ? 0 : net[i])); eq[i] = v; }
        return eq;
    }

    public static double[] drawdown(double[] equity) {
        double[] dd = new double[equity.length];
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            dd[i] = peak > 0 ? equity[i] / peak - 1 : 0;
        }
        return dd;
    }

    private static double round(double v, int d) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        double f = Math.pow(10, d);
        return Math.round(v * f) / f;
    }

    public static Map<String, Double> compute(double[] net, double[] absPos,
                                              List<TradeResult> trades, double capital) {
        int n = net.length;
        double[] eq = equityCurve(net, capital);
        double[] dd = drawdown(eq);
        double years = n / (double) TRADING_DAYS;
        double total = n > 0 ? eq[n - 1] / capital - 1 : 0;
        double cagr = years > 0 ? Math.pow(eq[n - 1] / capital, 1 / years) - 1 : 0;

        double mean = 0;
        for (double r : net) mean += r;
        mean /= Math.max(1, n);
        double var = 0;
        for (double r : net) var += (r - mean) * (r - mean);
        var /= Math.max(1, n);
        double sd = Math.sqrt(var);
        double dsum = 0; int dcount = 0;
        for (double r : net) if (r < 0) { dsum += r * r; dcount++; }
        double downside = dcount > 0 ? Math.sqrt(dsum / dcount) : 0;

        double sharpe = sd > 0 ? mean / sd * Math.sqrt(TRADING_DAYS) : 0;
        double sortino = downside > 0 ? mean / downside * Math.sqrt(TRADING_DAYS) : 0;
        double vol = sd * Math.sqrt(TRADING_DAYS);
        double maxDd = 0;
        for (double d : dd) maxDd = Math.min(maxDd, d);
        double calmar = maxDd < 0 ? cagr / Math.abs(maxDd) : 0;
        double exposure = 0;
        for (double a : absPos) exposure += (a != 0 ? 1 : 0);
        exposure /= Math.max(1, absPos.length);

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("totalReturnPct", round(total * 100, 2));
        m.put("cagrPct", round(cagr * 100, 2));
        m.put("annVolPct", round(vol * 100, 2));
        m.put("sharpe", round(sharpe, 2));
        m.put("sortino", round(sortino, 2));
        m.put("calmar", round(calmar, 2));
        m.put("maxDrawdownPct", round(maxDd * 100, 2));
        m.put("exposurePct", round(exposure * 100, 1));

        if (trades != null && !trades.isEmpty()) {
            int wins = 0; double gWin = 0, gLoss = 0, sumRet = 0, sumBars = 0, sumWin = 0, sumLoss = 0;
            int lossCount = 0;
            for (TradeResult t : trades) {
                sumRet += t.returnPct; sumBars += t.bars;
                if (t.returnPct > 0) { wins++; gWin += t.returnPct; sumWin += t.returnPct; }
                else { gLoss += Math.abs(t.returnPct); sumLoss += t.returnPct; lossCount++; }
            }
            int tn = trades.size();
            m.put("trades", (double) tn);
            m.put("winRatePct", round(100.0 * wins / tn, 1));
            m.put("profitFactor", gLoss > 0 ? round(gWin / gLoss, 2) : 0);
            m.put("avgWinPct", wins > 0 ? round(sumWin / wins * 100, 2) : 0);
            m.put("avgLossPct", lossCount > 0 ? round(sumLoss / lossCount * 100, 2) : 0);
            m.put("expectancyPct", round(sumRet / tn * 100, 2));
            m.put("avgBarsHeld", round(sumBars / tn, 1));
        } else {
            for (String k : new String[]{"trades", "winRatePct", "profitFactor", "avgWinPct",
                    "avgLossPct", "expectancyPct", "avgBarsHeld"}) m.put(k, 0.0);
        }
        return m;
    }
}
