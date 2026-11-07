package com.hopoong.diag.gateway.scenario;

import java.util.Map;
import java.util.stream.Collectors;

public record ReconcileReport(ReconcileAssertions.Mode mode, int results, int outbox, int pending,
        int sent, int dead, int received, Map<String, Integer> attemptOutcomes, long dup1,
        Map<String, Verdict> verdicts) {
    public enum Status { PASS, FAIL, SKIP }
    public record Verdict(Status status, String detail) {}
    public ReconcileReport {
        attemptOutcomes = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(attemptOutcomes));
        verdicts = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(verdicts));
    }
    public void assertAllPass() {
        var failures = verdicts.entrySet().stream().filter(e -> e.getValue().status() == Status.FAIL).toList();
        if (!failures.isEmpty()) throw new AssertionError(failures.toString());
    }
    public String line(String scenario) {
        return "SCENARIO_RESULT scenario=" + scenario + " mode=" + mode + " results=" + results
                + " outbox=" + outbox + " pending=" + pending + " sent=" + sent + " dead=" + dead
                + " received=" + received + " dup1=" + dup1 + " attempts=" + attemptOutcomes + " "
                + verdicts.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().status()).collect(Collectors.joining(" "));
    }
}
