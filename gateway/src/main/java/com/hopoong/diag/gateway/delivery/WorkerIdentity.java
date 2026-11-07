package com.hopoong.diag.gateway.delivery;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class WorkerIdentity {
    private final String instanceId;

    public WorkerIdentity(DeliveryProperties properties) {
        String configured = properties.instanceId();
        this.instanceId = truncate(configured == null || configured.isBlank() ? hostname() : configured, 64);
    }

    public String instanceId() { return instanceId; }
    public String workerId(String threadName) { return truncate(instanceId + ":" + threadName, 100); }

    private static String hostname() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name.isBlank() ? "gateway" : name;
        } catch (UnknownHostException ex) {
            return "gateway";
        }
    }

    private static String truncate(String value, int max) {
        return value.substring(0, Math.min(value.length(), max));
    }
}
