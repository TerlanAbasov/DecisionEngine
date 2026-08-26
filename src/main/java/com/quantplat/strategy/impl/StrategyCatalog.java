package com.quantplat.strategy.impl;

import com.quantplat.strategy.TradingStrategy;
import java.util.List;

/** The registry of all built-in single-asset strategies (100). Pairs is separate. */
public final class StrategyCatalog {
    private StrategyCatalog() {}

    public static List<TradingStrategy> all() {
        return List.of(
            // trend (20)
            new SmaCross(), new EmaCross(), new MacdTrend(), new TripleMa(),
            new AdxTrend(), new SupertrendStrat(), new KalmanTrend(),
            new HullMaTrend(), new DemaCross(), new TemaCross(), new VwmaTrend(),
            new AroonTrend(), new VortexTrend(), new TrixSignal(), new LinregSlope(),
            new ParabolicSar(), new EmaRibbon(), new ElderRayTrend(), new CoppockCurve(),
            new DonchianMidline(),
            // mean reversion (18)
            new Rsi2(), new Rsi14(), new BollingerReversion(), new ZScoreReversion(),
            new WilliamsRStrat(), new StochReversion(),
            new CciReversion(), new MfiReversion(), new CmoReversion(), new BollingerPctB(),
            new Rsi21(), new Rsi2Extreme(), new DpoReversion(), new KeltnerReversion(),
            new VwapBandReversion(), new AtrBandReversion(), new UltimateOscillatorReversion(),
            new StochRsi(),
            // momentum (15)
            new RocMomentum(), new High52wBreakout(), new DualMomentum(),
            new VolScaledMomentum(), new RsiMomentum(),
            new MultiHorizonMomentum(), new MacdHistogramSlope(), new ForceIndexMomentum(),
            new CciMomentum(), new AroonOscillatorMomentum(), new RocAcceleration(),
            new HullMomentum(), new TrixMomentum(), new StreakMomentum(), new MaStackMomentum(),
            // breakout (16)
            new Donchian(), new TurtleSystem(), new KeltnerBreakout(),
            new AtrChannelBreakout(), new BollingerSqueeze(), new Nr7Breakout(),
            new CciBreakout(), new VortexBreakout(), new DonchianSqueeze(),
            new VolatilityExpansionBreakout(), new PivotPointBreakout(), new CamarillaBreakout(),
            new RangeExpansionBreakout(), new ThreeBarBreakout(), new MacdZeroCross(),
            new LinregChannelBreakout(),
            // volume (10)
            new ObvTrend(), new VwapReversion(), new VolumeSpikeBreakout(),
            new MfiTrend(), new ChaikinMoneyFlow(), new EaseOfMovement(), new AccumDistribution(),
            new VolumePriceTrend(), new RelativeVolumeZscore(), new VwmaVolumeConfirm(),
            // hybrid (8)
            new MacdRsiCombo(), new DualMaAtrStop(), new TripleConfirmation(),
            new TrendVolumeCombo(), new BreakoutMomentumCombo(), new RsiPullbackTrendFilter(),
            new AdaptiveRegimeSwitch(), new SupertrendRsiCombo(),
            // pattern (6)
            new GapReversion(), new InsideBarBreakout(), new OutsideBarReversal(),
            new ConsecutiveTrendBars(), new HammerReversal(), new WideRangeBarFade(),
            // seasonal (7)
            new SeasonalityTom(), new DayOfWeekFilter(), new JanuaryEffect(), new SellInMay(),
            new QuarterEndEffect(), new SantaClausRally(), new MidMonthEffect()
        );
    }
}
