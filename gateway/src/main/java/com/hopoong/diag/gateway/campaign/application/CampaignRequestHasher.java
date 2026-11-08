package com.hopoong.diag.gateway.campaign.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class CampaignRequestHasher {
    private static final ObjectMapper JSON=new ObjectMapper();
    public String hash(CampaignCommand cmd) {
        var values=new TreeMap<String,String>();
        values.put("ecuId",cmd.ecuId());values.put("lotNo",cmd.lotNo());values.put("dtcCode",cmd.dtcCode());values.put("reason",cmd.reason());values.put("requestedBy",cmd.requestedBy());
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(values).getBytes(StandardCharsets.UTF_8)));}
        catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Cannot hash campaign request",e);}
    }
}
