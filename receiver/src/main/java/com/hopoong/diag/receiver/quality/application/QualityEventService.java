package com.hopoong.diag.receiver.quality.application;

import com.hopoong.diag.receiver.common.error.ReceiverErrorCode;
import com.hopoong.diag.receiver.fault.FaultState;
import com.hopoong.diag.receiver.quality.config.CompareProperties;
import com.hopoong.diag.receiver.quality.web.QualityEventRequest;
import com.hopoong.diag.websupport.error.BusinessException;
import com.hopoong.diag.websupport.error.CommonErrorCode;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityEventService {
    private final CasePairValidator pairValidator;
    private final PayloadHasher hasher;
    private final QualityEventWriter writer;
    private final FaultState faultState;
    private final CompareProperties compare;

    public ApplyResult apply(QualityEventRequest request) {
        var errors = pairValidator.validate(request);
        if (!errors.isEmpty()) throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        var cases = request.ecus().stream().flatMap(ecu -> ecu.dtcCodes().stream()
                .map(dtc -> new CaseRow(ecu.ecuId(), ecu.swVersion(), dtc))).toList();
        var cmd = new QualityEventCommand(request.eventId(), request.source().resultId(), request.vin(),
                OffsetDateTime.parse(request.occurredAt()), cases);
        var result = compare.naiveInsert() ? writer.applyNaive(cmd) : writer.apply(cmd, hasher.hash(request));
        if (result.outcome() == ApplyResult.Outcome.CONFLICT) {
            log.warn("QUALITY_CONFLICT");
            throw new BusinessException(ReceiverErrorCode.EVENT_CONFLICT);
        }
        if (compare.naiveInsert()) {
            log.info("QUALITY_APPLIED caseCount={} naive=true", result.caseCount());
        } else {
            log.info("QUALITY_{} caseCount={}", result.outcome(), result.caseCount());
        }
        faultState.awaitIfDropAfterCommit();
        return result;
    }
}
