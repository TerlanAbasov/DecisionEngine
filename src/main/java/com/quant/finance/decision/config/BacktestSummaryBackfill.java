package com.quant.finance.decision.config;

import com.quant.finance.decision.domain.BacktestResultEntity;
import com.quant.finance.decision.domain.BacktestRunEntity;
import com.quant.finance.decision.engine.PerformanceMetrics;
import com.quant.finance.decision.repository.BacktestResultRepository;
import com.quant.finance.decision.repository.BacktestRunRepository;
import com.quant.finance.decision.repository.TradeRepository;
import com.quant.finance.decision.service.BacktestService;
import com.quant.finance.decision.service.JsonCodec;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boot-time, idempotent repair of legacy backtest runs.
 *
 * <ol>
 *   <li>Fills the denormalised metric columns on {@code backtest_run} for runs created
 *       before {@code 20260906-backtest-run-summary}, from the stored metrics JSON.</li>
 *   <li>Repairs the multiplicative-compounding blow-up: old runs geometrically compounded
 *       the per-bar return stream, which over thousands of intraday bars pushed
 *       {@code totalReturnPct} into the billions. The per-bar returns are recovered from
 *       the stored equity curve, clamped, and re-integrated on a fixed notional
 *       (Σ net) — matching the current engine. Equity / drawdown / headline metrics are
 *       rewritten in place; when no equity curve is stored, Σ realised trade P&amp;L is used.</li>
 * </ol>
 */
@Component
@Order(2)
public class BacktestSummaryBackfill implements CommandLineRunner {

    private static final double MAX_BAR_RETURN = 0.75;

    private final BacktestRunRepository runRepo;
    private final BacktestResultRepository resultRepo;
    private final TradeRepository tradeRepo;
    private final JsonCodec json;

    public BacktestSummaryBackfill(BacktestRunRepository runRepo, BacktestResultRepository resultRepo,
                                   TradeRepository tradeRepo, JsonCodec json) {
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.tradeRepo = tradeRepo;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(String... args) {
        for (BacktestRunEntity r : runRepo.findAll()) {
            boolean changed = false;
            BacktestResultEntity res = resultRepo.findByRunId(r.getId()).orElse(null);

            if (r.getTotalReturnPct() == null && res != null) {
                BacktestService.applySummary(r, json.readMetrics(res.getMetricsJson()));
                changed = true;
            }

            // legacy portfolio runs stored one trade row per symbol unweighted, so Σ trade.returnPct
            // is ~N× the (equal-weight) portfolio return — divide by the symbol count to reconcile
            int symbolCount = Math.max(1, r.getSymbolsCsv() == null || r.getSymbolsCsv().isBlank()
                    ? 1 : r.getSymbolsCsv().split(",").length);
            double tradeSumPct = tradeRepo.sumReturnPctByRunId(r.getId()) * 100.0 / symbolCount;
            Double stored = r.getTotalReturnPct();
            boolean artifact = stored == null || Math.abs(stored) > 20 * Math.abs(tradeSumPct) + 500;
            if (artifact) {
                if (res != null && repairFromEquity(r, res)) {
                    resultRepo.save(res);
                } else {
                    // no usable equity curve — fall back to realised trade P&L
                    r.setTotalReturnPct(round2(tradeSumPct));
                    r.setCagrPct(round2(annualisePct(tradeSumPct, yearsOf(r))));
                    r.setMaxDrawdownPct(null);
                    r.setCalmar(null);
                }
                changed = true;
            }

            if (changed) runRepo.save(r);
        }
    }

    /**
     * Recover the per-bar net returns from the stored (multiplicative) equity curve, clamp
     * them, and rebuild the run additively. Returns false if there is no curve to work from.
     */
    private boolean repairFromEquity(BacktestRunEntity r, BacktestResultEntity res) {
        double[] eqOld = json.readDoubles(res.getEquityJson());
        if (eqOld.length < 2) return false;
        double cap = r.getCapital() > 0 ? r.getCapital() : 100_000;
        double rf = r.getRiskFreePct() == null ? 0 : r.getRiskFreePct();

        double[] net = new double[eqOld.length];
        double prev = cap;
        for (int i = 0; i < eqOld.length; i++) {
            double e = eqOld[i];
            double x = prev > 0 && Double.isFinite(e) ? e / prev - 1 : 0;
            if (!Double.isFinite(x)) x = 0;
            net[i] = Math.max(-MAX_BAR_RETURN, Math.min(MAX_BAR_RETURN, x));
            prev = e;
        }

        Map<String, Double> rm = PerformanceMetrics.returnMetrics(net, cap, rf, parseDates(res.getDatesJson()));
        double[] eqNew = PerformanceMetrics.equityCurve(net, cap);
        double[] ddNew = PerformanceMetrics.drawdown(eqNew);

        res.setEquityJson(json.write(eqNew));
        res.setDrawdownJson(json.write(ddNew));
        Map<String, Double> merged = new LinkedHashMap<>(json.readMetrics(res.getMetricsJson()));
        merged.putAll(rm);                        // overlay repaired headline keys
        res.setMetricsJson(json.write(merged));

        r.setTotalReturnPct(rm.get("totalReturnPct"));
        r.setCagrPct(rm.get("cagrPct"));
        r.setMaxDrawdownPct(rm.get("maxDrawdownPct"));
        r.setCalmar(rm.get("calmar"));
        r.setAnnVolPct(rm.get("annVolPct"));
        r.setSharpe(rm.get("sharpe"));
        r.setSortino(rm.get("sortino"));
        return true;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Parse a stored JSON array of ISO-8601 instants into {@code Instant[]} (null on any problem). */
    private static java.time.Instant[] parseDates(String datesJson) {
        if (datesJson == null || datesJson.isBlank()) return null;
        try {
            String[] iso = MAPPER.readValue(datesJson, String[].class);
            java.time.Instant[] out = new java.time.Instant[iso.length];
            for (int i = 0; i < iso.length; i++) out[i] = java.time.Instant.parse(iso[i]);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static double yearsOf(BacktestRunEntity r) {
        if (r.getBars() != null && r.getBars() > 0) return r.getBars() / 252.0;
        if (r.getStartDate() != null && r.getEndDate() != null) {
            long days = Duration.between(r.getStartDate(), r.getEndDate()).toDays();
            return Math.max(1e-6, days / 365.25);
        }
        return 1.0;
    }

    private static double annualisePct(double totalPct, double years) {
        double total = totalPct / 100.0;
        if (years <= 0) return 0;
        double cagr = total <= -1 ? -1 : Math.pow(1 + total, 1 / years) - 1;
        return cagr * 100.0;
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 100.0) / 100.0;
    }
}
