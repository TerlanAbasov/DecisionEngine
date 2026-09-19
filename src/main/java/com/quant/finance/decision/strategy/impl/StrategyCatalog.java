package com.quant.finance.decision.strategy.impl;

import com.quant.finance.decision.strategy.TradingStrategy;
import java.util.List;

/** The registry of all built-in single-asset strategies (55: 50 kept by backtest Sharpe
 *  ranking, 3 restored on request despite ranking low there, plus WaveTrend and
 *  Support/Resistance Bounce). Pairs is separate. */
public final class StrategyCatalog {
    private StrategyCatalog() {}

    public static List<TradingStrategy> all() {
        return List.of(
            // trend (14)
            new SmaCross(), new EmaCross(), new MacdTrend(), new TripleMa(),
            new SupertrendStrat(), new KalmanTrend(), new DemaCross(), new VwmaTrend(),
            new VortexTrend(), new LinregSlope(), new EmaRibbon(), new ElderRayTrend(),
            new CoppockCurve(), new DonchianMidline(),
            // mean reversion (3)
            new Rsi2(), new WaveTrend(), new SupportResistanceBounce(),
            // momentum (11)
            new RocMomentum(), new High52wBreakout(), new DualMomentum(),
            new VolScaledMomentum(), new RsiMomentum(), new MultiHorizonMomentum(),
            new ForceIndexMomentum(), new CciMomentum(), new TrixMomentum(), new MaStackMomentum(),
            new HullMomentum(),
            // breakout (5)
            new Donchian(), new TurtleSystem(), new KeltnerBreakout(),
            new RangeExpansionBreakout(), new LinregChannelBreakout(),
            // volume (5)
            new VwapReversion(), new VolumeSpikeBreakout(), new VolumePriceTrend(),
            new RelativeVolumeZscore(), new VwmaVolumeConfirm(),
            // hybrid (6)
            new MacdRsiCombo(), new DualMaAtrStop(), new TripleConfirmation(),
            new BreakoutMomentumCombo(), new RsiPullbackTrendFilter(), new SupertrendRsiCombo(),
            // pattern (1)
            new OutsideBarReversal(),
            // seasonal (6)
            new SeasonalityTom(), new DayOfWeekFilter(), new JanuaryEffect(), new SellInMay(),
            new SantaClausRally(), new MidMonthEffect(),
            // adaptive / advanced (4)
            new IchimokuCloud(), new ConnorsRsi(), new KamaTrend(), new ChoppinessTrend()
        );
    }
}
