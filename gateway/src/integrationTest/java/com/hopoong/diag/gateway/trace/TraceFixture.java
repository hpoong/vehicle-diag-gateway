package com.hopoong.diag.gateway.trace;
import com.hopoong.diag.gateway.ingest.application.IngestService;
import com.hopoong.diag.gateway.ingest.web.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
class TraceFixture {
    private final JdbcTemplate jdbc;
    private final IngestService ingest;
    TraceFixture(JdbcTemplate jdbc,IngestService ingest) {this.jdbc=jdbc;this.ingest=ingest;}
    static String vin(int i) {return "KMHTR0000000000"+String.format("%02d",i);}
    void part(int i,String serial,String lot,String start,String end) {
        jdbc.update("INSERT INTO vehicle_part(vin,ecu_id,part_no,serial_no,lot_no,installed_at,removed_at) VALUES (?,'TCM','P',?,?,?::timestamptz,?::timestamptz)",vin(i),serial,lot,"2026-"+start+"T00:00:00Z",end==null?null:"2026-"+end+"T00:00:00Z");
    }
    void diag(int i,String date,boolean fault) {diag(i,"2026-"+date+"T03:00:00Z",fault,false);}
    void diag(int i,String time,boolean fault,boolean bms) {
        var ecus=new ArrayList<EcuRequest>();ecus.add(new EcuRequest("TCM",null,fault?List.of("P0700"):List.of()));
        if (bms) ecus.add(new EcuRequest("BMS",null,List.of("P0700")));
        ingest.ingest(new DiagnosticResultRequest("trace-device","v"+i+"-"+time.replaceAll("[^0-9]",""),"ZETA",vin(i),"s",time,ecus));
    }
    void diagnoses() {
        diag(1,"10-05",true);diag(1,"10-05",true);
        diag(2,"10-06",true);diag(2,"10-07",true);diag(3,"10-08",true);diag(4,"10-09",false);
        diag(5,"09-20",true);diag(5,"10-03",false);diag(6,"2026-10-04T03:00:00Z",false,true);
        diag(7,"2026-10-01T00:00:00Z",false,false);diag(7,"2026-11-01T00:00:00Z",true,false);
        diag(8,"10-10",true);diag(8,"10-20",false);diag(9,"10-11",true);diag(10,"10-12",true);diag(11,"10-13",true);diag(12,"10-14",true);
    }
    void load() {
        for (int i:new int[]{1,2,3}) part(i,"S-0"+i,"L-A","01-01",null);
        part(4,"S-04A","L-A","01-01","03-01");part(4,"S-04B","L-A","03-01",null);
        for (int i:new int[]{5,6,7,13}) part(i,"S-"+String.format("%02d",i),"L-B","01-01",null);
        part(8,"S-08A","L-A","01-01","10-15");part(8,"S-08B","L-B","10-15",null);
        part(9,"S-09",null,"01-01",null);
        part(11,"S-11A","L-C","01-01","10-20");part(11,"S-11B","L-B","06-01",null);
        part(12,"S-12","L-C","01-01",null);diagnoses();
    }
}
