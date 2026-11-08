package com.hopoong.diag.gateway.campaign.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.campaign.persistence.SnapshotVehicle;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CampaignContractTest {
    @Test void hashExcludesOnlyRequestId() {
        var hasher=new CampaignRequestHasher();
        var a=new CampaignCommand("a","TCM","L-A","P0700","사유를 충분하게 작성합니다","ops");
        var b=new CampaignCommand("b",a.ecuId(),a.lotNo(),a.dtcCode(),a.reason(),a.requestedBy());
        var changed=new CampaignCommand("a",a.ecuId(),a.lotNo(),a.dtcCode(),a.reason()+" ",a.requestedBy());
        assertThat(hasher.hash(a)).hasSize(64).isEqualTo(hasher.hash(b)).isNotEqualTo(hasher.hash(changed));
    }
    @Test void payloadSortsVinAndFormatsUtcMillis() throws Exception {
        var json=new ObjectMapper();var factory=new CampaignPayloadFactory(json);
        var draft=factory.create(new CampaignCommand("r","TCM","L-A","P0700","사유를 충분하게 작성합니다","ops"),7,
            OffsetDateTime.parse("2026-11-01T09:00:00.123456+09:00"),List.of(new SnapshotVehicle("v2","s2"),new SnapshotVehicle("v1","s1")));
        var payload=json.readTree(draft.payloadJson());
        assertThat(payload.get("eventId").asText()).isEqualTo(draft.eventId().toString());
        assertThat(payload.at("/campaign/createdAt").asText()).isEqualTo("2026-11-01T00:00:00.123Z");
        assertThat(payload.get("vehicles").findValuesAsText("vin")).containsExactly("v1","v2");
    }
}
