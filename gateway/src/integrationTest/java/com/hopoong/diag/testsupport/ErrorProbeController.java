package com.hopoong.diag.testsupport;

import com.hopoong.diag.gateway.common.error.GatewayErrorCode;
import com.hopoong.diag.websupport.error.ApiFieldError;
import com.hopoong.diag.websupport.error.BusinessException;
import com.hopoong.diag.websupport.error.CommonErrorCode;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ErrorProbeController {
    @GetMapping("/error-probe/{kind}")
    void error(@PathVariable String kind) {
        switch (kind) {
            case "conflict" -> throw new BusinessException(GatewayErrorCode.EVENT_CONFLICT);
            case "rules" -> throw new BusinessException(GatewayErrorCode.COMMON_RULE_VIOLATION,
                    List.of(new ApiFieldError("a", "rule1", "first"),
                            new ApiFieldError("b", "rule2", "second")));
            case "policy" -> throw new BusinessException(GatewayErrorCode.POLICY_VIOLATION,
                    List.of(), Map.of("policyCode", "X"));
            case "missing" -> throw new BusinessException(CommonErrorCode.NOT_FOUND);
            case "transaction" -> throw new CannotCreateTransactionException("secret-down");
            case "mybatis" -> throw new MyBatisSystemException("x",
                    new PersistenceException(new CannotGetJdbcConnectionException("secret-down")));
            default -> throw new IllegalArgumentException(kind);
        }
    }
}
