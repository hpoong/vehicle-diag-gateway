-- gateway V5 조치 대상(캠페인) + outbox 일반화 — PostgreSQL 16
create table campaign (
    id            bigserial    primary key,
    request_id    varchar(64)  not null,
    request_hash  char(64)     not null,
    ecu_id        varchar(30)  not null,
    lot_no        varchar(30)  not null,
    dtc_code      varchar(10)  not null,
    reason        varchar(500) not null,
    requested_by  varchar(64)  not null,
    target_count  int          not null,
    created_at    timestamptz  not null default now(),
    constraint uq_campaign_request      unique (request_id),
    constraint ck_campaign_request_hash check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_campaign_target_count check (target_count between 1 and 5000),
    constraint ck_campaign_reason       check (char_length(reason) >= 10)
);

create table campaign_vehicle (
    campaign_id  bigint      not null,
    vin          varchar(17) not null,
    serial_no    varchar(40) not null,
    constraint pk_campaign_vehicle          primary key (campaign_id, vin),
    constraint fk_campaign_vehicle_campaign foreign key (campaign_id) references campaign (id)
);

alter table outbox_event
    add column event_type  varchar(20) not null default 'DIAGNOSTIC_RESULT',
    add column campaign_id bigint,
    alter column result_id drop not null,
    add constraint fk_outbox_campaign   foreign key (campaign_id) references campaign (id),
    add constraint uq_outbox_campaign   unique (campaign_id),
    add constraint ck_outbox_event_type check (
        (event_type = 'DIAGNOSTIC_RESULT' and result_id is not null and campaign_id is null)
     or (event_type = 'CAMPAIGN'          and campaign_id is not null and result_id is null));

comment on table  outbox_event             is '전송 이벤트. 진단 결과 1건 또는 캠페인 1건당 1행. 상태 PENDING → SENT/DEAD, DEAD → PENDING은 수동 재처리만';
comment on column outbox_event.result_id   is '원본 진단 결과 ID(1:1). event_type=CAMPAIGN이면 null';
comment on column outbox_event.event_type  is '이벤트 종류: DIAGNOSTIC_RESULT / CAMPAIGN. receiver 전송 경로를 정한다';
comment on column outbox_event.campaign_id is '원본 캠페인 ID(1:1). event_type=DIAGNOSTIC_RESULT이면 null';
comment on table campaign is 'LOT 현재 장착 차량 조치 대상 생성 요청';
comment on table campaign_vehicle is '생성 시점에 확정한 캠페인 대상 차량과 부품 스냅샷';
comment on column campaign.id is '캠페인 ID';
comment on column campaign.request_id is '멱등 요청 ID';
comment on column campaign.request_hash is 'requestId 제외 정규화 요청의 SHA-256 소문자 hex';
comment on column campaign.ecu_id is '대상 ECU';
comment on column campaign.lot_no is '대상 부품 LOT';
comment on column campaign.dtc_code is '관심 DTC';
comment on column campaign.reason is '조치 사유';
comment on column campaign.requested_by is '요청자';
comment on column campaign.target_count is '확정 대상 차량 수';
comment on column campaign.created_at is '생성 시각';
comment on column campaign_vehicle.campaign_id is '캠페인 ID';
comment on column campaign_vehicle.vin is '대상 차대번호';
comment on column campaign_vehicle.serial_no is '확정 시점 부품 시리얼';
