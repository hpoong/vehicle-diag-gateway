package com.hopoong.diag.gateway.delivery;
import com.hopoong.diag.testsupport.*;
import com.hopoong.diag.gateway.delivery.application.DeliveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
class OutboxGeneralizeIT extends DeliveryDatabaseITSupport {
    static final StubReceiver STUB=new StubReceiver();
    @DynamicPropertySource static void receiver(DynamicPropertyRegistry r) {r.add("gateway.delivery.receiver-url",STUB::url);}
    @AfterAll static void close() {STUB.close();}
    @BeforeEach void resetStub() {STUB.reset();}
    @Autowired DeliveryService delivery;
    @Test void sendsEachKindToItsEndpointWithUnchangedBytes() {
        fixture.createPending(1);CampaignOutboxFixture.create(jdbc);
        assertThat(delivery.runOnce().sent()).isEqualTo(2);
        assertThat(STUB.requests()).extracting(StubReceiver.Request::path).containsExactlyInAnyOrder("/api/v1/quality-events","/api/v1/campaign-targets");
        for (var r:STUB.requests()) {
            assertThat(r.body()).isEqualTo(jdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE event_id=?::uuid",String.class,r.eventId()));
            assertThat(r.requestId()).isNotBlank();
        }
    }
    @Test void claimsBothKindsAndChecksDatabaseConstraints() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign",Integer.class)).isZero();
        fixture.createPending(1);long id=CampaignOutboxFixture.create(jdbc);
        assertThat(claimer.claim(10)).extracting(e -> e.eventType()).containsExactlyInAnyOrder("DIAGNOSTIC_RESULT","CAMPAIGN");
        for (String value:new String[]{"FOO","DIAGNOSTIC_RESULT"})
            assertThatThrownBy(() -> jdbc.update("UPDATE outbox_event SET event_type=? WHERE id=?",value,id)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_outbox_event_type");
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_event SET campaign_id=NULL WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_outbox_event_type");
    }
}
