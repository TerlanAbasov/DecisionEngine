package com.quant.finance.decision.live;

import com.quant.finance.decision.live.AlpacaModels.*;

import java.util.List;
import java.util.Optional;

/** What the paper-trading job needs from the broker. Implemented over Alpaca's paper API; faked in tests. */
public interface TradingGateway {
    /** Whether this gateway trades only against a paper (demo) endpoint. The job refuses to run otherwise. */
    boolean isPaper();

    AccountInfo account();

    MarketClock clock();

    List<PositionInfo> positions();

    AssetInfo asset(String symbol);

    /** Market order, day time-in-force; {@code side} is "buy" or "sell". */
    OrderInfo submitMarketOrder(String symbol, long qty, String side, String clientOrderId);

    Optional<OrderInfo> findByClientOrderId(String clientOrderId);

    OrderInfo order(String orderId);

    void cancelOrder(String orderId);

    List<OrderInfo> openOrders();
}
