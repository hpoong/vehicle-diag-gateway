package com.hopoong.diag.gateway.ingest;

import java.util.List;

public record EcuCommand(String ecuId, String swVersion, List<String> dtcCodes, int requestIndex) {}
