package com.dotran.oms.order.application.repository;

import com.dotran.oms.order.application.event.OutboxMessage;

import java.util.List;

/** Must run inside the caller's transaction: the rows commit with the business change (system rule 2). */
public interface OutboxEventRepository {

    void saveAll(List<OutboxMessage> messages);
}
