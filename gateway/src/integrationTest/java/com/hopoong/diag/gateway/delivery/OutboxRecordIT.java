package com.hopoong.diag.gateway.delivery;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import static org.assertj.core.api.Assertions.*;
import com.hopoong.diag.gateway.delivery.application.AttemptOutcome;
import com.hopoong.diag.gateway.delivery.application.RecordResult;
import com.hopoong.diag.gateway.delivery.persistence.ClaimedEvent;

@ExtendWith(OutputCaptureExtension.class)
class OutboxRecordIT extends DeliveryDatabaseITSupport {
    @Test void successFinishesAttemptAndClearsClaimAtomically() {
        var event = claimOne();
        assertThat(recorder.record(event, outcome(AttemptOutcome.SUCCESS)).state()).isEqualTo(RecordResult.State.SENT);
        assertThat(text("status")).isEqualTo("SENT");
        assertThat(text("claim_token::text")).isNull();
        assertThat(text("locked_by")).isNull();
        assertThat(text("locked_until::text")).isNull();
        assertThat(text("sent_at::text")).isNotNull();
        assertThat(text("last_error")).isNull();
        assertThat(jdbc.queryForObject("SELECT last_http_status FROM outbox_event", Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_attempt WHERE outcome='SUCCESS' AND finished_at IS NOT NULL AND http_status=200 AND latency_ms=12", Integer.class)).isEqualTo(1);
    }
    @Test void retryableAndUncertainScheduleUsingDatabaseClock() {
        for (var value : new AttemptOutcome[]{AttemptOutcome.RETRYABLE, AttemptOutcome.UNCERTAIN}) {
            fixture.reset(); var event = claimOne();
            var result = recorder.record(event, outcome(value));
            assertThat(result.state()).isEqualTo(RecordResult.State.RETRY);
            assertThat(result.nextDelay().toMillis()).isBetween(2500L, 5000L);
            assertThat(jdbc.queryForObject("SELECT extract(epoch FROM (next_attempt_at-now())) FROM outbox_event", Double.class)).isBetween(1.5, 5.1);
            assertThat(text("status")).isEqualTo("PENDING");
            assertThat(text("claim_token::text")).isNull();
            assertThat(text("last_outcome")).isEqualTo(value.name());
            assertThat(outcomes()).containsExactly(value.name());
            assertThat(jdbc.queryForObject("SELECT error_code FROM delivery_attempt", String.class)).isEqualTo(outcome(value).errorCode());
        }
    }
    @Test void permanentFailureTakesPrecedenceOverMaxAttempts() {
        fixture.createPending(1); jdbc.update("UPDATE outbox_event SET attempt_count=2");
        recorder.record(claimer.claim(1).get(0), outcome(AttemptOutcome.NON_RETRYABLE));
        assertThat(text("dead_reason")).isEqualTo("NON_RETRYABLE");
        assertThat(text("status")).isEqualTo("DEAD");
        assertThat(outcomes()).containsExactly("NON_RETRYABLE");
    }
    @Test void retryableAndUncertainBecomeDeadAtLimitWithOriginalOutcome() {
        for (var value : new AttemptOutcome[]{AttemptOutcome.RETRYABLE, AttemptOutcome.UNCERTAIN}) {
            fixture.reset(); fixture.createPending(1); jdbc.update("UPDATE outbox_event SET attempt_count=2");
            recorder.record(claimer.claim(1).get(0), outcome(value));
            assertThat(text("status")).isEqualTo("DEAD");
            assertThat(text("dead_reason")).isEqualTo("MAX_ATTEMPTS");
            assertThat(text("last_outcome")).isEqualTo(value.name());
            assertThat(outcomes()).containsExactly(value.name());
        }
    }
    @Test void unknownTokenIsStaleAndLeaseExpiryAloneDoesNotInvalidateOwner() {
        var e = claimOne();
        var wrong = new ClaimedEvent(e.id(), e.eventId(), e.payload(), e.attemptCount(), e.cycleNo(), UUID.randomUUID().toString());
        assertThat(recorder.record(wrong, outcome(AttemptOutcome.SUCCESS)).state()).isEqualTo(RecordResult.State.STALE);
        assertThat(text("claim_token::text")).isEqualTo(e.claimToken());
        assertThat(outcomes()).containsExactly("STARTED");
        expire();
        assertThat(recorder.record(e, outcome(AttemptOutcome.SUCCESS)).state()).isEqualTo(RecordResult.State.SENT);
    }
    @Test void staleOwnerOnlyRecordsFirstLateOutcomeAndCurrentOwnerCanFinish(CapturedOutput output) {
        var t1 = claimOne(); expire(); var t2 = claimer.claim(1).get(0);
        assertThat(recorder.record(t1, outcome(AttemptOutcome.SUCCESS)).state()).isEqualTo(RecordResult.State.STALE);
        assertThat(text("status")).isEqualTo("PENDING");
        assertThat(text("claim_token::text")).isEqualTo(t2.claimToken());
        assertThat(outcomes()).containsExactly("ABANDONED", "STARTED");
        assertThat(jdbc.queryForObject("SELECT late_outcome FROM delivery_attempt WHERE claim_token=?::uuid", String.class, t1.claimToken())).isEqualTo("SUCCESS");
        assertThat(recorder.record(t1, outcome(AttemptOutcome.UNCERTAIN)).state()).isEqualTo(RecordResult.State.STALE);
        assertThat(jdbc.queryForObject("SELECT late_outcome FROM delivery_attempt WHERE claim_token=?::uuid", String.class, t1.claimToken())).isEqualTo("SUCCESS");
        assertThat(output.getOut()).contains("STALE_CLAIM outcome=SUCCESS lateRecorded=true", "STALE_CLAIM outcome=UNCERTAIN lateRecorded=false");
        assertThat(recorder.record(t2, outcome(AttemptOutcome.SUCCESS)).state()).isEqualTo(RecordResult.State.SENT);
        assertThat(outcomes()).containsExactly("ABANDONED", "SUCCESS");
    }
    @Test void lateRetryableResultCannotChangeAlreadySentEvent() {
        var t1 = claimOne(); expire(); var t2 = claimer.claim(1).get(0);
        recorder.record(t2, outcome(AttemptOutcome.SUCCESS));
        assertThat(recorder.record(t1, outcome(AttemptOutcome.RETRYABLE)).state()).isEqualTo(RecordResult.State.STALE);
        assertThat(text("status")).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT late_outcome FROM delivery_attempt WHERE claim_token=?::uuid", String.class, t1.claimToken())).isEqualTo("RETRYABLE");
    }
}
