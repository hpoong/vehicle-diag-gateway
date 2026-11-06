package com.hopoong.diag.gateway.ingest.validation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IsoOffsetDateTimeValidatorTest {
    @Test
    void requiresAValidOffsetDateTimeAndAllowsNullForNotNullToHandle() {
        var validator = new IsoOffsetDateTimeValidator();
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("2026-11-06T08:10:00Z", null)).isTrue();
        assertThat(validator.isValid("2026-11-06T08:10:00+09:00", null)).isTrue();
        assertThat(validator.isValid("2026-11-06T08:10:00", null)).isFalse();
        assertThat(validator.isValid("2026-13-01T00:00:00Z", null)).isFalse();
    }
}
