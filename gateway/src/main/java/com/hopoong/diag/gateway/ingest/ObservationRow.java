package com.hopoong.diag.gateway.ingest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
@Getter
@RequiredArgsConstructor
public class ObservationRow {
    private final long resultId;
    private final String ecuId;
    private final String swVersion;
    private final String[] dtcCodes;
}
