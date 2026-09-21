package com.quant.finance.decision.live;

import com.quant.finance.decision.entity.LiveCycleEntity;
import com.quant.finance.decision.entity.LiveEquityEntity;
import com.quant.finance.decision.entity.LiveOrderEntity;
import com.quant.finance.decision.entity.LiveSlotEntity;
import com.quant.finance.decision.entity.LiveStrategyPnlEntity;
import com.quant.finance.decision.entity.LiveTradeEntity;
import com.quant.finance.decision.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The job's state in the database. */
@Component
@RequiredArgsConstructor
public class JpaLiveStore implements LiveStore {

    private final LiveCycleRepository cycles;
    private final LiveSlotRepository slotRepo;
    private final LiveTradeRepository trades;
    private final LiveOrderRepository orders;
    private final LiveEquityRepository equity;
    private final LiveStrategyPnlRepository pnl;

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
