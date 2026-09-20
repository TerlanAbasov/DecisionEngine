package com.quant.finance.decision.live;

import com.quant.finance.decision.live.BarWindow.Bar;
import com.quant.finance.decision.strategy.BarSeries;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BarWindowTest {
    private static final Instant NOW = Instant.parse("2026-09-21T15:00:00Z");

    /** A fake Alpaca holding minute bars at every whole minute from t0; records what it was asked for. */
    private static final class Source implements BarWindow.Fetcher {
        final List<Instant[]> asked = new ArrayList<>();
        Instant t0 = NOW.minus(Duration.ofDays(30));
        Instant availableUntil = NOW;
        double closeOfNewest = 1;

        @Override public Map<Instant, Bar> fetch(Instant from, Instant to) {
            asked.add(new Instant[] {from, to});
            Map<Instant, Bar> out = new TreeMap<>();
            Instant start = from.isBefore(t0) ? t0 : from;
            Instant end = to.isBefore(availableUntil) ? to : availableUntil;
            for (Instant t = start.truncatedTo(java.time.temporal.ChronoUnit.MINUTES); !t.isAfter(end); t = t.plusSeconds(60))
                if (!t.isBefore(from)) out.put(t, new Bar(1, 1, 1, t.equals(end) ? closeOfNewest : 1, 1));
            return out;
        }
    }

    @Test
    void theFirstUpdateFetchesTheWholeLookbackInOneCall() {
        Source s = new Source();
        BarSeries b = new BarWindow().update("X", Duration.ofHours(2), NOW, s);
        assertEquals(1, s.asked.size());
        assertEquals(NOW.minus(Duration.ofHours(2)), s.asked.get(0)[0]);
        assertEquals(121, b.size());
    }

    @Test
    void laterUpdatesFetchOnlyWhatIsNewAndReplaceTheBarThatWasStillForming() {
        Source s = new Source();
        BarWindow w = new BarWindow();
        w.update("X", Duration.ofHours(2), NOW, s);
        s.asked.clear();
        s.availableUntil = NOW.plusSeconds(300);
        s.closeOfNewest = 7;
        BarSeries b = w.update("X", Duration.ofHours(2), NOW.plusSeconds(300), s);
        assertEquals(1, s.asked.size());
        assertEquals(NOW, s.asked.get(0)[0], "from the newest cached bar, not from the start of the window");
        assertEquals(7, b.close[b.size() - 1], 1e-9);
        assertEquals(1, b.close[b.size() - 6], 1e-9, "older bars stay as they were");
        assertEquals(121, b.size(), "the window slid: five minutes in, five minutes out");
    }

    @Test
    void theWindowKeepsNoMoreThanTheLookback() {
        Source s = new Source();
        BarWindow w = new BarWindow();
        w.update("X", Duration.ofHours(1), NOW, s);
        s.availableUntil = NOW.plus(Duration.ofHours(3));
        BarSeries b = w.update("X", Duration.ofHours(1), NOW.plus(Duration.ofHours(3)), s);
        assertEquals(61, b.size());
        assertEquals(NOW.plus(Duration.ofHours(2)), b.date[0]);
    }

    @Test
    void aLongerLookbackBackfillsOnlyTheMissingOlderPart() {
        Source s = new Source();
        BarWindow w = new BarWindow();
        w.update("X", Duration.ofHours(1), NOW, s);
        s.asked.clear();
        BarSeries b = w.update("X", Duration.ofHours(3), NOW, s);
        assertEquals(2, s.asked.size());
        assertEquals(NOW.minus(Duration.ofHours(3)), s.asked.get(0)[0]);
        assertEquals(NOW.minus(Duration.ofHours(1)), s.asked.get(0)[1], "older part only");
        assertEquals(181, b.size());
    }

    @Test
    void aSymbolThatHasNoDataIsAskedAgainForTheWholeWindowAndYieldsAnEmptySeries() {
        Source s = new Source();
        s.t0 = NOW.plus(Duration.ofDays(1));                                  // nothing exists yet
        BarWindow w = new BarWindow();
        assertEquals(0, w.update("X", Duration.ofHours(2), NOW, s).size());
        s.asked.clear();
        assertEquals(0, w.update("X", Duration.ofHours(2), NOW.plusSeconds(60), s).size());
        assertEquals(NOW.plusSeconds(60).minus(Duration.ofHours(2)), s.asked.get(0)[0]);
    }
}
