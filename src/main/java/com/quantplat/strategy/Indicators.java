package com.quantplat.strategy;

/**
 * Vectorised technical indicators over double[]. Every value at index t uses only
 * data at indices &lt;= t (no look-ahead). NaN is used for the warm-up window.
 */
public final class Indicators {
    private Indicators() {}

    private static double[] nan(int n) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, Double.NaN);
        return a;
    }

    public static double[] sma(double[] s, int n) {
        double[] out = nan(s.length);
        double sum = 0;
        for (int i = 0; i < s.length; i++) {
            sum += s[i];
            if (i >= n) sum -= s[i - n];
            if (i >= n - 1) out[i] = sum / n;
        }
        return out;
    }

    public static double[] ema(double[] s, int n) {
        double[] out = nan(s.length);
        double k = 2.0 / (n + 1);
        double prev = 0;
        boolean seeded = false;
        double seedSum = 0;
        for (int i = 0; i < s.length; i++) {
            if (!seeded) {
                seedSum += s[i];
                if (i == n - 1) { prev = seedSum / n; out[i] = prev; seeded = true; }
            } else {
                prev = s[i] * k + prev * (1 - k);
                out[i] = prev;
            }
        }
        return out;
    }

    /** Wilder smoothing (alpha = 1/n). */
    public static double[] wilder(double[] s, int n) {
        double[] out = nan(s.length);
        double prev = 0, seedSum = 0;
        boolean seeded = false;
        for (int i = 0; i < s.length; i++) {
            double v = Double.isNaN(s[i]) ? 0 : s[i];
            if (!seeded) {
                seedSum += v;
                if (i == n - 1) { prev = seedSum / n; out[i] = prev; seeded = true; }
            } else {
                prev = prev + (v - prev) / n;
                out[i] = prev;
            }
        }
        return out;
    }

    public static double[] roc(double[] s, int n) {
        double[] out = nan(s.length);
        for (int i = n; i < s.length; i++) out[i] = (s[i] / s[i - n] - 1.0) * 100.0;
        return out;
    }

    public static double[] stddev(double[] s, int n) {
        double[] out = nan(s.length);
        for (int i = n - 1; i < s.length; i++) {
            double mean = 0;
            for (int j = i - n + 1; j <= i; j++) mean += s[j];
            mean /= n;
            double var = 0;
            for (int j = i - n + 1; j <= i; j++) var += (s[j] - mean) * (s[j] - mean);
            out[i] = Math.sqrt(var / n);
        }
        return out;
    }

    public static double[] zscore(double[] s, int n) {
        double[] mean = sma(s, n), sd = stddev(s, n), out = nan(s.length);
        for (int i = 0; i < s.length; i++)
            if (!Double.isNaN(sd[i]) && sd[i] != 0) out[i] = (s[i] - mean[i]) / sd[i];
        return out;
    }

    public static double[] rsi(double[] s, int n) {
        double[] up = new double[s.length], dn = new double[s.length];
        for (int i = 1; i < s.length; i++) {
            double d = s[i] - s[i - 1];
            up[i] = Math.max(d, 0);
            dn[i] = Math.max(-d, 0);
        }
        double[] au = wilder(up, n), ad = wilder(dn, n), out = nan(s.length);
        for (int i = 0; i < s.length; i++) {
            if (Double.isNaN(au[i])) continue;
            if (ad[i] == 0) { out[i] = 100; continue; }
            double rs = au[i] / ad[i];
            out[i] = 100.0 - 100.0 / (1.0 + rs);
        }
        return out;
    }

    public static double[] trueRange(BarSeries b) {
        int n = b.size();
        double[] tr = nan(n);
        for (int i = 0; i < n; i++) {
            if (i == 0) { tr[i] = b.high[i] - b.low[i]; continue; }
            double a = b.high[i] - b.low[i];
            double c = Math.abs(b.high[i] - b.close[i - 1]);
            double d = Math.abs(b.low[i] - b.close[i - 1]);
            tr[i] = Math.max(a, Math.max(c, d));
        }
        return tr;
    }

    public static double[] atr(BarSeries b, int n) {
        return wilder(trueRange(b), n);
    }

    /** Bollinger Bands: returns {lower, mid, upper}. */
    public static double[][] bollinger(double[] s, int n, double k) {
        double[] mid = sma(s, n), sd = stddev(s, n);
        double[] lo = nan(s.length), up = nan(s.length);
        for (int i = 0; i < s.length; i++)
            if (!Double.isNaN(mid[i])) { lo[i] = mid[i] - k * sd[i]; up[i] = mid[i] + k * sd[i]; }
        return new double[][]{lo, mid, up};
    }

    /** MACD: returns {macdLine, signalLine, histogram}. */
    public static double[][] macd(double[] s, int fast, int slow, int signal) {
        double[] ef = ema(s, fast), es = ema(s, slow), line = nan(s.length);
        for (int i = 0; i < s.length; i++)
            if (!Double.isNaN(ef[i]) && !Double.isNaN(es[i])) line[i] = ef[i] - es[i];
        double[] sig = ema(replaceNan(line), signal), hist = nan(s.length);
        for (int i = 0; i < s.length; i++)
            if (!Double.isNaN(line[i]) && !Double.isNaN(sig[i])) hist[i] = line[i] - sig[i];
        return new double[][]{line, sig, hist};
    }

    private static double[] replaceNan(double[] a) {
        double[] out = a.clone();
        for (int i = 0; i < out.length; i++) if (Double.isNaN(out[i])) out[i] = 0;
        return out;
    }

    /** ADX: returns {adx, plusDI, minusDI}. */
    public static double[][] adx(BarSeries b, int n) {
        int len = b.size();
        double[] plusDM = new double[len], minusDM = new double[len];
        for (int i = 1; i < len; i++) {
            double up = b.high[i] - b.high[i - 1];
            double dn = b.low[i - 1] - b.low[i];
            plusDM[i] = (up > dn && up > 0) ? up : 0;
            minusDM[i] = (dn > up && dn > 0) ? dn : 0;
        }
        double[] atr = wilder(trueRange(b), n);
        double[] pdi = nan(len), mdi = nan(len), dx = nan(len);
        double[] spdm = wilder(plusDM, n), smdm = wilder(minusDM, n);
        for (int i = 0; i < len; i++) {
            if (Double.isNaN(atr[i]) || atr[i] == 0) continue;
            pdi[i] = 100 * spdm[i] / atr[i];
            mdi[i] = 100 * smdm[i] / atr[i];
            double sum = pdi[i] + mdi[i];
            dx[i] = sum == 0 ? 0 : 100 * Math.abs(pdi[i] - mdi[i]) / sum;
        }
        return new double[][]{wilder(dx, n), pdi, mdi};
    }

    public static double[] rollingMax(double[] s, int n) {
        double[] out = nan(s.length);
        for (int i = n - 1; i < s.length; i++) {
            double m = -Double.MAX_VALUE;
            for (int j = i - n + 1; j <= i; j++) m = Math.max(m, s[j]);
            out[i] = m;
        }
        return out;
    }

    public static double[] rollingMin(double[] s, int n) {
        double[] out = nan(s.length);
        for (int i = n - 1; i < s.length; i++) {
            double m = Double.MAX_VALUE;
            for (int j = i - n + 1; j <= i; j++) m = Math.min(m, s[j]);
            out[i] = m;
        }
        return out;
    }

    /** Stochastic: returns {%K, %D}. */
    public static double[][] stochastic(BarSeries b, int n, int d) {
        double[] hh = rollingMax(b.high, n), ll = rollingMin(b.low, n), k = nan(b.size());
        for (int i = 0; i < b.size(); i++) {
            if (Double.isNaN(hh[i])) continue;
            double range = hh[i] - ll[i];
            k[i] = range == 0 ? 50 : 100 * (b.close[i] - ll[i]) / range;
        }
        return new double[][]{k, sma(replaceNan(k), d)};
    }

    public static double[] williamsR(BarSeries b, int n) {
        double[] hh = rollingMax(b.high, n), ll = rollingMin(b.low, n), out = nan(b.size());
        for (int i = 0; i < b.size(); i++) {
            if (Double.isNaN(hh[i])) continue;
            double range = hh[i] - ll[i];
            out[i] = range == 0 ? -50 : -100 * (hh[i] - b.close[i]) / range;
        }
        return out;
    }

    /** Keltner channel: returns {lower, mid, upper}. */
    public static double[][] keltner(BarSeries b, int n, double m) {
        double[] mid = ema(b.close, n), atr = atr(b, n);
        double[] lo = nan(b.size()), up = nan(b.size());
        for (int i = 0; i < b.size(); i++)
            if (!Double.isNaN(mid[i]) && !Double.isNaN(atr[i])) { lo[i] = mid[i] - m * atr[i]; up[i] = mid[i] + m * atr[i]; }
        return new double[][]{lo, mid, up};
    }

    /** Supertrend direction: +1 uptrend, -1 downtrend. */
    public static double[] supertrend(BarSeries b, int n, double mult) {
        int len = b.size();
        double[] atr = atr(b, n), dir = nan(len);
        double[] fu = new double[len], fl = new double[len];
        boolean up = true;
        for (int i = 0; i < len; i++) {
            if (i == 0 || Double.isNaN(atr[i])) { continue; }
            double hl2 = (b.high[i] + b.low[i]) / 2;
            double bu = hl2 + mult * atr[i], bl = hl2 - mult * atr[i];
            fu[i] = (b.close[i - 1] <= fu[i - 1] || fu[i - 1] == 0) ? Math.min(bu, fu[i - 1] == 0 ? bu : fu[i - 1]) : bu;
            fl[i] = (b.close[i - 1] >= fl[i - 1]) ? Math.max(bl, fl[i - 1]) : bl;
            if (b.close[i] > fu[i - 1]) up = true;
            else if (b.close[i] < fl[i - 1]) up = false;
            dir[i] = up ? 1 : -1;
        }
        return dir;
    }

    public static double[] obv(BarSeries b) {
        double[] out = new double[b.size()];
        for (int i = 1; i < b.size(); i++) {
            double sign = Math.signum(b.close[i] - b.close[i - 1]);
            out[i] = out[i - 1] + sign * b.volume[i];
        }
        return out;
    }

    public static double[] vwap(BarSeries b, int n) {
        double[] out = nan(b.size());
        for (int i = n - 1; i < b.size(); i++) {
            double pv = 0, vv = 0;
            for (int j = i - n + 1; j <= i; j++) {
                double tp = (b.high[j] + b.low[j] + b.close[j]) / 3;
                pv += tp * b.volume[j];
                vv += b.volume[j];
            }
            out[i] = vv == 0 ? Double.NaN : pv / vv;
        }
        return out;
    }

    /** 1-D Kalman filter estimate of price level (smooth trend). */
    public static double[] kalman(double[] s, double q, double r) {
        double[] out = new double[s.length];
        double x = s[0], p = 1;
        out[0] = x;
        for (int i = 1; i < s.length; i++) {
            double pm = p + q;
            double k = pm / (pm + r);
            x = x + k * (s[i] - x);
            p = (1 - k) * pm;
            out[i] = x;
        }
        return out;
    }

    private static double[] wma(double[] s, int n) {
        double[] out = nan(s.length);
        double denom = n * (n + 1) / 2.0;
        for (int i = n - 1; i < s.length; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) sum += s[i - j] * (n - j);
            out[i] = sum / denom;
        }
        return out;
    }

    /** Hull Moving Average: WMA(2*WMA(n/2) - WMA(n), sqrt(n)) — lower lag than SMA/EMA. */
    public static double[] hullMa(double[] s, int n) {
        int half = Math.max(1, n / 2);
        int sqrtN = Math.max(1, (int) Math.round(Math.sqrt(n)));
        double[] wmaHalf = wma(s, half), wmaFull = wma(s, n);
        double[] raw = nan(s.length);
        for (int i = 0; i < s.length; i++)
            if (!Double.isNaN(wmaHalf[i]) && !Double.isNaN(wmaFull[i])) raw[i] = 2 * wmaHalf[i] - wmaFull[i];
        return wma(replaceNan(raw), sqrtN);
    }

    /** Double EMA: 2*EMA - EMA(EMA) — less lag than a plain EMA. */
    public static double[] dema(double[] s, int n) {
        double[] e1 = ema(s, n), e2 = ema(replaceNan(e1), n), out = nan(s.length);
        for (int i = 0; i < s.length; i++)
            if (!Double.isNaN(e1[i]) && !Double.isNaN(e2[i])) out[i] = 2 * e1[i] - e2[i];
        return out;
    }

    /** Triple EMA: 3*EMA1 - 3*EMA2 + EMA3. */
    public static double[] tema(double[] s, int n) {
        double[] e1 = ema(s, n), e2 = ema(replaceNan(e1), n), e3 = ema(replaceNan(e2), n), out = nan(s.length);
        for (int i = 0; i < s.length; i++)
            if (!Double.isNaN(e1[i]) && !Double.isNaN(e2[i]) && !Double.isNaN(e3[i]))
                out[i] = 3 * e1[i] - 3 * e2[i] + e3[i];
        return out;
    }

    /** Volume-weighted moving average over n bars. */
    public static double[] vwma(BarSeries b, int n) {
        double[] out = nan(b.size());
        for (int i = n - 1; i < b.size(); i++) {
            double pv = 0, vv = 0;
            for (int j = i - n + 1; j <= i; j++) { pv += b.close[j] * b.volume[j]; vv += b.volume[j]; }
            out[i] = vv == 0 ? Double.NaN : pv / vv;
        }
        return out;
    }

    /** Aroon: returns {up, down}, each 0..100 based on bars since the n-period high/low. */
    public static double[][] aroon(BarSeries b, int n) {
        int len = b.size();
        double[] up = nan(len), down = nan(len);
        for (int i = n; i < len; i++) {
            int hiIdx = i, loIdx = i;
            double hi = b.high[i], lo = b.low[i];
            for (int j = i - n; j <= i; j++) {
                if (b.high[j] >= hi) { hi = b.high[j]; hiIdx = j; }
                if (b.low[j] <= lo) { lo = b.low[j]; loIdx = j; }
            }
            up[i] = 100.0 * (n - (i - hiIdx)) / n;
            down[i] = 100.0 * (n - (i - loIdx)) / n;
        }
        return new double[][]{up, down};
    }

    /** Vortex Indicator: returns {VI+, VI-}. */
    public static double[][] vortex(BarSeries b, int n) {
        int len = b.size();
        double[] vmPlus = new double[len], vmMinus = new double[len], tr = trueRange(b);
        for (int i = 1; i < len; i++) {
            vmPlus[i] = Math.abs(b.high[i] - b.low[i - 1]);
            vmMinus[i] = Math.abs(b.low[i] - b.high[i - 1]);
        }
        double[] viP = nan(len), viM = nan(len);
        for (int i = n; i < len; i++) {
            double sp = 0, sm = 0, st = 0;
            for (int j = i - n + 1; j <= i; j++) { sp += vmPlus[j]; sm += vmMinus[j]; st += Double.isNaN(tr[j]) ? 0 : tr[j]; }
            if (st == 0) continue;
            viP[i] = sp / st; viM[i] = sm / st;
        }
        return new double[][]{viP, viM};
    }

    /** TRIX: rate of change of a triple-smoothed EMA. */
    public static double[] trix(double[] s, int n) {
        double[] e1 = ema(s, n), e2 = ema(replaceNan(e1), n), e3 = ema(replaceNan(e2), n), out = nan(s.length);
        for (int i = 1; i < s.length; i++)
            if (!Double.isNaN(e3[i]) && !Double.isNaN(e3[i - 1]) && e3[i - 1] != 0)
                out[i] = (e3[i] / e3[i - 1] - 1) * 100;
        return out;
    }

    /** Rolling linear regression over the last n points: returns {fitted value at the current bar, slope}. */
    public static double[][] linreg(double[] s, int n) {
        int len = s.length;
        double[] val = nan(len), slope = nan(len);
        double sx = 0, sxx = 0;
        for (int i = 0; i < n; i++) { sx += i; sxx += (double) i * i; }
        double xBar = sx / n;
        double denom = sxx - n * xBar * xBar;
        for (int i = n - 1; i < len; i++) {
            double sy = 0, sxy = 0;
            for (int j = 0; j < n; j++) { double y = s[i - n + 1 + j]; sy += y; sxy += j * y; }
            double yBar = sy / n;
            double b1 = denom == 0 ? 0 : (sxy - n * xBar * yBar) / denom;
            slope[i] = b1;
            val[i] = (yBar - b1 * xBar) + b1 * (n - 1);
        }
        return new double[][]{val, slope};
    }

    /** Wilder's Parabolic SAR trend flag: +1 while price is above the SAR (uptrend), -1 below. */
    public static double[] psar(BarSeries b, double step, double maxStep) {
        int len = b.size();
        double[] dir = nan(len);
        if (len < 2) return dir;
        boolean up = true;
        double sar = b.low[0], ep = b.high[0], af = step;
        dir[0] = 1;
        for (int i = 1; i < len; i++) {
            sar = sar + af * (ep - sar);
            if (up) {
                sar = Math.min(sar, Math.min(b.low[i - 1], i >= 2 ? b.low[i - 2] : b.low[i - 1]));
                if (b.low[i] < sar) { up = false; sar = ep; ep = b.low[i]; af = step; }
                else if (b.high[i] > ep) { ep = b.high[i]; af = Math.min(maxStep, af + step); }
            } else {
                sar = Math.max(sar, Math.max(b.high[i - 1], i >= 2 ? b.high[i - 2] : b.high[i - 1]));
                if (b.high[i] > sar) { up = true; sar = ep; ep = b.high[i]; af = step; }
                else if (b.low[i] < ep) { ep = b.low[i]; af = Math.min(maxStep, af + step); }
            }
            dir[i] = up ? 1 : -1;
        }
        return dir;
    }

    /** Commodity Channel Index. */
    public static double[] cci(BarSeries b, int n) {
        int len = b.size();
        double[] tp = new double[len];
        for (int i = 0; i < len; i++) tp[i] = (b.high[i] + b.low[i] + b.close[i]) / 3.0;
        double[] tpSma = sma(tp, n), out = nan(len);
        for (int i = n - 1; i < len; i++) {
            double md = 0;
            for (int j = i - n + 1; j <= i; j++) md += Math.abs(tp[j] - tpSma[i]);
            md /= n;
            out[i] = md == 0 ? 0 : (tp[i] - tpSma[i]) / (0.015 * md);
        }
        return out;
    }

    /** Money Flow Index (volume-weighted RSI). */
    public static double[] mfi(BarSeries b, int n) {
        int len = b.size();
        double[] tp = new double[len], posFlow = new double[len], negFlow = new double[len];
        for (int i = 0; i < len; i++) tp[i] = (b.high[i] + b.low[i] + b.close[i]) / 3.0;
        for (int i = 1; i < len; i++) {
            double mf = tp[i] * b.volume[i];
            if (tp[i] > tp[i - 1]) posFlow[i] = mf;
            else if (tp[i] < tp[i - 1]) negFlow[i] = mf;
        }
        double[] out = nan(len);
        for (int i = n; i < len; i++) {
            double pf = 0, nf = 0;
            for (int j = i - n + 1; j <= i; j++) { pf += posFlow[j]; nf += negFlow[j]; }
            out[i] = nf == 0 ? 100 : 100 - 100 / (1 + pf / nf);
        }
        return out;
    }

    /** Chande Momentum Oscillator. */
    public static double[] cmo(double[] s, int n) {
        int len = s.length;
        double[] up = new double[len], dn = new double[len];
        for (int i = 1; i < len; i++) {
            double d = s[i] - s[i - 1];
            up[i] = Math.max(d, 0); dn[i] = Math.max(-d, 0);
        }
        double[] out = nan(len);
        for (int i = n; i < len; i++) {
            double su = 0, sd = 0;
            for (int j = i - n + 1; j <= i; j++) { su += up[j]; sd += dn[j]; }
            out[i] = (su + sd) == 0 ? 0 : 100 * (su - sd) / (su + sd);
        }
        return out;
    }

    /** Detrended Price Oscillator: price minus an SMA lagged by n/2+1 bars (no look-ahead). */
    public static double[] dpo(double[] s, int n) {
        double[] smaS = sma(s, n);
        int shift = n / 2 + 1;
        double[] out = nan(s.length);
        for (int i = shift; i < s.length; i++)
            if (!Double.isNaN(smaS[i - shift])) out[i] = s[i] - smaS[i - shift];
        return out;
    }

    /** Ultimate Oscillator (Williams): weighted blend of buying pressure over 3 horizons. */
    public static double[] ultimateOscillator(BarSeries b, int n1, int n2, int n3) {
        int len = b.size();
        double[] bp = new double[len], tr = new double[len];
        for (int i = 1; i < len; i++) {
            double prevClose = b.close[i - 1];
            bp[i] = b.close[i] - Math.min(b.low[i], prevClose);
            tr[i] = Math.max(b.high[i], prevClose) - Math.min(b.low[i], prevClose);
        }
        double[] out = nan(len);
        int maxN = Math.max(n1, Math.max(n2, n3));
        for (int i = maxN; i < len; i++) {
            double a1 = uoAvg(bp, tr, i, n1), a2 = uoAvg(bp, tr, i, n2), a3 = uoAvg(bp, tr, i, n3);
            out[i] = 100 * (4 * a1 + 2 * a2 + a3) / 7.0;
        }
        return out;
    }

    private static double uoAvg(double[] bp, double[] tr, int i, int n) {
        double sbp = 0, str = 0;
        for (int j = i - n + 1; j <= i; j++) { sbp += bp[j]; str += tr[j]; }
        return str == 0 ? 0 : sbp / str;
    }

    /** Elder's Force Index: (close change) * volume, EMA-smoothed. */
    public static double[] forceIndex(BarSeries b, int n) {
        int len = b.size();
        double[] raw = new double[len];
        for (int i = 1; i < len; i++) raw[i] = (b.close[i] - b.close[i - 1]) * b.volume[i];
        return ema(raw, n);
    }

    /** Chaikin Money Flow: sum(money-flow-volume, n) / sum(volume, n). */
    public static double[] cmf(BarSeries b, int n) {
        int len = b.size();
        double[] mfv = new double[len];
        for (int i = 0; i < len; i++) {
            double range = b.high[i] - b.low[i];
            double mult = range == 0 ? 0 : ((b.close[i] - b.low[i]) - (b.high[i] - b.close[i])) / range;
            mfv[i] = mult * b.volume[i];
        }
        double[] out = nan(len);
        for (int i = n - 1; i < len; i++) {
            double sm = 0, sv = 0;
            for (int j = i - n + 1; j <= i; j++) { sm += mfv[j]; sv += b.volume[j]; }
            out[i] = sv == 0 ? 0 : sm / sv;
        }
        return out;
    }

    /** Ease of Movement: price advance per unit of range/volume, SMA-smoothed. */
    public static double[] emv(BarSeries b, int n) {
        int len = b.size();
        double[] raw = new double[len];
        for (int i = 1; i < len; i++) {
            double dist = (b.high[i] + b.low[i]) / 2.0 - (b.high[i - 1] + b.low[i - 1]) / 2.0;
            double range = b.high[i] - b.low[i];
            raw[i] = (b.volume[i] == 0 || range == 0) ? 0 : dist * range / b.volume[i];
        }
        return sma(raw, n);
    }

    /** Accumulation/Distribution line: cumulative close-location-weighted volume. */
    public static double[] adLine(BarSeries b) {
        int len = b.size();
        double[] out = new double[len];
        for (int i = 0; i < len; i++) {
            double range = b.high[i] - b.low[i];
            double mult = range == 0 ? 0 : ((b.close[i] - b.low[i]) - (b.high[i] - b.close[i])) / range;
            out[i] = (i == 0 ? 0 : out[i - 1]) + mult * b.volume[i];
        }
        return out;
    }

    /** Volume Price Trend: cumulative volume weighted by percentage price change. */
    public static double[] vpt(BarSeries b) {
        int len = b.size();
        double[] out = new double[len];
        for (int i = 1; i < len; i++) {
            double pct = b.close[i - 1] == 0 ? 0 : (b.close[i] - b.close[i - 1]) / b.close[i - 1];
            out[i] = out[i - 1] + pct * b.volume[i];
        }
        return out;
    }
}
