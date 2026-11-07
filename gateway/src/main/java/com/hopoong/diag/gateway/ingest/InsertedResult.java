package com.hopoong.diag.gateway.ingest;
import java.time.OffsetDateTime;
public record InsertedResult(long id, OffsetDateTime receivedAt) {}
