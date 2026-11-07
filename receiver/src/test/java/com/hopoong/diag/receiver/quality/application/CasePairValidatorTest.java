package com.hopoong.diag.receiver.quality.application;

import com.hopoong.diag.receiver.quality.web.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CasePairValidatorTest {
    private final CasePairValidator validator = new CasePairValidator();
    private QualityEventRequest request(EcuRequest... ecus) {
        return new QualityEventRequest(null, null, null, null, null, null, List.of(ecus));
    }
    private EcuRequest ecu(String... dtcs) { return new EcuRequest("BMS", null, List.of(dtcs)); }

    @Test
    void reportsEveryRepeatedPairAtItsSecondAndLaterLocation() {
        assertThat(validator.validate(request(ecu("P0A80", "P0A80")))).extracting("field")
                .containsExactly("ecus[0].dtcCodes[1]");
        assertThat(validator.validate(request(ecu("P0A80"), ecu("P0A80")))).extracting("field")
                .containsExactly("ecus[1].dtcCodes[0]");
        assertThat(validator.validate(request(ecu("P0A80"), ecu("P0AFA")))).isEmpty();
    }
}
