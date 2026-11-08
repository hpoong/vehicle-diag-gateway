package com.hopoong.diag.testsupport;
import org.springframework.jdbc.core.JdbcTemplate;
import com.hopoong.diag.gateway.campaign.web.CampaignCreateRequest;
public final class CampaignFixture {
    private CampaignFixture() {}
    public static void loadLot(JdbcTemplate jdbc,String ecuId,String lotNo,int current,int removed) {
        int start=jdbc.queryForObject("SELECT count(*) FROM vehicle_part",Integer.class)+1;
        for (int i=start;i<start+current+removed;i++) jdbc.update("INSERT INTO vehicle_part(vin,ecu_id,part_no,serial_no,lot_no,installed_at,removed_at) VALUES (?,?, 'P',?,?,now()-interval '30 days',CASE WHEN ? THEN now()-interval '1 day' ELSE NULL END)",
            "KMHCP"+String.format("%012d",i),ecuId,"S-"+lotNo+"-"+i,lotNo,i>=start+current);
    }
    public static CampaignCreateRequest request(String id,String ecu,String lot) {return new CampaignCreateRequest(id,ecu,lot,"P0700","고장률 상승으로 조치 대상 확인","ops-it");}
}
