package com.quantplat.strategy.impl;

import com.quantplat.strategy.TradingStrategy;
import java.util.List;

/** The registry of all built-in single-asset strategies (31). Pairs is separate. */
public final class StrategyCatalog {
    private StrategyCatalog() {}

    public static List<TradingStrategy> all() {
        return List.of(
            // trend (7)
            new SmaCross(), new EmaCross(), new MacdTrend(), new TripleMa(),
            new AdxTrend(), new SupertrendStrat(), new KalmanTrend(),
            // mean reversion (6)
            new Rsi2(), new Rsi14(), new BollingerReversion(), new ZScoreReversion(),
            new WilliamsRStrat(), new StochReversion(),
            // momentum (5)
            new RocMomentum(), new High52wBreakout(), new DualMomentum(),
            new VolScaledMomentum(), new RsiMomentum(),
            // breakout (6)
            new Donchian(), new TurtleSystem(), new KeltnerBreakout(),
            new AtrChannelBreakout(), new BollingerSqueeze(), new Nr7Breakout(),
            // volume (3)
            new ObvTrend(), new VwapReversion(), new VolumeSpikeBreakout(),
            // hybrid / pattern / seasonal (4)
            new MacdRsiCombo(), new DualMaAtrStop(), new GapReversion(), new SeasonalityTom()
        );
    }
}
