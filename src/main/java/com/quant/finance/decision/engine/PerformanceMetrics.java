package com.quant.finance.decision.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PerformanceMetrics {
    private PerformanceMetrics() {}

    private static final int TRADING_DAYS = 252;
    private static final double SECONDS_PER_YEAR = 365.25 * 86_400;

    /** Calendar years spanned and average bars-per-year, from the actual bar timestamps. */
    private record TimeBasis(double years, double periodsPerYear) {}

    /**
     * Annualisation basis derived from the bar timestamps (assuming one bar per trading day, 252/yr, was wrong by orders of magnitude for hourly / minute bars),
     * falling back to 252/yr only when there are too few dated bars to measure.
     */
    private static TimeBasis timeBasis(Instant[] dates, int barCount) {
        if (dates != null && dates.length >= 2 && barCount >= 2) {
            long seconds = Duration.between(dates[0], dates[dates.length - 1]).getSeconds();
            if (seconds > 0) {
                double years = seconds / SECONDS_PER_YEAR;
                return new TimeBasis(years, barCount / years);
            }
        }
        double years = barCount / (double) TRADING_DAYS;
        return new TimeBasis(years, TRADING_DAYS);
    }

    /** Per-bar mean, standard deviation and downside deviation (below the per-bar risk-free rate) of a return stream. */
    private record ReturnStats(double mean, double stdDev, double downsideDeviation) {

        static ReturnStats of(double[] returns, double riskFreePerBar) {
            int count = returns.length;
            double mean = 0;
            for (double value : returns) {
                mean += value;
            }
            mean /= Math.max(1, count);
            double variance = 0;
            for (double value : returns) {
                variance += (value - mean) * (value - mean);
            }
            variance /= Math.max(1, count);
            double downsideSquares = 0;
            int downsideCount = 0;
            for (double value : returns) {
                if (value < riskFreePerBar) {
                    double shortfall = value - riskFreePerBar;
                    downsideSquares += shortfall * shortfall;
                    downsideCount++;
                }
            }
            double downsideDeviation = downsideCount > 0 ? Math.sqrt(downsideSquares / downsideCount) : 0;
            return new ReturnStats(mean, Math.sqrt(variance), downsideDeviation);
        }
    }

    /**
     * Equity path on a FIXED notional, {@code capital * (1 + Σ net)}: positions are a fixed fraction of capital, so returns are additive — compounding the per-bar
     * stream would imply sizing the backtest never did and, over thousands of intraday bars, explode the endpoint past any realised trade P&amp;L.
     */
    public static double[] equityCurve(double[] net, double capital) {
        double[] equity = new double[net.length];
        double cumulativeReturn = 0;
        for (int i = 0; i < net.length; i++) {
            double barReturn = (Double.isNaN(net[i]) || Double.isInfinite(net[i])) ? 0 : net[i];
            cumulativeReturn += barReturn;
            equity[i] = capital * (1 + cumulativeReturn);
        }
        return equity;
    }

    /**
     * Return in each calendar year (UTC), in percent, oldest first: the sum of that year's per-bar net returns (additive, see {@link #equityCurve}),
     * so the years add up to the total return; the first and last year are usually partial.
     */
    public static Map<String, Double> yearlyReturnsPct(double[] net, Instant[] dates) {
        Map<Integer, Double> byYear = new TreeMap<>();
        int count = Math.min(net.length, dates.length);
        for (int i = 0; i < count; i++) {
            double barReturn = (Double.isNaN(net[i]) || Double.isInfinite(net[i])) ? 0 : net[i];
            byYear.merge(dates[i].atZone(ZoneOffset.UTC).getYear(), barReturn, Double::sum);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        byYear.forEach((year, yearReturn) -> out.put(String.valueOf(year), round(yearReturn * 100, 2)));
        return out;
    }

    public static double[] drawdown(double[] equity) {
        double[] drawdown = new double[equity.length];
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            if (peak <= 0) {
                drawdown[i] = 0;
                continue;
            }
            double fromPeak = equity[i] / peak - 1;
            drawdown[i] = fromPeak < -1 ? -1 : fromPeak;
        }
        return drawdown;
    }

    /** Annualised rate from a total return, guarding total ≤ -100% (wipeout). */
    private static double annualise(double totalReturn, double years) {
        if (years <= 0) {
            return 0;
        }
        return totalReturn <= -1 ? -1 : Math.pow(1 + totalReturn, 1 / years) - 1;
    }

    private static double round(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /** Back-compat overload — assumes daily bars. Prefer the {@code Instant[] dates} form. */
    public static Map<String, Double> returnMetrics(double[] net, double capital, double riskFreePct) {
        return returnMetrics(net, capital, riskFreePct, null);
    }

    /**
     * Core metrics computable from a bare return stream (no positions or trades) — used by the
     * strategy ensemble. {@code dates} sets the annualisation basis (bars-per-year); null ⇒ daily.
     */
    public static Map<String, Double> returnMetrics(double[] net, double capital, double riskFreePct, Instant[] dates) {
        int barCount = net.length;
        double[] equity = equityCurve(net, capital);
        double[] drawdown = drawdown(equity);
        TimeBasis timeBasis = timeBasis(dates, barCount);
        double years = timeBasis.years();
        double periodsPerYear = timeBasis.periodsPerYear();
        double sqrtPeriodsPerYear = Math.sqrt(periodsPerYear);
        double totalReturn = barCount > 0 ? equity[barCount - 1] / capital - 1 : 0;
        double cagr = annualise(totalReturn, years);
        double riskFreePerBar = riskFreePct / 100.0 / periodsPerYear;

        ReturnStats stats = ReturnStats.of(net, riskFreePerBar);
        double excessReturn = stats.mean() - riskFreePerBar;

        double maxDrawdown = 0;
        double drawdownSquares = 0;
        for (double barDrawdown : drawdown) {
            maxDrawdown = Math.min(maxDrawdown, barDrawdown);
            drawdownSquares += barDrawdown * barDrawdown;
        }
        double calmar = Math.abs(maxDrawdown) > 0 ? cagr / Math.abs(maxDrawdown) : 0;

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("totalReturnPct", round(totalReturn * 100, 2));
        metrics.put("cagrPct", round(cagr * 100, 2));
        metrics.put("annVolPct", round(stats.stdDev() * sqrtPeriodsPerYear * 100, 2));
        metrics.put("sharpe", round(stats.stdDev() > 0 ? excessReturn / stats.stdDev() * sqrtPeriodsPerYear : 0, 2));
        metrics.put(
                "sortino",
                round(
                        stats.downsideDeviation() > 0
                                ? excessReturn / stats.downsideDeviation() * sqrtPeriodsPerYear
                                : 0,
                        2));
        metrics.put("calmar", round(calmar, 2));
        metrics.put("maxDrawdownPct", round(maxDrawdown * 100, 2));
        metrics.put("ulcerIndex", round(Math.sqrt(drawdownSquares / Math.max(1, drawdown.length)) * 100, 2));
        return metrics;
    }

    /** Back-compat overload — assumes daily bars. Prefer the {@code Instant[] dates} form. */
    public static Map<String, Double> compute(
            double[] net,
            double[] position,
            double[] absPosition,
            double[] bench,
            List<TradeResult> trades,
            BacktestConfig config) {
        return compute(net, position, absPosition, bench, trades, config, null);
    }

    public static Map<String, Double> compute(
            double[] net,
            double[] position,
            double[] absPosition,
            double[] bench,
            List<TradeResult> trades,
            BacktestConfig config,
            Instant[] dates) {
        double capital = config.capital;
        int barCount = net.length;
        double[] equity = equityCurve(net, capital);
        double[] drawdown = drawdown(equity);
        TimeBasis timeBasis = timeBasis(dates, barCount);
        double years = timeBasis.years();
        double periodsPerYear = timeBasis.periodsPerYear();
        double sqrtPeriodsPerYear = Math.sqrt(periodsPerYear);
        double totalReturn = barCount > 0 ? equity[barCount - 1] / capital - 1 : 0;
        double cagr = annualise(totalReturn, years);
        double riskFreePerBar = config.riskFreePct / 100.0 / periodsPerYear;

        ReturnStats stats = ReturnStats.of(net, riskFreePerBar);
        double mean = stats.mean();
        double excessReturn = mean - riskFreePerBar;
        double sharpe = stats.stdDev() > 0 ? excessReturn / stats.stdDev() * sqrtPeriodsPerYear : 0;
        double sortino =
                stats.downsideDeviation() > 0 ? excessReturn / stats.downsideDeviation() * sqrtPeriodsPerYear : 0;
        double annualVolatility = stats.stdDev() * sqrtPeriodsPerYear;
        double maxDrawdown = 0;
        for (double barDrawdown : drawdown) {
            maxDrawdown = Math.min(maxDrawdown, barDrawdown);
        }
        double calmar = maxDrawdown < 0 ? cagr / Math.abs(maxDrawdown) : 0;

        double exposure = 0;
        double longExposure = 0;
        double shortExposure = 0;
        for (int i = 0; i < absPosition.length; i++) {
            if (absPosition[i] != 0) {
                exposure++;
            }
            if (position[i] > 0) {
                longExposure++;
            } else if (position[i] < 0) {
                shortExposure++;
            }
        }
        int positionBars = Math.max(1, absPosition.length);
        exposure /= positionBars;
        longExposure /= positionBars;
        shortExposure /= positionBars;

        // --- risk of the daily return stream ---
        double drawdownSquares = 0;
        int barsInDrawdown = 0;
        int currentUnderwater = 0;
        int maxUnderwater = 0;
        for (double barDrawdown : drawdown) {
            drawdownSquares += barDrawdown * barDrawdown;
            if (barDrawdown < 0) {
                barsInDrawdown++;
                currentUnderwater++;
                maxUnderwater = Math.max(maxUnderwater, currentUnderwater);
            } else {
                currentUnderwater = 0;
            }
        }
        double ulcerIndex = Math.sqrt(drawdownSquares / Math.max(1, drawdown.length)) * 100;
        double gain = 0;
        double pain = 0;
        for (double barReturn : net) {
            if (barReturn > 0) {
                gain += barReturn;
            } else {
                pain += -barReturn;
            }
        }
        double gainToPain = pain > 0 ? gain / pain : 0;

        double[] sortedReturns = net.clone();
        Arrays.sort(sortedReturns);
        int tailIndex = (int) Math.floor(0.05 * sortedReturns.length);
        double valueAtRisk95 =
                sortedReturns.length > 0 ? sortedReturns[Math.min(tailIndex, sortedReturns.length - 1)] : 0;
        double tailSum = 0;
        int tailCount = 0;
        for (int i = 0; i <= Math.min(tailIndex, sortedReturns.length - 1); i++) {
            tailSum += sortedReturns[i];
            tailCount++;
        }
        double conditionalValueAtRisk95 = tailCount > 0 ? tailSum / tailCount : 0;

        // --- beta / alpha / correlation vs benchmark daily returns ---
        double benchMean = 0;
        for (double benchReturn : bench) {
            benchMean += benchReturn;
        }
        benchMean /= Math.max(1, bench.length);
        double covariance = 0;
        double benchVariance = 0;
        double strategyVariance = 0;
        for (int i = 0; i < barCount && i < bench.length; i++) {
            covariance += (net[i] - mean) * (bench[i] - benchMean);
            benchVariance += (bench[i] - benchMean) * (bench[i] - benchMean);
            strategyVariance += (net[i] - mean) * (net[i] - mean);
        }
        double beta = benchVariance > 0 ? covariance / benchVariance : 0;
        double annualAlpha = (mean - beta * benchMean) * periodsPerYear * 100;
        double correlation = (benchVariance > 0 && strategyVariance > 0)
                ? covariance / Math.sqrt(benchVariance * strategyVariance)
                : 0;

        double turnover = 0;
        for (int i = 1; i < position.length; i++) {
            turnover += Math.abs(position[i] - position[i - 1]);
        }
        double annualTurnover = years > 0 ? turnover / years * 100 : 0;

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("totalReturnPct", round(totalReturn * 100, 2));
        metrics.put("cagrPct", round(cagr * 100, 2));
        metrics.put("annVolPct", round(annualVolatility * 100, 2));
        metrics.put("sharpe", round(sharpe, 2));
        metrics.put("sortino", round(sortino, 2));
        metrics.put("calmar", round(calmar, 2));
        metrics.put("maxDrawdownPct", round(maxDrawdown * 100, 2));
        metrics.put("maxDrawdownDays", (double) maxUnderwater);
        metrics.put("timeInDrawdownPct", round(100.0 * barsInDrawdown / Math.max(1, drawdown.length), 1));
        metrics.put("exposurePct", round(exposure * 100, 1));
        metrics.put("longExposurePct", round(longExposure * 100, 1));
        metrics.put("shortExposurePct", round(shortExposure * 100, 1));
        metrics.put("ulcerIndex", round(ulcerIndex, 2));
        metrics.put("gainToPain", round(gainToPain, 2));
        metrics.put("var95Pct", round(valueAtRisk95 * 100, 2));
        metrics.put("cvar95Pct", round(conditionalValueAtRisk95 * 100, 2));
        metrics.put("beta", round(beta, 2));
        metrics.put("alphaAnnPct", round(annualAlpha, 2));
        metrics.put("benchCorr", round(correlation, 2));
        metrics.put("annTurnoverPct", round(annualTurnover, 0));

        addTradeStats(metrics, trades, years);
        return metrics;
    }

    private static final String[] TRADE_STAT_KEYS = {
        "trades",
        "longOps",
        "shortOps",
        "winRatePct",
        "longWinRatePct",
        "shortWinRatePct",
        "profitFactor",
        "avgWinPct",
        "avgLossPct",
        "expectancyPct",
        "avgBarsHeld",
        "longAvgBarsHeld",
        "shortAvgBarsHeld",
        "longPnlPct",
        "shortPnlPct",
        "bestTradePct",
        "worstTradePct",
        "maxWinStreak",
        "maxLossStreak",
        "tradesPerYear"
    };

    private static void addTradeStats(Map<String, Double> metrics, List<TradeResult> trades, double years) {
        if (trades == null || trades.isEmpty()) {
            for (String key : TRADE_STAT_KEYS) {
                metrics.put(key, 0.0);
            }
            return;
        }
        int tradeCount = trades.size();
        int wins = 0;
        int losses = 0;
        int longTrades = 0;
        int shortTrades = 0;
        int longWins = 0;
        int shortWins = 0;
        double grossWins = 0;
        double grossLosses = 0;
        double sumReturns = 0;
        double sumLossReturns = 0;
        double sumBarsHeld = 0;
        double longBarsHeld = 0;
        double shortBarsHeld = 0;
        double longPnl = 0;
        double shortPnl = 0;
        double bestTrade = Double.NEGATIVE_INFINITY;
        double worstTrade = Double.POSITIVE_INFINITY;
        int winStreak = 0;
        int lossStreak = 0;
        int maxWinStreak = 0;
        int maxLossStreak = 0;
        for (TradeResult trade : trades) {
            sumReturns += trade.returnPct;
            sumBarsHeld += trade.bars;
            bestTrade = Math.max(bestTrade, trade.returnPct);
            worstTrade = Math.min(worstTrade, trade.returnPct);
            boolean isLong = "LONG".equals(trade.side);
            if (isLong) {
                longTrades++;
                longPnl += trade.returnPct;
                longBarsHeld += trade.bars;
            } else {
                shortTrades++;
                shortPnl += trade.returnPct;
                shortBarsHeld += trade.bars;
            }
            if (trade.returnPct > 0) {
                wins++;
                grossWins += trade.returnPct;
                if (isLong) {
                    longWins++;
                } else {
                    shortWins++;
                }
                winStreak++;
                lossStreak = 0;
                maxWinStreak = Math.max(maxWinStreak, winStreak);
            } else {
                losses++;
                grossLosses += Math.abs(trade.returnPct);
                sumLossReturns += trade.returnPct;
                lossStreak++;
                winStreak = 0;
                maxLossStreak = Math.max(maxLossStreak, lossStreak);
            }
        }
        metrics.put("trades", (double) tradeCount);
        metrics.put("longOps", (double) longTrades);
        metrics.put("shortOps", (double) shortTrades);
        metrics.put("winRatePct", round(100.0 * wins / tradeCount, 1));
        metrics.put("longWinRatePct", longTrades > 0 ? round(100.0 * longWins / longTrades, 1) : 0);
        metrics.put("shortWinRatePct", shortTrades > 0 ? round(100.0 * shortWins / shortTrades, 1) : 0);
        metrics.put("profitFactor", grossLosses > 0 ? round(grossWins / grossLosses, 2) : 0);
        metrics.put("avgWinPct", wins > 0 ? round(grossWins / wins * 100, 2) : 0);
        metrics.put("avgLossPct", losses > 0 ? round(sumLossReturns / losses * 100, 2) : 0);
        metrics.put("expectancyPct", round(sumReturns / tradeCount * 100, 2));
        metrics.put("avgBarsHeld", round(sumBarsHeld / tradeCount, 1));
        metrics.put("longAvgBarsHeld", longTrades > 0 ? round(longBarsHeld / longTrades, 1) : 0);
        metrics.put("shortAvgBarsHeld", shortTrades > 0 ? round(shortBarsHeld / shortTrades, 1) : 0);
        metrics.put("longPnlPct", round(longPnl * 100, 2));
        metrics.put("shortPnlPct", round(shortPnl * 100, 2));
        metrics.put("bestTradePct", round(bestTrade * 100, 2));
        metrics.put("worstTradePct", round(worstTrade * 100, 2));
        metrics.put("maxWinStreak", (double) maxWinStreak);
        metrics.put("maxLossStreak", (double) maxLossStreak);
        metrics.put("tradesPerYear", years > 0 ? round(tradeCount / years, 1) : 0);
    }
}
