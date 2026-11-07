package com.hopoong.diag.gateway.ingest.persistence;
import java.time.OffsetDateTime;
public record InsertedResult(long id, OffsetDateTime receivedAt) {}
