package com.quant.finance.decision.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerformanceMetrics {
    private PerformanceMetrics() {}
    private static final int TRADING_DAYS = 252;
    private static final double SECONDS_PER_YEAR = 365.25 * 86_400;

    /** Calendar years spanned and average bars-per-year, from the actual bar timestamps. */
    private record TimeBasis(double years, double periodsPerYear) {}

    /**
     * Annualisation basis. Metrics used to assume every bar was one trading day (252/yr);
     * for hourly / minute bars that made CAGR, Sharpe, vol, alpha and turnover wrong by
     * orders of magnitude. Derive it from the timestamps instead — falling back to 252/yr
     * only when there aren't enough dated bars to measure.
     */
    private static TimeBasis timeBasis(Instant[] dates, int n) {
        if (dates != null && dates.length >= 2 && n >= 2) {
            long secs = Duration.between(dates[0], dates[dates.length - 1]).getSeconds();
            if (secs > 0) {
                double years = secs / SECONDS_PER_YEAR;
                return new TimeBasis(years, n / years);
            }
        }
        double y = n / (double) TRADING_DAYS;
        return new TimeBasis(y, TRADING_DAYS);
    }

    /**
     * Equity path on a FIXED notional: {@code capital * (1 + Σ net)}.
     *
     * <p>The engine's position is a fixed fraction of capital (never scaled up with equity),
     * so returns are additive on the starting capital — multiplicative compounding of the
     * per-bar stream would imply position sizing the backtest never did and, over thousands
     * of intraday bars, explodes the endpoint far past any realised trade P&L.
     */
    public static double[] equityCurve(double[] net, double capital) {
        double[] eq = new double[net.length];
        double cum = 0;
        for (int i = 0; i < net.length; i++) {
            double r = (Double.isNaN(net[i]) || Double.isInfinite(net[i])) ? 0 : net[i];
            cum += r;
            eq[i] = capital * (1 + cum);
        }
        return eq;
    }

    public static double[] drawdown(double[] equity) {
        double[] dd = new double[equity.length];
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            if (peak <= 0) { dd[i] = 0; continue; }
            double d = equity[i] / peak - 1;
            dd[i] = d < -1 ? -1 : d;
        }
        return dd;
    }

    /** Annualised rate from a total return, guarding total ≤ -100% (wipeout). */
    private static double annualise(double total, double years) {
        if (years <= 0) return 0;
        return total <= -1 ? -1 : Math.pow(1 + total, 1 / years) - 1;
    }

    private static double round(double v, int d) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        double f = Math.pow(10, d);
        return Math.round(v * f) / f;
    }

    /** Back-compat overload — assumes daily bars. Prefer the {@code Instant[] dates} form. */
    public static Map<String, Double> returnMetrics(double[] net, double capital, double riskFreePct) {
        return returnMetrics(net, capital, riskFreePct, null);
    }

    /**
     * Core metrics computable from a bare return stream (no positions or trades) — used by the
     * strategy ensemble. {@code dates} sets the annualisation basis (bars-per-year); null ⇒ daily.
     */
    public static Map<String, Double> returnMetrics(double[] net, double capital, double riskFreePct,
                                                    Instant[] dates) {
        int n = net.length;
        double[] eq = equityCurve(net, capital);
        double[] dd = drawdown(eq);
        TimeBasis tb = timeBasis(dates, n);
        double years = tb.years(), ppy = tb.periodsPerYear();
        double sqrtPpy = Math.sqrt(ppy);
        double total = n > 0 ? eq[n - 1] / capital - 1 : 0;
        double cagr = annualise(total, years);
        double rfBar = riskFreePct / 100.0 / ppy;

        double mean = 0;
        for (double r : net) mean += r;
        mean /= Math.max(1, n);
        double var = 0;
        for (double r : net) var += (r - mean) * (r - mean);
        var /= Math.max(1, n);
        double sd = Math.sqrt(var);
        double dsum = 0; int dcount = 0;
        for (double r : net) if (r < rfBar) { double x = r - rfBar; dsum += x * x; dcount++; }
        double downside = dcount > 0 ? Math.sqrt(dsum / dcount) : 0;
        double excess = mean - rfBar;

        double maxDd = 0, ddSq = 0;
        for (double d : dd) { maxDd = Math.min(maxDd, d); ddSq += d * d; }
        double cagrAbs = Math.abs(maxDd) > 0 ? cagr / Math.abs(maxDd) : 0;

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("totalReturnPct", round(total * 100, 2));
        m.put("cagrPct", round(cagr * 100, 2));
        m.put("annVolPct", round(sd * sqrtPpy * 100, 2));
        m.put("sharpe", round(sd > 0 ? excess / sd * sqrtPpy : 0, 2));
        m.put("sortino", round(downside > 0 ? excess / downside * sqrtPpy : 0, 2));
        m.put("calmar", round(cagrAbs, 2));
        m.put("maxDrawdownPct", round(maxDd * 100, 2));
        m.put("ulcerIndex", round(Math.sqrt(ddSq / Math.max(1, dd.length)) * 100, 2));
        return m;
    }

    /** Back-compat overload — assumes daily bars. Prefer the {@code Instant[] dates} form. */
    public static Map<String, Double> compute(double[] net, double[] pos, double[] absPos,
                                              double[] bench, List<TradeResult> trades, BacktestConfig cfg) {
        return compute(net, pos, absPos, bench, trades, cfg, null);
    }

    public static Map<String, Double> compute(double[] net, double[] pos, double[] absPos,
                                              double[] bench, List<TradeResult> trades, BacktestConfig cfg,
                                              Instant[] dates) {
        double capital = cfg.capital;
        int n = net.length;
        double[] eq = equityCurve(net, capital);
        double[] dd = drawdown(eq);
        TimeBasis tb = timeBasis(dates, n);
        double years = tb.years(), ppy = tb.periodsPerYear();
        double sqrtPpy = Math.sqrt(ppy);
        double total = n > 0 ? eq[n - 1] / capital - 1 : 0;
        double cagr = annualise(total, years);
        double rfBar = cfg.riskFreePct / 100.0 / ppy;

        double mean = 0;
        for (double r : net) mean += r;
        mean /= Math.max(1, n);
        double var = 0;
        for (double r : net) var += (r - mean) * (r - mean);
        var /= Math.max(1, n);
        double sd = Math.sqrt(var);
        double excess = mean - rfBar;
        double dsum = 0; int dcount = 0;
        for (double r : net) if (r < rfBar) { double x = r - rfBar; dsum += x * x; dcount++; }
        double downside = dcount > 0 ? Math.sqrt(dsum / dcount) : 0;

        double sharpe = sd > 0 ? excess / sd * sqrtPpy : 0;
        double sortino = downside > 0 ? excess / downside * sqrtPpy : 0;
        double vol = sd * sqrtPpy;
        double maxDd = 0;
        for (double d : dd) maxDd = Math.min(maxDd, d);
        double calmar = maxDd < 0 ? cagr / Math.abs(maxDd) : 0;

        double exposure = 0, longExp = 0, shortExp = 0;
        for (int i = 0; i < absPos.length; i++) {
            if (absPos[i] != 0) exposure++;
            if (pos[i] > 0) longExp++; else if (pos[i] < 0) shortExp++;
        }
        int pn = Math.max(1, absPos.length);
        exposure /= pn; longExp /= pn; shortExp /= pn;

        // --- risk of the daily return stream ---
        double ddSq = 0; int ddDays = 0, curUnderwater = 0, maxUnderwater = 0;
        for (double d : dd) {
            ddSq += d * d;
            if (d < 0) { ddDays++; curUnderwater++; maxUnderwater = Math.max(maxUnderwater, curUnderwater); }
            else curUnderwater = 0;
        }
        double ulcer = Math.sqrt(ddSq / Math.max(1, dd.length)) * 100;
        double gain = 0, pain = 0;
        for (double r : net) { if (r > 0) gain += r; else pain += -r; }
        double gainToPain = pain > 0 ? gain / pain : 0;

        double[] sorted = net.clone();
        Arrays.sort(sorted);
        int q = (int) Math.floor(0.05 * sorted.length);
        double var95 = sorted.length > 0 ? sorted[Math.min(q, sorted.length - 1)] : 0;
        double cvarSum = 0; int cvarN = 0;
        for (int i = 0; i <= Math.min(q, sorted.length - 1); i++) { cvarSum += sorted[i]; cvarN++; }
        double cvar95 = cvarN > 0 ? cvarSum / cvarN : 0;

        // --- beta / alpha / correlation vs benchmark daily returns ---
        double bMean = 0;
        for (double r : bench) bMean += r;
        bMean /= Math.max(1, bench.length);
        double cov = 0, bVar = 0, sVar = 0;
        for (int i = 0; i < n && i < bench.length; i++) {
            cov += (net[i] - mean) * (bench[i] - bMean);
            bVar += (bench[i] - bMean) * (bench[i] - bMean);
            sVar += (net[i] - mean) * (net[i] - mean);
        }
        double beta = bVar > 0 ? cov / bVar : 0;
        double alphaAnn = (mean - beta * bMean) * ppy * 100;
        double corr = (bVar > 0 && sVar > 0) ? cov / Math.sqrt(bVar * sVar) : 0;

        double turnover = 0;
        for (int i = 1; i < pos.length; i++) turnover += Math.abs(pos[i] - pos[i - 1]);
        double annTurnover = years > 0 ? turnover / years * 100 : 0;

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("totalReturnPct", round(total * 100, 2));
        m.put("cagrPct", round(cagr * 100, 2));
        m.put("annVolPct", round(vol * 100, 2));
        m.put("sharpe", round(sharpe, 2));
        m.put("sortino", round(sortino, 2));
        m.put("calmar", round(calmar, 2));
        m.put("maxDrawdownPct", round(maxDd * 100, 2));
        m.put("maxDrawdownDays", (double) maxUnderwater);
        m.put("timeInDrawdownPct", round(100.0 * ddDays / Math.max(1, dd.length), 1));
        m.put("exposurePct", round(exposure * 100, 1));
        m.put("longExposurePct", round(longExp * 100, 1));
        m.put("shortExposurePct", round(shortExp * 100, 1));
        m.put("ulcerIndex", round(ulcer, 2));
        m.put("gainToPain", round(gainToPain, 2));
        m.put("var95Pct", round(var95 * 100, 2));
        m.put("cvar95Pct", round(cvar95 * 100, 2));
        m.put("beta", round(beta, 2));
        m.put("alphaAnnPct", round(alphaAnn, 2));
        m.put("benchCorr", round(corr, 2));
        m.put("annTurnoverPct", round(annTurnover, 0));

        addTradeStats(m, trades, years);
        return m;
    }

    private static void addTradeStats(Map<String, Double> m, List<TradeResult> trades, double years) {
        String[] keys = {"trades", "longOps", "shortOps", "winRatePct", "longWinRatePct", "shortWinRatePct",
                "profitFactor", "avgWinPct", "avgLossPct", "expectancyPct", "avgBarsHeld",
                "longAvgBarsHeld", "shortAvgBarsHeld", "longPnlPct", "shortPnlPct",
                "bestTradePct", "worstTradePct", "maxWinStreak", "maxLossStreak", "tradesPerYear"};
        if (trades == null || trades.isEmpty()) {
            for (String k : keys) m.put(k, 0.0);
            return;
        }
        int tn = trades.size();
        int wins = 0, longN = 0, shortN = 0, longWins = 0, shortWins = 0, lossCount = 0;
        double gWin = 0, gLoss = 0, sumRet = 0, sumBars = 0, sumWin = 0, sumLoss = 0;
        double longBars = 0, shortBars = 0;
        double longPnl = 0, shortPnl = 0, best = Double.NEGATIVE_INFINITY, worst = Double.POSITIVE_INFINITY;
        int winStreak = 0, lossStreak = 0, maxWinStreak = 0, maxLossStreak = 0;
        for (TradeResult t : trades) {
            sumRet += t.returnPct; sumBars += t.bars;
            best = Math.max(best, t.returnPct); worst = Math.min(worst, t.returnPct);
            boolean isLong = "LONG".equals(t.side);
            if (isLong) { longN++; longPnl += t.returnPct; longBars += t.bars; }
            else { shortN++; shortPnl += t.returnPct; shortBars += t.bars; }
            if (t.returnPct > 0) {
                wins++; gWin += t.returnPct; sumWin += t.returnPct;
                if (isLong) longWins++; else shortWins++;
                winStreak++; lossStreak = 0; maxWinStreak = Math.max(maxWinStreak, winStreak);
            } else {
                gLoss += Math.abs(t.returnPct); sumLoss += t.returnPct; lossCount++;
                lossStreak++; winStreak = 0; maxLossStreak = Math.max(maxLossStreak, lossStreak);
            }
        }
        m.put("trades", (double) tn);
        m.put("longOps", (double) longN);
        m.put("shortOps", (double) shortN);
        m.put("winRatePct", round(100.0 * wins / tn, 1));
        m.put("longWinRatePct", longN > 0 ? round(100.0 * longWins / longN, 1) : 0);
        m.put("shortWinRatePct", shortN > 0 ? round(100.0 * shortWins / shortN, 1) : 0);
        m.put("profitFactor", gLoss > 0 ? round(gWin / gLoss, 2) : 0);
        m.put("avgWinPct", wins > 0 ? round(sumWin / wins * 100, 2) : 0);
        m.put("avgLossPct", lossCount > 0 ? round(sumLoss / lossCount * 100, 2) : 0);
        m.put("expectancyPct", round(sumRet / tn * 100, 2));
        m.put("avgBarsHeld", round(sumBars / tn, 1));
        m.put("longAvgBarsHeld", longN > 0 ? round(longBars / longN, 1) : 0);
        m.put("shortAvgBarsHeld", shortN > 0 ? round(shortBars / shortN, 1) : 0);
        m.put("longPnlPct", round(longPnl * 100, 2));
        m.put("shortPnlPct", round(shortPnl * 100, 2));
        m.put("bestTradePct", round(best * 100, 2));
        m.put("worstTradePct", round(worst * 100, 2));
        m.put("maxWinStreak", (double) maxWinStreak);
        m.put("maxLossStreak", (double) maxLossStreak);
        m.put("tradesPerYear", years > 0 ? round(tn / years, 1) : 0);
    }
}
