package com.quant.finance.decision.autotrade;

import static org.junit.jupiter.api.Assertions.*;

import com.quant.finance.decision.autotrade.BarWindow.Bar;
import com.quant.finance.decision.strategy.BarSeries;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class BarWindowTest {
    private static final Instant NOW = Instant.parse("2026-09-21T15:00:00Z");

    /** A fake Alpaca holding minute bars at every whole minute from t0; records what it was asked for. */
    private static final class Source implements BarWindow.Fetcher {
        final List<Instant[]> asked = new ArrayList<>();
        Instant t0 = NOW.minus(Duration.ofDays(30));
        Instant availableUntil = NOW;
        double closeOfNewest = 1;

        @Override
        public Map<Instant, Bar> fetch(Instant from, Instant to) {
            asked.add(new Instant[] {from, to});
            Map<Instant, Bar> out = new TreeMap<>();
            Instant start = from.isBefore(t0) ? t0 : from;
            Instant end = to.isBefore(availableUntil) ? to : availableUntil;
            for (Instant t = start.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
                    !t.isAfter(end);
                    t = t.plusSeconds(60)) {
                if (!t.isBefore(from)) {
                    out.put(t, new Bar(1, 1, 1, t.equals(end) ? closeOfNewest : 1, 1));
                }
            }
            return out;
        }
    }

    @Test
    void theFirstUpdateFetchesTheWholeLookbackInOneCall() {
        Source source = new Source();
        BarSeries series = new BarWindow().update("X", Duration.ofHours(2), NOW, source);
        assertEquals(1, source.asked.size());
        assertEquals(NOW.minus(Duration.ofHours(2)), source.asked.get(0)[0]);
        assertEquals(121, series.size());
    }

    @Test
    void laterUpdatesFetchOnlyWhatIsNewAndReplaceTheBarThatWasStillForming() {
        Source source = new Source();
        BarWindow window = new BarWindow();
        window.update("X", Duration.ofHours(2), NOW, source);
        source.asked.clear();
        source.availableUntil = NOW.plusSeconds(300);
        source.closeOfNewest = 7;
        BarSeries series = window.update("X", Duration.ofHours(2), NOW.plusSeconds(300), source);
        assertEquals(1, source.asked.size());
        assertEquals(NOW, source.asked.get(0)[0], "from the newest cached bar, not from the start of the window");
        assertEquals(7, series.close[series.size() - 1], 1e-9);
        assertEquals(1, series.close[series.size() - 6], 1e-9, "older bars stay as they were");
        assertEquals(121, series.size(), "the window slid: five minutes in, five minutes out");
    }

    @Test
    void theWindowKeepsNoMoreThanTheLookback() {
        Source source = new Source();
        BarWindow window = new BarWindow();
        window.update("X", Duration.ofHours(1), NOW, source);
        source.availableUntil = NOW.plus(Duration.ofHours(3));
        BarSeries series = window.update("X", Duration.ofHours(1), NOW.plus(Duration.ofHours(3)), source);
        assertEquals(61, series.size());
        assertEquals(NOW.plus(Duration.ofHours(2)), series.date[0]);
    }

    @Test
    void aLongerLookbackBackfillsOnlyTheMissingOlderPart() {
        Source source = new Source();
        BarWindow window = new BarWindow();
        window.update("X", Duration.ofHours(1), NOW, source);
        source.asked.clear();
        BarSeries series = window.update("X", Duration.ofHours(3), NOW, source);
        assertEquals(2, source.asked.size());
        assertEquals(NOW.minus(Duration.ofHours(3)), source.asked.get(0)[0]);
        assertEquals(NOW.minus(Duration.ofHours(1)), source.asked.get(0)[1], "older part only");
        assertEquals(181, series.size());
    }

    @Test
    void aSymbolThatHasNoDataIsAskedAgainForTheWholeWindowAndYieldsAnEmptySeries() {
        Source source = new Source();
        source.t0 = NOW.plus(Duration.ofDays(1)); // nothing exists yet
        BarWindow window = new BarWindow();
        assertEquals(0, window.update("X", Duration.ofHours(2), NOW, source).size());
        source.asked.clear();
        assertEquals(
                0,
                window.update("X", Duration.ofHours(2), NOW.plusSeconds(60), source)
                        .size());
        assertEquals(
                NOW.plusSeconds(60).minus(Duration.ofHours(2)), source.asked.get(0)[0]);
    }
}
