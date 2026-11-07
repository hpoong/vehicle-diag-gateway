package com.hopoong.diag.gateway.delivery;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OutboxClaimIT extends DeliveryDatabaseITSupport {
    @Test void claimsOrderedBatchWithDistinctTokensAndStartedAttempts() {
        var ids = fixture.createPending(6);
        var events = claimer.claim(4);
        assertThat(events).extracting(ClaimedEvent::id).containsExactlyElementsOf(ids.subList(0,4));
        assertThat(events).extracting(ClaimedEvent::claimToken).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(events).allSatisfy(e -> { assertThat(e.attemptCount()).isEqualTo(1); assertThat(e.cycleNo()).isEqualTo(1); });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE claim_token IS NULL AND attempt_count=0", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE locked_by IS NOT NULL AND locked_until BETWEEN now()+interval '25 seconds' AND now()+interval '31 seconds'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_attempt a JOIN outbox_event o ON a.outbox_id=o.id WHERE a.claim_token=o.claim_token AND a.attempt_no=o.attempt_count AND a.cycle_no=o.cycle_no AND a.finished_at IS NULL AND a.worker_id LIKE ?", Integer.class,
                "%:" + Thread.currentThread().getName())).isEqualTo(4);
        assertThat(outcomes()).containsOnly("STARTED");
        assertThat(claimer.claim(0)).isEmpty();
    }
    @Test void skipsFutureSchedulesValidLeasesAndTerminalRows() {
        fixture.createPending(4);
        jdbc.update("UPDATE outbox_event SET next_attempt_at=now()+interval '1 hour' WHERE id=1");
        jdbc.update("UPDATE outbox_event SET claim_token=gen_random_uuid(), locked_by='other', locked_until=now()+interval '1 hour' WHERE id=2");
        jdbc.update("UPDATE outbox_event SET status='SENT', sent_at=now() WHERE id=3");
        jdbc.update("UPDATE outbox_event SET status='DEAD', dead_reason='NON_RETRYABLE', dead_at=now() WHERE id=4");
        assertThat(claimer.claim(10)).isEmpty();
        assertThat(jdbc.queryForList("SELECT attempt_count FROM outbox_event", Integer.class)).containsOnly(0);
    }
    @Test void twoClaimersNeverClaimTheSameRow() throws Exception {
        fixture.createPending(100);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<List<Long>> work = () -> {
            start.await(); var ids = new ArrayList<Long>();
            for (;;) { var batch = claimer.claim(4); if (batch.isEmpty()) return ids; batch.forEach(e -> ids.add(e.id())); }
        };
        try {
            var a = pool.submit(work); var b = pool.submit(work); start.countDown();
            var ids = new ArrayList<>(a.get(30, TimeUnit.SECONDS)); ids.addAll(b.get(30, TimeUnit.SECONDS));
            assertThat(ids).hasSize(100).doesNotHaveDuplicates();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE attempt_count<>1", Integer.class)).isZero();
            assertThat(outcomes()).hasSize(100).containsOnly("STARTED");
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void reclaimsExpiredAttemptsBeforeInsertingNewStartedRowsInMixedBatch() {
        fixture.createPending(2); var old = claimer.claim(1).get(0); expire();
        var batch = claimer.claim(2);
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).id()).isEqualTo(old.id());
        assertThat(batch.get(0).claimToken()).isNotEqualTo(old.claimToken());
        assertThat(batch.get(0).attemptCount()).isEqualTo(2);
        assertThat(outcomes()).containsExactly("ABANDONED", "STARTED", "STARTED");
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM delivery_attempt WHERE claim_token=?::uuid", Boolean.class, old.claimToken())).isTrue();
    }
    @Test void skipsOverLimitAndAbandonsPriorAttemptFirst() {
        fixture.createPending(1); jdbc.update("UPDATE outbox_event SET attempt_count=2");
        var old = claimer.claim(1).get(0); expire();
        assertThat(claimer.claim(4)).isEmpty();
        assertThat(text("status")).isEqualTo("DEAD");
        assertThat(text("dead_reason")).isEqualTo("MAX_ATTEMPTS");
        assertThat(text("last_outcome")).isEqualTo("SKIPPED_MAX_ATTEMPTS");
        assertThat(text("claim_token::text")).isNull();
        assertThat(outcomes()).containsExactly("ABANDONED", "SKIPPED_MAX_ATTEMPTS");
        assertThat(jdbc.queryForObject("SELECT attempt_no FROM delivery_attempt WHERE outcome='SKIPPED_MAX_ATTEMPTS'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT started_at=finished_at FROM delivery_attempt WHERE outcome='SKIPPED_MAX_ATTEMPTS'", Boolean.class)).isTrue();
    }
    @Test void skipsOverLimitUnclaimedRowWithoutSending() {
        fixture.createPending(1); jdbc.update("UPDATE outbox_event SET attempt_count=3");
        assertThat(claimer.claim(1)).isEmpty();
        assertThat(outcomes()).containsExactly("SKIPPED_MAX_ATTEMPTS");
        assertThat(text("status")).isEqualTo("DEAD");
    }
}
