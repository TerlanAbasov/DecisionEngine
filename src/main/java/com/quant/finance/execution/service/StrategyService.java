package com.quant.finance.execution.service;

import com.quant.finance.execution.domain.StrategyConfigEntity;
import com.quant.finance.execution.dto.Dtos.StrategyControlsUpdate;
import com.quant.finance.execution.dto.Dtos.StrategyDto;
import com.quant.finance.execution.engine.Timeframe;
import com.quant.finance.execution.repository.StrategyConfigRepository;
import com.quant.finance.execution.strategy.ConfiguredStrategy;
import com.quant.finance.execution.strategy.TradingStrategy;
import com.quant.finance.execution.strategy.impl.StrategyCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class StrategyService {

    private static final Logger log = LoggerFactory.getLogger(StrategyService.class);

    private static final Set<String> DIRECTIONS = Set.of("long_only", "short_only", "long_short");

    /** Default bar interval per category — the frame each family of strategies is usually run on for day trading. */
    private static final Map<String, String> DEFAULT_TF = Map.of(
            "trend", "H1", "momentum", "H1", "hybrid", "H1",
            "mean_reversion", "M15", "breakout", "M15", "volume", "M15", "pattern", "M15",
            "seasonal", "D1");

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
        for (TradingStrategy s : StrategyCatalog.all()) catalog.put(s.name(), s);
    }

    /** Seed strategy_config from the catalog on first startup, and backfill day-trading metadata. */
    @Transactional
    public void seed() {
        int inserted = 0, backfilled = 0;
        for (TradingStrategy s : catalog.values()) {
            Optional<StrategyConfigEntity> existing = repo.findByName(s.name());
            if (existing.isEmpty()) {
                StrategyConfigEntity e = new StrategyConfigEntity();
                e.setName(s.name());
                e.setCategory(s.category());
                e.setDirection(s.direction());
                e.setDescription(s.description());
                e.setEnabled(true);
                e.setRecommendedTimeframe(defaultTimeframe(s.category()));
                e.setIntraday(defaultIntraday(s.category()));
                repo.save(e);
                inserted++;
            } else {
                // rows created before this column existed -> fill the per-category default once
                StrategyConfigEntity e = existing.get();
                boolean dirty = false;
                if (e.getRecommendedTimeframe() == null) {
                    e.setRecommendedTimeframe(defaultTimeframe(s.category())); dirty = true;
                }
                if (e.getIntraday() == null) {
                    e.setIntraday(defaultIntraday(s.category())); dirty = true;
                }
                if (dirty) { repo.save(e); backfilled++; }
            }
        }
        log.info("Strategies: seed done — catalog {} ({} inserted, {} metadata-backfilled)",
                catalog.size(), inserted, backfilled);
    }

    public List<StrategyDto> list() {
        return list(false);
    }

    /** Catalog strategies; archived ones are excluded unless {@code includeArchived}. */
    public List<StrategyDto> list(boolean includeArchived) {
        List<StrategyDto> out = new ArrayList<>();
        for (StrategyConfigEntity e : repo.findAll())
            if (catalog.containsKey(e.getName()) && (includeArchived || !Boolean.TRUE.equals(e.getArchived())))
                out.add(dto(e));
        out.sort(Comparator.comparing(StrategyDto::name));
        return out;
    }

    public StrategyDto getDetail(String name) {
        return dto(entity(name));
    }

    public void setEnabled(String name, boolean enabled) {
        StrategyConfigEntity e = entity(name);
        e.setEnabled(enabled);
        repo.save(e);
        log.info("Strategy '{}' {}", name, enabled ? "enabled" : "disabled");
    }

    @Transactional
    public void setArchived(String name, boolean archived) {
        StrategyConfigEntity e = entity(name);
        e.setArchived(archived);
        if (archived) e.setEnabled(false);       // archived strategies never run
        repo.save(e);
        log.info("Strategy '{}' {}", name, archived ? "archived" : "un-archived");
    }

    public boolean isArchived(String name) {
        return repo.findByName(name).map(e -> Boolean.TRUE.equals(e.getArchived())).orElse(false);
    }

    /** Un-archive every archived strategy (leaves the enabled flag as-is). Returns how many. */
    @Transactional
    public int unarchiveAll() {
        int n = 0;
        for (StrategyConfigEntity e : repo.findAll()) {
            if (Boolean.TRUE.equals(e.getArchived())) {
                e.setArchived(false);
                repo.save(e);
                n++;
            }
        }
        log.info("Strategies: un-archived {} strategies", n);
        return n;
    }

    @Transactional
    public StrategyDto updateControls(String name, StrategyControlsUpdate u) {
        StrategyConfigEntity e = entity(name);
        if (u.weight() != null) e.setWeight(Math.max(0, u.weight()));
        if (u.invert() != null) e.setInvert(u.invert());
        if (u.directionOverride() != null) {
            String d = u.directionOverride().isBlank() ? null : u.directionOverride().trim();
            if (d != null && !DIRECTIONS.contains(d))
                throw new IllegalArgumentException("directionOverride must be one of " + DIRECTIONS + " or empty");
            e.setDirectionOverride(d);
        }
        if (u.tags() != null) e.setTags(String.join(",", u.tags().stream().map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().toList()));
        if (u.favorite() != null) e.setFavorite(u.favorite());
        if (u.notes() != null) e.setNotes(u.notes().isBlank() ? null : u.notes());
        if (u.recommendedTimeframe() != null) {
            // blank / "auto" -> null, meaning "use the category default"
            String raw = u.recommendedTimeframe().trim();
            String tf = Timeframe.isAuto(raw) ? null : raw.toUpperCase();
            if (tf != null && !isKnownTimeframe(tf))
                throw new IllegalArgumentException("recommendedTimeframe must be a Timeframe name (e.g. M15, H1, D1)");
            e.setRecommendedTimeframe(tf);
        }
        if (u.intraday() != null) e.setIntraday(u.intraday());
        if (u.archived() != null) {
            e.setArchived(u.archived());
            if (u.archived()) e.setEnabled(false);
        }
        log.info("Strategy '{}' controls updated", name);
        return dto(repo.save(e));
    }

    @Transactional
    public StrategyDto updateParams(String name, Map<String, Double> params) {
        StrategyConfigEntity e = entity(name);
        Set<String> valid = catalog.get(name).defaultParams().keySet();
        Map<String, Double> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Double> p : params.entrySet()) {
            if (!valid.contains(p.getKey()))
                throw new IllegalArgumentException("Unknown param '" + p.getKey() + "' for " + name + "; valid: " + valid);
            if (p.getValue() != null && !p.getValue().isNaN()) clean.put(p.getKey(), p.getValue());
        }
        e.setParamsJson(clean.isEmpty() ? null : json.write(clean));
        log.info("Strategy '{}' params set: {}", name, clean);
        return dto(repo.save(e));
    }

    @Transactional
    public StrategyDto resetParams(String name) {
        StrategyConfigEntity e = entity(name);
        e.setParamsJson(null);
        log.info("Strategy '{}' params reset to defaults", name);
        return dto(repo.save(e));
    }

    /** The catalog strategy wrapped with this strategy's invert / direction-override controls. */
    public TradingStrategy getStrategy(String name) {
        StrategyConfigEntity e = entity(name);
        return wrap(e, catalog.get(name));
    }

    /** Effective params: catalog defaults with the user's overrides merged on top. */
    public Map<String, Double> getParams(String name) {
        TradingStrategy s = catalog.get(name);
        if (s == null) return Map.of();
        Map<String, Double> merged = new LinkedHashMap<>(s.defaultParams());
        merged.putAll(overrides(name));
        return merged;
    }

    /** Just the keys the user has overridden (empty if none). */
    public Map<String, Double> overrides(String name) {
        return repo.findByName(name).map(e -> json.readParams(e.getParamsJson())).orElse(Map.of());
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
            if (name == null || name.isBlank() || out.containsKey(name) || !catalog.containsKey(name)) continue;
            repo.findByName(name).ifPresent(e -> {
                if (!Boolean.TRUE.equals(e.getArchived()))
                    out.put(name, wrap(e, catalog.get(name)));
            });
        }
        return out;
    }

    private Map<String, TradingStrategy> strategyMap(boolean includeDisabled) {
        Map<String, TradingStrategy> out = new LinkedHashMap<>();
        for (StrategyConfigEntity e : repo.findAll())
            if ((includeDisabled || e.isEnabled()) && !Boolean.TRUE.equals(e.getArchived())
                    && catalog.containsKey(e.getName()))
                out.put(e.getName(), wrap(e, catalog.get(e.getName())));
        return out;
    }

    public double weightOf(String name) {
        return repo.findByName(name).map(e -> e.getWeight() == null ? 1.0 : e.getWeight()).orElse(1.0);
    }

    /** The bar interval a strategy is tuned for: stored value, else the category default. */
    public Timeframe recommendedTimeframe(String name) {
        String stored = repo.findByName(name).map(StrategyConfigEntity::getRecommendedTimeframe).orElse(null);
        if (stored != null && isKnownTimeframe(stored)) return Timeframe.from(stored);
        String category = categoryOf(name);
        return Timeframe.from(defaultTimeframe(category));
    }

    private static boolean isKnownTimeframe(String s) {
        try { Timeframe.valueOf(s.trim().toUpperCase()); return true; }
        catch (IllegalArgumentException ex) { return false; }
    }

    /** Catalog defaults for a strategy (throws if unknown). */
    public Map<String, Double> defaultParams(String name) {
        TradingStrategy s = catalog.get(name);
        if (s == null) throw new NoSuchElementException("Unknown strategy: " + name);
        return new LinkedHashMap<>(s.defaultParams());
    }

    public String categoryOf(String name) {
        TradingStrategy s = catalog.get(name);
        return s == null ? "?" : s.category();
    }

    public boolean isStrategy(String name) { return catalog.containsKey(name); }

    // ---- internals -------------------------------------------------------

    private StrategyConfigEntity entity(String name) {
        return repo.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Unknown strategy: " + name));
    }

    private TradingStrategy wrap(StrategyConfigEntity e, TradingStrategy s) {
        boolean inv = Boolean.TRUE.equals(e.getInvert());
        String dir = e.getDirectionOverride();
        return (inv || (dir != null && !dir.isBlank())) ? new ConfiguredStrategy(s, inv, dir) : s;
    }

    private StrategyDto dto(StrategyConfigEntity e) {
        TradingStrategy s = catalog.get(e.getName());
        Map<String, Double> defaults = s.defaultParams();
        Map<String, Double> ov = json.readParams(e.getParamsJson());
        Map<String, Double> effective = new LinkedHashMap<>(defaults);
        effective.putAll(ov);
        String override = e.getDirectionOverride();
        String effectiveDir = (override == null || override.isBlank()) ? s.direction() : override;
        List<String> tags = (e.getTags() == null || e.getTags().isBlank())
                ? List.of() : Arrays.asList(e.getTags().split(","));
        String recTf = (e.getRecommendedTimeframe() != null && isKnownTimeframe(e.getRecommendedTimeframe()))
                ? e.getRecommendedTimeframe().toUpperCase()
                : defaultTimeframe(e.getCategory());
        boolean intraday = e.getIntraday() != null ? e.getIntraday() : defaultIntraday(e.getCategory());
        return new StrategyDto(e.getName(), e.getCategory(), effectiveDir, s.direction(),
                e.getDescription(), e.isEnabled(), Boolean.TRUE.equals(e.getArchived()),
                e.getWeight() == null ? 1.0 : e.getWeight(),
                Boolean.TRUE.equals(e.getInvert()),
                override == null ? "" : override,
                tags, Boolean.TRUE.equals(e.getFavorite()),
                e.getNotes() == null ? "" : e.getNotes(),
                recTf, intraday,
                effective, defaults, new ArrayList<>(ov.keySet()));
    }
}
