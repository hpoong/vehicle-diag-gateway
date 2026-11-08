package com.hopoong.diag.testsupport;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
public final class CampaignOutboxFixture {
    private CampaignOutboxFixture() {}
    public static long create(JdbcTemplate jdbc) {
        long campaign=jdbc.queryForObject("INSERT INTO campaign(request_id,request_hash,ecu_id,lot_no,dtc_code,reason,requested_by,target_count) VALUES (?,repeat('a',64),'TCM','L-1','P0700','테스트 캠페인 사유입니다','ops-it',1) RETURNING id",Long.class,UUID.randomUUID().toString());
        String event=UUID.randomUUID().toString();
        return jdbc.queryForObject("INSERT INTO outbox_event(event_type,event_id,campaign_id,payload) VALUES ('CAMPAIGN',?::uuid,?,jsonb_build_object('eventId',?::text)) RETURNING id",Long.class,event,campaign,event);
    }
}
