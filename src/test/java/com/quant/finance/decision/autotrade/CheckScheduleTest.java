package com.quant.finance.decision.autotrade;

import com.quant.finance.decision.engine.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CheckScheduleTest {

    private static final Instant BAR = Instant.parse("2026-09-21T14:45:00Z");        // last completed M15 bar, closed at 15:00

    @Test
    void nothingIsCheckedAgainUntilTheNextBarShouldBeComplete() {
        CheckSchedule schedule = new CheckSchedule();
        Instant now = Instant.parse("2026-09-21T15:00:05Z");
        assertTrue(schedule.isDue("AAPL", Timeframe.M15, now), "never looked at: due at once");

        schedule.checked("AAPL", Timeframe.M15, BAR, now);
        Instant due = Instant.parse("2026-09-21T15:15:20Z");                          // the 15:00 bar closes at 15:15, plus the data lag
        assertFalse(schedule.isDue("AAPL", Timeframe.M15, Instant.parse("2026-09-21T15:10:00Z")));
        assertFalse(schedule.isDue("AAPL", Timeframe.M15, due.minusSeconds(1)));
        assertTrue(schedule.isDue("AAPL", Timeframe.M15, due));
    }

    @Test
    void eachSymbolAndTimeframeHasItsOwnSchedule() {
        CheckSchedule schedule = new CheckSchedule();
        Instant now = Instant.parse("2026-09-21T15:00:05Z");
        schedule.checked("AAPL", Timeframe.M15, BAR, now);
        assertTrue(schedule.isDue("MSFT", Timeframe.M15, now));
        assertTrue(schedule.isDue("AAPL", Timeframe.H1, now));
    }

    @Test
    void aDailyStrategyIsNotLookedAtAgainForTwoDays() {
        Instant dayBar = Instant.parse("2026-09-21T04:00:00Z");
        assertEquals(Instant.parse("2026-09-23T04:00:20Z"), CheckSchedule.next(Instant.parse("2026-09-22T05:00:00Z"), dayBar, Timeframe.D1));
    }

    @Test
    void justAfterTheDueMomentItLooksAgainQuicklyBecauseDataMayBeLate() {
        Instant due = Instant.parse("2026-09-21T15:15:20Z");
        Instant now = due.plusSeconds(60);
        assertEquals(now.plus(CheckSchedule.LATE_RETRY), CheckSchedule.next(now, BAR, Timeframe.M15));
    }

    @Test
    void whenClearlyOverdueTheMarketIsClosedSoItOnlyLooksNowAndThen() {
        Instant now = Instant.parse("2026-09-26T09:00:00Z");                          // days after the last bar (weekend)
        assertEquals(now.plus(Duration.ofMinutes(15)), CheckSchedule.next(now, BAR, Timeframe.M15));
        assertEquals(now.plus(Duration.ofMinutes(15)), CheckSchedule.next(now, BAR, Timeframe.H1), "capped at 15 minutes for long frames too");
        assertEquals(now.plus(Duration.ofMinutes(5)), CheckSchedule.next(now, BAR, Timeframe.M5), "and never longer than the bar itself");
    }

    @Test
    void withoutAUsableBarItTriesAgainAfterOneBarOrTheCap() {
        Instant now = Instant.parse("2026-09-21T15:00:00Z");
        assertEquals(now.plus(Duration.ofMinutes(15)), CheckSchedule.next(now, null, Timeframe.M15));
        assertEquals(now.plus(Duration.ofMinutes(15)), CheckSchedule.next(now, null, Timeframe.D1));
    }
}
