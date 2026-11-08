package com.hopoong.diag.receiver.campaign.application;
import com.hopoong.diag.receiver.campaign.web.*;
import com.hopoong.diag.receiver.quality.application.PayloadHasher;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CampaignVehicleValidatorTest {
    CampaignTargetRequest request(List<CampaignVehicleRequest> vehicles) {
        return new CampaignTargetRequest("event",new CampaignSourceRequest("diag-gateway",1L,"r"),
            new CampaignInfoRequest("TCM","L-A","P0700","사유","ops","2026-11-01T00:00:00Z"),vehicles);
    }
    @Test void identifiesEveryDuplicateVin() {
        var a=new CampaignVehicleRequest("v1","s1");var b=new CampaignVehicleRequest("v2","s2");
        var validator=new CampaignVehicleValidator();
        assertThat(validator.validate(request(List.of(a,b)))).isEmpty();
        assertThat(validator.validate(request(List.of(a,a,b,b)))).extracting(e -> e.getField()).containsExactly("vehicles[1].vin","vehicles[3].vin");
    }
    @Test void vehicleOrderIsPartOfPayloadHash() {
        var a=new CampaignVehicleRequest("v1","s1");var b=new CampaignVehicleRequest("v2","s2");var hasher=new PayloadHasher();
        assertThat(hasher.hash(request(List.of(a,b)))).isEqualTo(hasher.hash(request(List.of(a,b)))).isNotEqualTo(hasher.hash(request(List.of(b,a))));
    }
}
