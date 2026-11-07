package com.hopoong.diag.gateway.delivery;

import com.hopoong.diag.testsupport.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hopoong.diag.gateway.delivery.application.DeliveryRecorder;
import com.hopoong.diag.gateway.delivery.application.DeliveryService;
import com.hopoong.diag.gateway.delivery.application.RunSummary;
import com.hopoong.diag.gateway.delivery.client.ReceiverClient;

@SpringBootTest(properties = {"gateway.delivery.backoff.base=0s", "gateway.delivery.read-timeout=500ms",
        "gateway.delivery.connect-timeout=500ms", "gateway.delivery.max-attempts=3"})
@ExtendWith(OutputCaptureExtension.class)
class DeliveryFlowIT extends DeliveryDatabaseITSupport {
    static final StubReceiver STUB = new StubReceiver();
    @DynamicPropertySource static void receiver(DynamicPropertyRegistry registry) { registry.add("gateway.delivery.receiver-url", STUB::url); }
    @AfterAll static void closeStub() { STUB.close(); }
    @Autowired DeliveryService service;
    @Autowired ReceiverClient client;
    @Autowired ThreadPoolTaskExecutor deliveryExecutor;
    @MockitoSpyBean DeliveryRecorder recorderSpy;
    @BeforeEach void resetStub() { STUB.reset(); }
    @Test void sendsWholeBatchAndLeavesExecutorMdcClean() throws Exception {
        fixture.createPending(4);
        var summary = service.runOnce();
        assertThat(summary).isEqualTo(new RunSummary(4,4,0,0,0,0));
        assertThat(STUB.requests()).hasSize(4);
        assertThat(service.runOnce().claimed()).isZero();
        assertThat(properties.enabled()).isFalse();
        for (int i=0; i<4; i++) assertThat(deliveryExecutor.submit(() ->
                MDC.get("claimToken") == null && MDC.get("eventId") == null && MDC.get("outboxId") == null && MDC.get("attemptNo") == null)
                .get(5, TimeUnit.SECONDS)).isTrue();
    }
    @Test void retries503ThenSucceedsWithCorrelatedLogs(CapturedOutput output) {
        fixture.createPending(1); STUB.respond(503);
        assertThat(service.runOnce().retry()).isEqualTo(1);
        STUB.respond(200); assertThat(service.runOnce().sent()).isEqualTo(1);
        assertThat(outcomes()).containsExactly("RETRYABLE", "SUCCESS");
        assertThat(output.getOut()).contains("DELIVERY_RESULT outcome=RETRYABLE http=503", "next=PENDING+0ms",
                "DELIVERY_RESULT outcome=SUCCESS http=200", "next=SENT", "OUTBOX_SENT attempts=2");
        assertThat(output.getOut()).doesNotContain(OutboxFixture.VIN);
        String firstToken = STUB.requests().get(0).requestId();
        assertThat(output.getOut().lines().filter(line -> line.contains("DELIVERY_RESULT outcome=RETRYABLE")).toList())
                .singleElement().asString().contains("claimToken:" + firstToken, "outboxId:1", "attemptNo:1");
    }
    @Test void responseLossIsUncertainAndRetriesIdenticalEventWithNewRequestId() {
        fixture.createPending(1); STUB.hold(Duration.ofSeconds(2));
        assertThat(service.runOnce().retry()).isEqualTo(1);
        assertThat(outcomes()).containsExactly("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT error_code FROM delivery_attempt", String.class)).isEqualTo("READ_TIMEOUT");
        STUB.hold(Duration.ZERO); assertThat(service.runOnce().sent()).isEqualTo(1);
        assertThat(STUB.requests()).hasSize(2);
        var a=STUB.requests().get(0); var b=STUB.requests().get(1);
        assertThat(a.body()).isEqualTo(b.body()); assertThat(a.eventId()).isEqualTo(b.eventId());
        assertThat(a.requestId()).isNotEqualTo(b.requestId());
    }
    @Test void conflictIsDeadWithoutFurtherRequests(CapturedOutput output) {
        fixture.createPending(1); STUB.respond(409);
        assertThat(service.runOnce().dead()).isEqualTo(1);
        assertThat(text("dead_reason")).isEqualTo("NON_RETRYABLE");
        assertThat(service.runOnce().claimed()).isZero(); assertThat(STUB.requests()).hasSize(1);
        assertThat(output.getOut()).contains("OUTBOX_DEAD reason=NON_RETRYABLE");
    }
    @Test void repeatedFailureStopsAtMaxAttempts(CapturedOutput output) {
        fixture.createPending(1); STUB.respond(503);
        service.runOnce(); service.runOnce(); assertThat(service.runOnce().dead()).isEqualTo(1);
        assertThat(text("dead_reason")).isEqualTo("MAX_ATTEMPTS");
        assertThat(outcomes()).containsExactly("RETRYABLE", "RETRYABLE", "RETRYABLE");
        assertThat(service.runOnce().claimed()).isZero(); assertThat(STUB.requests()).hasSize(3);
        assertThat(output.getOut()).contains("OUTBOX_DEAD reason=MAX_ATTEMPTS");
    }
    @Test void oneRecordFailureDoesNotPreventOtherEventFromCompleting(CapturedOutput output) {
        var ids=fixture.createPending(2);
        doThrow(new IllegalStateException("record failed")).when(recorderSpy).record(argThat(e -> e.id()==ids.get(0)), any());
        var result=service.runOnce();
        assertThat(result.sent()).isEqualTo(1); assertThat(result.failed()).isEqualTo(1);
        assertThat(text("status")).isEqualTo("PENDING"); assertThat(text("claim_token::text")).isNotNull();
        assertThat(output.getOut()).contains("DELIVERY_RECORD_FAILED");
    }
    // DL-09: emulate a process dying after TX1 or after HTTP and before TX2.
    @Test void expiredClaimIsAbandonedAndRecovered() {
        claimOne(); expire(); assertThat(service.runOnce().sent()).isEqualTo(1);
        assertThat(outcomes()).containsExactly("ABANDONED", "SUCCESS");
        assertThat(STUB.requests()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_event", Integer.class)).isEqualTo(2);
    }
    @Test void unexpiredClaimRemainsStartedWithoutSending() {
        claimOne(); assertThat(service.runOnce().claimed()).isZero();
        assertThat(outcomes()).containsExactly("STARTED"); assertThat(STUB.requests()).isEmpty();
    }
    @Test void recoversAfterHttpCompletedWithoutRecording() {
        var event=claimOne(); assertThat(client.send(event.eventId(), event.claimToken(), event.payload()).httpStatus()).isEqualTo(200);
        expire(); assertThat(service.runOnce().sent()).isEqualTo(1);
        assertThat(STUB.requests()).hasSize(2).extracting(StubReceiver.Request::eventId).containsOnly(event.eventId());
        assertThat(outcomes()).containsExactly("ABANDONED", "SUCCESS");
    }
}
