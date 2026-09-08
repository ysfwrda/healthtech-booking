package com.healthtech.appointment.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    // Native query: JPQL has no equivalent of SKIP LOCKED. Concurrent relays each claim a
    // disjoint batch instead of blocking on each other's row locks (see ADR-008).
    @Query(value = """
            SELECT * FROM outbox
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> claimBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("DELETE FROM OutboxMessage o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :cutoff")
    int deleteByPublishedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
