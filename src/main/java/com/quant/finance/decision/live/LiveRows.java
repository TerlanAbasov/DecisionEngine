package com.quant.finance.decision.live;

import com.quant.finance.decision.entity.LiveEquityEntity;
import com.quant.finance.decision.entity.LiveSlotEntity;
import com.quant.finance.decision.entity.LiveStrategyPnlEntity;
import com.quant.finance.decision.entity.LiveTradeEntity;
import com.quant.finance.decision.live.AlpacaModels.AccountInfo;
import com.quant.finance.decision.live.SlotLedger.ClosedTrade;
import com.quant.finance.decision.live.SlotLedger.State;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Converts between the stored rows and the ledger's values, and builds the rows a cycle writes. */
final class LiveRows {
    private LiveRows() {}

    // ---- slots ---------------------------------------------------------------------------------

    static boolean isOpen(LiveSlotEntity s) { return s.getDirection() != 0; }

    /** An open position whose entry and last price are known, so its profit can be worked out. */
    static boolean isPriced(LiveSlotEntity s) { return isOpen(s) && s.getLastPrice() != null && s.getEntryPrice() != null; }

    /** Signed virtual shares: negative when short. */
    static double signedQty(LiveSlotEntity s) { return s.getDirection() * s.getQty(); }

    /** Open profit or loss at the last known price; 0 when flat or not yet priced. */
    static double unrealized(LiveSlotEntity s) {
        return isPriced(s) ? signedQty(s) * (s.getLastPrice() - s.getEntryPrice()) : 0;
    }

    static State stateOf(LiveSlotEntity s) {
        return new State(s.getDirection(), s.getQty(), s.getEntryPrice() == null ? 0 : s.getEntryPrice(),
                s.getEntryTime(), s.getBlockedDir(), s.getRealizedPnl());
    }

    static LiveSlotEntity newSlot(String strategy, String symbol) {
        LiveSlotEntity s = new LiveSlotEntity();
        s.setStrategy(strategy);
        s.setSymbol(symbol);
        return s;
    }

    /** Stores the ledger's new state and the signal that led to it. */
    static void update(LiveSlotEntity slot, State state, double signal, double price, Instant now) {
        boolean flat = state.direction() == 0;
        slot.setDirection(state.direction());
        slot.setQty(state.qty());
        slot.setEntryPrice(flat ? null : state.entryPrice());
        slot.setEntryTime(flat ? null : state.entryTime());
        slot.setBlockedDir(state.blockedDir());
        slot.setRealizedPnl(state.realizedPnl());
        slot.setLastSignal(signal);
        slot.setLastSignalAt(now);
        markToMarket(slot, price, now);
    }

    static void markToMarket(LiveSlotEntity slot, double price, Instant now) {
        slot.setLastPrice(price);
        slot.setUpdatedAt(now);
    }

    // ---- what a cycle writes -------------------------------------------------------------------

    static LiveTradeEntity trade(String strategy, String symbol, ClosedTrade c, Long cycleId) {
        LiveTradeEntity t = new LiveTradeEntity();
        t.setStrategy(strategy);
        t.setSymbol(symbol);
        t.setSide(c.side());
        t.setQty(c.qty());
        t.setEntryTime(c.entryTime());
        t.setEntryPrice(c.entryPrice());
        t.setExitTime(c.exitTime());
        t.setExitPrice(c.exitPrice());
        t.setPnlUsd(c.pnlUsd());
        t.setReturnPct(c.returnPct());
        t.setExitReason(c.reason());
        t.setCycleId(cycleId);
        return t;
    }

    static LiveEquityEntity equity(AccountInfo a, Long cycleId, Instant now) {
        LiveEquityEntity e = new LiveEquityEntity();
        e.setTs(now);
        e.setEquity(a.equity());
        e.setCash(a.cash());
        e.setBuyingPower(a.buyingPower());
        e.setLongValue(a.longMarketValue());
        e.setShortValue(a.shortMarketValue());
        e.setCycleId(cycleId);
        return e;
    }

    /** One point per strategy: what its slots have realized and hold unrealized right now. */
    static List<LiveStrategyPnlEntity> strategyPnl(Collection<LiveSlotEntity> slots, Long cycleId, Instant now) {
        Map<String, List<LiveSlotEntity>> byStrategy = slots.stream()
                .collect(Collectors.groupingBy(LiveSlotEntity::getStrategy, TreeMap::new, Collectors.toList()));
        List<LiveStrategyPnlEntity> rows = new ArrayList<>();
        byStrategy.forEach((strategy, own) -> {
            LiveStrategyPnlEntity r = new LiveStrategyPnlEntity();
            r.setCycleId(cycleId);
            r.setTs(now);
            r.setStrategy(strategy);
            r.setRealized(own.stream().mapToDouble(LiveSlotEntity::getRealizedPnl).sum());
            r.setUnrealized(own.stream().mapToDouble(LiveRows::unrealized).sum());
            r.setOpenSlots((int) own.stream().filter(LiveRows::isPriced).count());
            rows.add(r);
        });
        return rows;
    }
}
