package com.quant.finance.decision.live;

import com.quant.finance.decision.domain.*;
import com.quant.finance.decision.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JpaLiveStore implements LiveStore {

    private final LiveCycleRepository cycles;
    private final LiveSlotRepository slotRepo;
    private final LiveTradeRepository trades;
    private final LiveOrderRepository orders;
    private final LiveEquityRepository equity;
    private final LiveStrategyPnlRepository pnl;

    public JpaLiveStore(LiveCycleRepository cycles, LiveSlotRepository slotRepo, LiveTradeRepository trades,
                        LiveOrderRepository orders, LiveEquityRepository equity, LiveStrategyPnlRepository pnl) {
        this.cycles = cycles;
        this.slotRepo = slotRepo;
        this.trades = trades;
        this.orders = orders;
        this.equity = equity;
        this.pnl = pnl;
    }

    @Override
    public LiveCycleEntity saveCycle(LiveCycleEntity cycle) { return cycles.save(cycle); }

    @Override
    public Map<String, LiveSlotEntity> slots() {
        Map<String, LiveSlotEntity> out = new HashMap<>();
        for (LiveSlotEntity s : slotRepo.findAll()) out.put(LiveStore.key(s.getStrategy(), s.getSymbol()), s);
        return out;
    }

    @Override
    @Transactional
    public void commit(List<LiveSlotEntity> slots, List<LiveTradeEntity> closed) {
        slotRepo.saveAll(slots);
        trades.saveAll(closed);
    }

    @Override
    public LiveOrderEntity saveOrder(LiveOrderEntity order) { return orders.save(order); }

    @Override
    public void saveEquity(LiveEquityEntity e) { equity.save(e); }

    @Override
    public void saveStrategyPnl(List<LiveStrategyPnlEntity> rows) { pnl.saveAll(rows); }

    @Override
    @Transactional
    public void pruneCurves(Instant before) {
        equity.deleteByTsBefore(before);
        pnl.deleteByTsBefore(before);
    }
}
