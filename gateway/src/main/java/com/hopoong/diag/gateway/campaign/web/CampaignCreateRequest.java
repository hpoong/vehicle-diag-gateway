package com.hopoong.diag.gateway.campaign.web;
import com.hopoong.diag.gateway.trace.web.TraceController;
import com.hopoong.diag.gateway.ingest.web.validation.StrictStringDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
public record CampaignCreateRequest(
    @NotNull @Size(min=1,max=64) @Pattern(regexp="^[A-Za-z0-9_-]+$") @JsonDeserialize(using=StrictStringDeserializer.class) String requestId,
    @NotNull @Size(min=1,max=30) @Pattern(regexp=TraceController.ECU_ID) @JsonDeserialize(using=StrictStringDeserializer.class) String ecuId,
    @NotNull @Size(min=1,max=30) @Pattern(regexp=TraceController.LOT_NO) @JsonDeserialize(using=StrictStringDeserializer.class) String lotNo,
    @NotNull @Pattern(regexp=TraceController.DTC_CODE) @JsonDeserialize(using=StrictStringDeserializer.class) String dtcCode,
    @NotBlank @JsonDeserialize(using=StrictStringDeserializer.class) String reason,
    @NotNull @Size(min=1,max=64) @Pattern(regexp="^[A-Za-z0-9._-]+$") @JsonDeserialize(using=StrictStringDeserializer.class) String requestedBy) {
    @JsonIgnore @AssertTrue(message="사유는 NUL 없이 10~500자여야 합니다.")
    public boolean isReasonValid() {return reason==null || (reason.indexOf(0)<0 && reason.codePointCount(0,reason.length())>=10 && reason.codePointCount(0,reason.length())<=500);}
}
