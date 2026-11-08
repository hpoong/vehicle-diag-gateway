package com.hopoong.diag.gateway.trace.application;
import com.hopoong.diag.gateway.trace.config.TraceProperties;
import com.hopoong.diag.websupport.error.*;
import static com.hopoong.diag.gateway.common.error.GatewayErrorCode.INVALID_TRACE_WINDOW;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
public record TraceWindow(OffsetDateTime from, OffsetDateTime to) {
    private static OffsetDateTime parse(String value) {
        return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
    public static TraceWindow resolve(String from, String to, Instant now, TraceProperties props) {
        var end=to==null ? now.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS) : parse(to);
        var start=from==null ? end.minus(props.defaultWindow()) : parse(from);
        if (!start.isBefore(end)) throw new BusinessException(INVALID_TRACE_WINDOW,List.of(new ApiFieldError("from","TRACE.WINDOW_ORDER","from은 to보다 앞이어야 합니다")));
        if (Duration.between(start,end).compareTo(props.maxWindow())>0) throw new BusinessException(INVALID_TRACE_WINDOW,List.of(new ApiFieldError("to","TRACE.WINDOW_TOO_LONG","조회 기간은 최대 "+props.maxWindow().toDays()+"일입니다")));
        return new TraceWindow(start,end);
    }
}
