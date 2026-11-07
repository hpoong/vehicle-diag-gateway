package com.hopoong.diag.gateway.common;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class UtcTime {
    public static final DateTimeFormatter MILLIS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private UtcTime() {}
}
