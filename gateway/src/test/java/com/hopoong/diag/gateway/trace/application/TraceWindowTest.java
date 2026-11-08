package com.hopoong.diag.gateway.trace.application;
import com.hopoong.diag.gateway.trace.config.TraceProperties;
import com.hopoong.diag.websupport.error.BusinessException;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TraceWindowTest {
    final TraceProperties props=new TraceProperties(20,Duration.ofDays(90),Duration.ofDays(30));
    final Instant now=Instant.parse("2026-11-01T00:00:00Z");
    @Test void defaultsAndPrecision() {
        var w=TraceWindow.resolve(null,null,now,props);
        assertThat(w.to().toInstant()).isEqualTo(now);
        assertThat(Duration.between(w.from(),w.to())).isEqualTo(Duration.ofDays(30));
        w=TraceWindow.resolve("2026-10-01T09:00:00.0000019+09:00",null,now,props);
        assertThat(w.from().toString()).isEqualTo("2026-10-01T00:00:00.000001Z");
    }
    @Test void bounds() {
        assertThatCode(() -> TraceWindow.resolve("2026-08-03T00:00:00Z",null,now,props)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TraceWindow.resolve("2026-08-02T23:59:59.999999Z",null,now,props)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TraceWindow.resolve(now.toString(),null,now,props)).isInstanceOf(BusinessException.class);
    }
}
