package com.hopoong.diag.testsupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
public final class CampaignTargetFixture {
    public static String sample(String eventId,int count) {
        var vehicles=new ArrayList<Map<String,String>>();
        for (int i=1;i<=count;i++) vehicles.add(Map.of("vin","KMHCP"+String.format("%012d",i),"serialNo","S-"+i));
        try {return new ObjectMapper().writeValueAsString(Map.of("eventId",eventId,"source",Map.of("system","diag-gateway","campaignId",7,"requestId","req-1"),
            "campaign",Map.of("ecuId","TCM","lotNo","L-A","dtcCode","P0700","reason","테스트 캠페인 조치 사유입니다","requestedBy","ops-it","createdAt","2026-11-01T00:00:00Z"),"vehicles",vehicles));}
        catch (Exception e) {throw new IllegalStateException(e);}
    }
}
