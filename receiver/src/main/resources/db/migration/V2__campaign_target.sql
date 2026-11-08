-- receiver V2 조치 대상 수신 — PostgreSQL 16
create table received_campaign (
    event_id       uuid         primary key,
    payload_hash   char(64)     not null,
    campaign_id    bigint       not null,
    ecu_id         varchar(30)  not null,
    lot_no         varchar(30)  not null,
    vehicle_count  int          not null,
    received_at    timestamptz  not null default now(),
    constraint ck_received_campaign_payload_hash  check (payload_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_received_campaign_vehicle_count check (vehicle_count >= 1)
);

create table received_campaign_vehicle (
    event_id   uuid        not null,
    vin        varchar(17) not null,
    serial_no  varchar(40) not null,
    constraint pk_received_campaign_vehicle primary key (event_id, vin),
    constraint fk_received_campaign_vehicle_event foreign key (event_id) references received_campaign (event_id)
);
comment on table received_campaign is '조치 대상 수신 이벤트';
comment on column received_campaign.event_id is 'gateway 전송 이벤트 ID, 멱등 키';
comment on column received_campaign.payload_hash is '정규화 수신 본문 SHA-256 소문자 hex';
comment on column received_campaign.campaign_id is 'gateway 캠페인 ID, 논리 참조';
comment on column received_campaign.ecu_id is '조치 대상 ECU';
comment on column received_campaign.lot_no is '조치 대상 부품 LOT';
comment on column received_campaign.vehicle_count is '수신 대상 차량 수';
comment on column received_campaign.received_at is '최초 수신 시각';
comment on table received_campaign_vehicle is '조치 대상 수신 차량';
comment on column received_campaign_vehicle.event_id is '수신 캠페인 이벤트 ID';
comment on column received_campaign_vehicle.vin is '조치 대상 차대번호';
comment on column received_campaign_vehicle.serial_no is '대상 부품 시리얼';
