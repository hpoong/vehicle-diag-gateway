package com.hopoong.diag.gateway.production.web;
public record VehiclePartImportResponse(String batchId, int received, int inserted, int updated, int unchanged) {}
