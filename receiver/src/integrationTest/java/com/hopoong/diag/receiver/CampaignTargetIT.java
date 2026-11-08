package com.hopoong.diag.receiver;
import com.hopoong.diag.testsupport.CampaignTargetFixture;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
class CampaignTargetIT extends ReceiverDatabaseITSupport {
    org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> send(String body) {return postTo("/api/v1/campaign-targets",body,null);}
    @Test void appliesReplaysAndRejectsConflictingBody() throws Exception {
        String id=UUID.randomUUID().toString(),body=CampaignTargetFixture.sample(id,3);
        var response=send(body);assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().at("/data/result").asText()).isEqualTo("APPLIED");
        assertThat(response.getBody().at("/data/vehicleCount").asInt()).isEqualTo(3);
        String hash=jdbc.queryForObject("SELECT payload_hash FROM received_campaign",String.class);assertThat(hash).hasSize(64);
        assertThat(send(body).getBody().at("/data/result").asText()).isEqualTo("DUPLICATE");
        var tree=mapper.readTree(body);var ordered=new LinkedHashMap<String,Object>();
        ordered.put("vehicles",tree.get("vehicles"));ordered.put("campaign",tree.get("campaign"));ordered.put("source",tree.get("source"));ordered.put("eventId",id);
        assertThat(send(mapper.writeValueAsString(ordered)).getBody().at("/data/result").asText()).isEqualTo("DUPLICATE");
        assertThat(send(CampaignTargetFixture.sample(id,4)).getStatusCode().value()).isEqualTo(409);
        assertThat(count("received_campaign")).isEqualTo(1);assertThat(count("received_campaign_vehicle")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM received_campaign",String.class)).isEqualTo(hash);
    }
    @Test void validatesBodyAndDuplicateVin() throws Exception {
        String body=CampaignTargetFixture.sample(UUID.randomUUID().toString(),3);
        for (String invalid:List.of(body.replace("\"campaignId\":7","\"campaignId\":0"),body.replace("2026-11-01T00:00:00Z","2026-11-01T00:00:00"),body.replace("S-1","x".repeat(41)),body.replace("KMHCP000000000001","x".repeat(18)),body.replace("\"campaign\":","\"unknown\":1,\"campaign\":"),CampaignTargetFixture.sample(UUID.randomUUID().toString(),0)))
            assertThat(send(invalid).getStatusCode().value()).isEqualTo(400);
        var duplicate=send(body.replace("KMHCP000000000002","KMHCP000000000001"));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(400);
        assertThat(duplicate.getBody().at("/errors/0/rule").asText()).isEqualTo("DUPLICATE_VEHICLE");
        assertEmpty();
    }
    @Test void rejectsMissingFieldsInvalidUuidAndTooManyVehicles() throws Exception {
        var valid=mapper.readTree(CampaignTargetFixture.sample(UUID.randomUUID().toString(),3));
        for (String field:List.of("eventId","source","campaign","vehicles")) {
            var changed=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).remove(field);
            assertThat(send(mapper.writeValueAsString(changed)).getStatusCode().value()).isEqualTo(400);
        }
        for (String field:List.of("ecuId","lotNo","createdAt")) {
            var changed=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed.get("campaign")).remove(field);
            assertThat(send(mapper.writeValueAsString(changed)).getStatusCode().value()).isEqualTo(400);
        }
        var changed=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("eventId","invalid");
        assertThat(send(mapper.writeValueAsString(changed)).getStatusCode().value()).isEqualTo(400);
        changed=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed.get("source")).remove("campaignId");
        assertThat(send(mapper.writeValueAsString(changed)).getStatusCode().value()).isEqualTo(400);
        assertThat(send(CampaignTargetFixture.sample(UUID.randomUUID().toString(),5001)).getStatusCode().value()).isEqualTo(400);
        assertEmpty();
    }
    @Test void schemaRejectsInvalidMetadataAndMissingParent() {
        for (String values:List.of("'x',1","repeat('a',64),0"))
            assertThatThrownBy(() -> jdbc.update("INSERT INTO received_campaign(event_id,payload_hash,vehicle_count,campaign_id,ecu_id,lot_no) SELECT gen_random_uuid(),"+values+",7,'TCM','L-A'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO received_campaign_vehicle VALUES (gen_random_uuid(),'KMHCP000000000001','S')")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
