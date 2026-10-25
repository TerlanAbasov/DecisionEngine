package com.quant.finance.decision.service;

import com.quant.finance.decision.dto.Dtos.StrategyControlsUpdate;
import com.quant.finance.decision.dto.Dtos.StrategyDto;
import com.quant.finance.decision.engine.Timeframe;
import com.quant.finance.decision.entity.StrategyConfigEntity;
import com.quant.finance.decision.repository.StrategyConfigRepository;
import com.quant.finance.decision.strategy.ConfiguredStrategy;
import com.quant.finance.decision.strategy.TradingStrategy;
import com.quant.finance.decision.strategy.impl.StrategyCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class StrategyService {

    private static final Set<String> DIRECTIONS = Set.of("long_only", "short_only", "long_short");

    /** Default bar interval per category — the frame each family of strategies is usually run on for day trading. */
    private static final Map<String, String> DEFAULT_TF = Map.of(
            "trend",
            "H1",
            "momentum",
            "H1",
            "hybrid",
            "H1",
            "mean_reversion",
            "M15",
            "breakout",
            "M15",
            "volume",
            "M15",
            "pattern",
            "M15",
            "seasonal",
            "D1");

    /** Categories that are not day-trading strategies (calendar / multi-day effects). */
    private static final Set<String> NON_INTRADAY_CATEGORIES = Set.of("seasonal");

    static String defaultTimeframe(String category) {
        return DEFAULT_TF.getOrDefault(category, "H1");
    }

    static boolean defaultIntraday(String category) {
        return !NON_INTRADAY_CATEGORIES.contains(category);
    }

    private final StrategyConfigRepository repo;
    private final JsonCodec json;
    private final Map<String, TradingStrategy> catalog = new LinkedHashMap<>();

    public StrategyService(StrategyConfigRepository repo, JsonCodec json) {
        this.repo = repo;
        this.json = json;
        for (TradingStrategy strategy : StrategyCatalog.all()) {
            catalog.put(strategy.name(), strategy);
        }
    }

    /** Seed strategy_config from the catalog on first startup, and backfill day-trading metadata. */
    @Transactional
    public void seed() {
        int inserted = 0;
        int backfilled = 0;
        for (TradingStrategy strategy : catalog.values()) {
            Optional<StrategyConfigEntity> existing = repo.findByName(strategy.name());
            if (existing.isEmpty()) {
                StrategyConfigEntity config = new StrategyConfigEntity();
                config.setName(strategy.name());
                config.setCategory(strategy.category());
                config.setDirection(strategy.direction());
                config.setDescription(strategy.description());
                config.setEnabled(true);
                config.setRecommendedTimeframe(defaultTimeframe(strategy.category()));
                config.setIntraday(defaultIntraday(strategy.category()));
                repo.save(config);
                inserted++;
            } else {
                // rows created before this column existed -> fill the per-category default once
                StrategyConfigEntity config = existing.get();
                boolean dirty = false;
                if (config.getRecommendedTimeframe() == null) {
                    config.setRecommendedTimeframe(defaultTimeframe(strategy.category()));
                    dirty = true;
                }
                if (config.getIntraday() == null) {
                    config.setIntraday(defaultIntraday(strategy.category()));
                    dirty = true;
                }
                if (dirty) {
                    repo.save(config);
                    backfilled++;
                }
            }
        }
        log.info(
                "Strategies: seed done — catalog {} ({} inserted, {} metadata-backfilled)",
                catalog.size(),
                inserted,
                backfilled);
    }

    public List<StrategyDto> list() {
        return list(false);
    }

    /** Catalog strategies; archived ones are excluded unless {@code includeArchived}. */
    public List<StrategyDto> list(boolean includeArchived) {
        List<StrategyDto> out = new ArrayList<>();
        for (StrategyConfigEntity config : repo.findAll()) {
            if (catalog.containsKey(config.getName())
                    && (includeArchived || !Boolean.TRUE.equals(config.getArchived()))) {
                out.add(dto(config));
            }
        }
        out.sort(Comparator.comparing(StrategyDto::name));
        return out;
    }

    public StrategyDto getDetail(String name) {
        return dto(entity(name));
    }

    public void setEnabled(String name, boolean enabled) {
        StrategyConfigEntity config = entity(name);
        config.setEnabled(enabled);
        repo.save(config);
        log.info("Strategy '{}' {}", name, enabled ? "enabled" : "disabled");
    }

    @Transactional
    public void setArchived(String name, boolean archived) {
        StrategyConfigEntity config = entity(name);
        config.setArchived(archived);
        if (archived) {
            config.setEnabled(false); // archived strategies never run
        }
        repo.save(config);
        log.info("Strategy '{}' {}", name, archived ? "archived" : "un-archived");
    }

    public boolean isArchived(String name) {
        return repo.findByName(name)
                .map(e -> Boolean.TRUE.equals(e.getArchived()))
                .orElse(false);
    }

    /** Permanently removes the strategy's config row. Irreversible — unlike archiving. */
    @Transactional
    public void deleteStrategy(String name) {
        repo.deleteByName(name);
        log.info("Strategy '{}' permanently deleted", name);
    }

    /** Un-archive every archived strategy (leaves the enabled flag as-is). Returns how many. */
    @Transactional
    public int unarchiveAll() {
        int count = 0;
        for (StrategyConfigEntity config : repo.findAll()) {
            if (Boolean.TRUE.equals(config.getArchived())) {
                config.setArchived(false);
                repo.save(config);
                count++;
            }
        }
        log.info("Strategies: un-archived {} strategies", count);
        return count;
    }

    @Transactional
    public StrategyDto updateControls(String name, StrategyControlsUpdate update) {
        StrategyConfigEntity config = entity(name);
        if (update.weight() != null) {
            config.setWeight(Math.max(0, update.weight()));
        }
        if (update.invert() != null) {
            config.setInvert(update.invert());
        }
        if (update.directionOverride() != null) {
            String direction = update.directionOverride().isBlank()
                    ? null
                    : update.directionOverride().trim();
            if (direction != null && !DIRECTIONS.contains(direction)) {
                throw new IllegalArgumentException("directionOverride must be one of " + DIRECTIONS + " or empty");
            }
            config.setDirectionOverride(direction);
        }
        if (update.tags() != null) {
            config.setTags(String.join(
                    ",",
                    update.tags().stream()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .distinct()
                            .toList()));
        }
        if (update.favorite() != null) {
            config.setFavorite(update.favorite());
        }
        if (update.notes() != null) {
            config.setNotes(update.notes().isBlank() ? null : update.notes());
        }
        if (update.recommendedTimeframe() != null) {
            // blank / "auto" -> null, meaning "use the category default"
            String raw = update.recommendedTimeframe().trim();
            String frame = Timeframe.isAuto(raw) ? null : raw.toUpperCase();
            if (frame != null && !isKnownTimeframe(frame)) {
                throw new IllegalArgumentException("recommendedTimeframe must be a Timeframe name (e.g. M15, H1, D1)");
            }
            config.setRecommendedTimeframe(frame);
        }
        if (update.intraday() != null) {
            config.setIntraday(update.intraday());
        }
        if (update.defaultStopLossPct() != null) {
            config.setDefaultStopLossPct(Math.max(0, update.defaultStopLossPct()));
        }
        if (update.defaultTakeProfitPct() != null) {
            config.setDefaultTakeProfitPct(Math.max(0, update.defaultTakeProfitPct()));
        }
        if (update.archived() != null) {
            config.setArchived(update.archived());
            if (update.archived()) {
                config.setEnabled(false);
            }
        }
        log.info("Strategy '{}' controls updated", name);
        return dto(repo.save(config));
    }

    @Transactional
    public StrategyDto updateParams(String name, Map<String, Double> params) {
        StrategyConfigEntity config = entity(name);
        Set<String> valid = catalog.get(name).defaultParams().keySet();
        Map<String, Double> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : params.entrySet()) {
            if (!valid.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Unknown param '" + entry.getKey() + "' for " + name + "; valid: " + valid);
            }
            if (entry.getValue() != null && !entry.getValue().isNaN()) {
                clean.put(entry.getKey(), entry.getValue());
            }
        }
        config.setParamsJson(clean.isEmpty() ? null : json.write(clean));
        log.info("Strategy '{}' params set: {}", name, clean);
        return dto(repo.save(config));
    }

    @Transactional
    public StrategyDto resetParams(String name) {
        StrategyConfigEntity config = entity(name);
        config.setParamsJson(null);
        log.info("Strategy '{}' params reset to defaults", name);
        return dto(repo.save(config));
    }

    /** The catalog strategy wrapped with this strategy's invert / direction-override controls. */
    public TradingStrategy getStrategy(String name) {
        StrategyConfigEntity config = entity(name);
        return wrap(config, catalog.get(name));
    }

    /** Effective params: catalog defaults with the user's overrides merged on top. */
    public Map<String, Double> getParams(String name) {
        TradingStrategy strategy = catalog.get(name);
        if (strategy == null) {
            return Map.of();
        }
        Map<String, Double> merged = new LinkedHashMap<>(strategy.defaultParams());
        merged.putAll(overrides(name));
        return merged;
    }

    /** Just the keys the user has overridden (empty if none). */
    public Map<String, Double> overrides(String name) {
        return repo.findByName(name)
                .map(e -> json.readDoubleMap(e.getParamsJson()))
                .orElse(Map.of());
    }

    /** name -> wrapped strategy for all enabled strategies. */
    public Map<String, TradingStrategy> getEnabledStrategies() {
        return strategyMap(false);
    }

    /** name -> wrapped strategy for every non-archived strategy, enabled or not. */
    public Map<String, TradingStrategy> getRunnableStrategies() {
        return strategyMap(true);
    }

    /** name -> wrapped strategy for the given names, in order; unknown / archived names are skipped. */
    public Map<String, TradingStrategy> getStrategies(java.util.Collection<String> names) {
        Map<String, TradingStrategy> out = new LinkedHashMap<>();
        for (String name : names) {
            if (name == null || name.isBlank() || out.containsKey(name) || !catalog.containsKey(name)) {
                continue;
            }
            repo.findByName(name).ifPresent(e -> {
                if (!Boolean.TRUE.equals(e.getArchived())) {
                    out.put(name, wrap(e, catalog.get(name)));
                }
            });
        }
        return out;
    }

    private Map<String, TradingStrategy> strategyMap(boolean includeDisabled) {
        Map<String, TradingStrategy> out = new LinkedHashMap<>();
        for (StrategyConfigEntity config : repo.findAll()) {
            if ((includeDisabled || config.isEnabled())
                    && !Boolean.TRUE.equals(config.getArchived())
                    && catalog.containsKey(config.getName())) {
                out.put(config.getName(), wrap(config, catalog.get(config.getName())));
            }
        }
        return out;
    }

    public double weightOf(String name) {
        return repo.findByName(name)
                .map(e -> e.getWeight() == null ? 1.0 : e.getWeight())
                .orElse(1.0);
    }

    /** The bar interval a strategy is tuned for: stored value, else the category default. */
    public Timeframe recommendedTimeframe(String name) {
        String stored = repo.findByName(name)
                .map(StrategyConfigEntity::getRecommendedTimeframe)
                .orElse(null);
        if (stored != null && isKnownTimeframe(stored)) {
            return Timeframe.from(stored);
        }
        String category = categoryOf(name);
        return Timeframe.from(defaultTimeframe(category));
    }

    /** This strategy's default stop-loss %, or null if none saved (engine default: off). */
    public Double defaultStopLossPct(String name) {
        return repo.findByName(name)
                .map(StrategyConfigEntity::getDefaultStopLossPct)
                .orElse(null);
    }

    /** This strategy's default take-profit %, or null if none saved (engine default: off). */
    public Double defaultTakeProfitPct(String name) {
        return repo.findByName(name)
                .map(StrategyConfigEntity::getDefaultTakeProfitPct)
                .orElse(null);
    }

    /** Persists the risk-default optimizer's winning (timeframe, stopLoss%, takeProfit%) combo. */
    @Transactional
    public void saveRiskDefaults(String name, String timeframe, double stopLossPct, double takeProfitPct) {
        StrategyConfigEntity config = entity(name);
        config.setRecommendedTimeframe(timeframe);
        config.setDefaultStopLossPct(stopLossPct);
        config.setDefaultTakeProfitPct(takeProfitPct);
        repo.save(config);
        log.info("Strategy '{}' risk defaults saved: tf={} SL={}% TP={}%", name, timeframe, stopLossPct, takeProfitPct);
    }

    private static boolean isKnownTimeframe(String value) {
        try {
            Timeframe.valueOf(value.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Catalog defaults for a strategy (throws if unknown). */
    public Map<String, Double> defaultParams(String name) {
        TradingStrategy strategy = catalog.get(name);
        if (strategy == null) {
            throw new NoSuchElementException("Unknown strategy: " + name);
        }
        return new LinkedHashMap<>(strategy.defaultParams());
    }

    public String categoryOf(String name) {
        TradingStrategy strategy = catalog.get(name);
        return strategy == null ? "?" : strategy.category();
    }

    public boolean isStrategy(String name) {
        return catalog.containsKey(name);
    }

    // ---- internals -------------------------------------------------------

    private StrategyConfigEntity entity(String name) {
        return repo.findByName(name).orElseThrow(() -> new NoSuchElementException("Unknown strategy: " + name));
    }

    private TradingStrategy wrap(StrategyConfigEntity config, TradingStrategy strategy) {
        boolean inverted = Boolean.TRUE.equals(config.getInvert());
        String directionOverride = config.getDirectionOverride();
        return (inverted || (directionOverride != null && !directionOverride.isBlank()))
                ? new ConfiguredStrategy(strategy, inverted, directionOverride)
                : strategy;
    }

    private StrategyDto dto(StrategyConfigEntity config) {
        TradingStrategy strategy = catalog.get(config.getName());
        Map<String, Double> defaults = strategy.defaultParams();
        Map<String, Double> overriddenParams = json.readDoubleMap(config.getParamsJson());
        Map<String, Double> effective = new LinkedHashMap<>(defaults);
        effective.putAll(overriddenParams);
        String override = config.getDirectionOverride();
        String effectiveDir = (override == null || override.isBlank()) ? strategy.direction() : override;
        List<String> tags = (config.getTags() == null || config.getTags().isBlank())
                ? List.of()
                : Arrays.asList(config.getTags().split(","));
        String recommendedFrame =
                (config.getRecommendedTimeframe() != null && isKnownTimeframe(config.getRecommendedTimeframe()))
                        ? config.getRecommendedTimeframe().toUpperCase()
                        : defaultTimeframe(config.getCategory());
        boolean intraday = config.getIntraday() != null ? config.getIntraday() : defaultIntraday(config.getCategory());
        return new StrategyDto(
                config.getName(),
                config.getCategory(),
                effectiveDir,
                strategy.direction(),
                config.getDescription(),
                config.isEnabled(),
                Boolean.TRUE.equals(config.getArchived()),
                config.getWeight() == null ? 1.0 : config.getWeight(),
                Boolean.TRUE.equals(config.getInvert()),
                override == null ? "" : override,
                tags,
                Boolean.TRUE.equals(config.getFavorite()),
                config.getNotes() == null ? "" : config.getNotes(),
                recommendedFrame,
                intraday,
                config.getDefaultStopLossPct(),
                config.getDefaultTakeProfitPct(),
                effective,
                defaults,
                new ArrayList<>(overriddenParams.keySet()));
    }
}
