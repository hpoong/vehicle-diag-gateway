package com.hopoong.diag.receiver.campaign.application;
import com.hopoong.diag.receiver.campaign.web.CampaignTargetRequest;
import com.hopoong.diag.websupport.error.ApiFieldError;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class CampaignVehicleValidator {
    public List<ApiFieldError> validate(CampaignTargetRequest request) {
        var seen=new HashSet<String>();var errors=new ArrayList<ApiFieldError>();
        for (int i=0;i<request.vehicles().size();i++)
            if (!seen.add(request.vehicles().get(i).vin())) errors.add(new ApiFieldError("vehicles["+i+"].vin","DUPLICATE_VEHICLE","같은 VIN이 중복됩니다"));
        return errors;
    }
}
