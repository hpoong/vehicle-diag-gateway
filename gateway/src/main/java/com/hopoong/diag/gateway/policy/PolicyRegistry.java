package com.hopoong.diag.gateway.policy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PolicyRegistry {
    private final Map<String, ResultValidationPolicy> policiesByBrand;
    private final ResultValidationPolicy standardPolicy;

    public PolicyRegistry(List<ResultValidationPolicy> policies) {
        Map<String, ResultValidationPolicy> mapping = new HashMap<>();
        ResultValidationPolicy standard = null;
        for (ResultValidationPolicy policy : policies) {
            if ("STANDARD-v1".equals(policy.code())) {
                if (standard != null) throw new IllegalStateException("Duplicate STANDARD-v1 policy");
                standard = policy;
            }
            for (String brand : policy.brands()) {
                if (mapping.putIfAbsent(brand, policy) != null) {
                    throw new IllegalStateException("Duplicate policy for brand: " + brand);
                }
            }
        }
        if (standard == null) throw new IllegalStateException("STANDARD-v1 policy is required");
        this.standardPolicy = standard;
        this.policiesByBrand = Map.copyOf(mapping);
    }

    public ResultValidationPolicy resolve(String brand) {
        return policiesByBrand.getOrDefault(brand, standardPolicy);
    }
}
