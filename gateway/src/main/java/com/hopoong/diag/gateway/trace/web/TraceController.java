package com.hopoong.diag.gateway.trace.web;
import com.hopoong.diag.gateway.trace.application.*;
import com.hopoong.diag.gateway.trace.config.TraceProperties;
import com.hopoong.diag.gateway.ingest.web.validation.IsoOffsetDateTime;
import com.hopoong.diag.websupport.response.ApiResponse;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/trace/lots")
public class TraceController {
    public static final String ECU_ID="^[A-Z0-9_]+$";
    public static final String DTC_CODE="^[PCBU][0-3][0-9A-F]{3}$";
    public static final String LOT_NO="^[A-Za-z0-9][A-Za-z0-9._-]*$";
    public static final int MAX_FORWARD_LIMIT=5000;
    private final TraceService service;
    private final TraceProperties props;
    public TraceController(TraceService service,TraceProperties props) {this.service=service;this.props=props;}
    @GetMapping
    public ApiResponse<ReverseTraceResponse> reverse(
        @RequestParam @Size(min=1,max=30) @Pattern(regexp=ECU_ID) String ecuId,
        @RequestParam @Pattern(regexp=DTC_CODE) String dtcCode,
        @RequestParam(required=false) @IsoOffsetDateTime String from,
        @RequestParam(required=false) @IsoOffsetDateTime String to,
        @RequestParam(required=false) @Size(min=1,max=30) @Pattern(regexp=LOT_NO) String lotNo) {
        var window=TraceWindow.resolve(from,to,Instant.now(),props);
        return ApiResponse.success(service.reverse(ecuId,dtcCode,window,lotNo));
    }
    @GetMapping("/{lotNo}/vehicles")
    public ApiResponse<ForwardTraceResponse> forward(
        @PathVariable @Size(min=1,max=30) @Pattern(regexp=LOT_NO) String lotNo,
        @RequestParam @Size(min=1,max=30) @Pattern(regexp=ECU_ID) String ecuId,
        @RequestParam(defaultValue="500") @Min(1) @Max(MAX_FORWARD_LIMIT) int limit) {
        return ApiResponse.success(service.forward(lotNo,ecuId,limit));
    }
}
