package com.hopoong.diag.receiver.quality.application;

import com.hopoong.diag.receiver.quality.web.QualityEventRequest;
import com.hopoong.diag.websupport.error.ApiFieldError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CasePairValidator {
    private record Pair(String ecuId, String dtcCode) {}

    public List<ApiFieldError> validate(QualityEventRequest request) {
        var seen = new HashSet<Pair>();
        var errors = new ArrayList<ApiFieldError>();
        for (int i = 0; i < request.ecus().size(); i++) {
            var ecu = request.ecus().get(i);
            for (int j = 0; j < ecu.dtcCodes().size(); j++) {
                if (!seen.add(new Pair(ecu.ecuId(), ecu.dtcCodes().get(j)))) {
                    errors.add(new ApiFieldError("ecus[" + i + "].dtcCodes[" + j + "]",
                            "DUPLICATE_CASE", "같은 ECU·DTC 쌍이 중복됩니다"));
                }
            }
        }
        return List.copyOf(errors);
    }
}
