create table diagnostic_result (
    id            bigserial    primary key,
    device_id     varchar(50)  not null,
    event_id      varchar(64)  not null,                 -- 단말이 만든 결과 ID
    payload_hash  char(64)     not null,                 -- §4 정규화 SHA-256
    brand         varchar(20)  not null,
    vin           varchar(17)  not null,
    session_id    varchar(64)  not null,
    occurred_at   timestamptz  not null,                 -- 단말 진단 시각
    received_at   timestamptz  not null default now(),   -- gateway 수신 시각
    policy_code   varchar(30)  not null,                 -- 적용 정책과 버전
    raw_payload   jsonb        not null,                 -- 단말 원본(파싱 결과)
    constraint uq_result_device_event unique (device_id, event_id)
);
create index ix_result_vin_occurred on diagnostic_result (vin, occurred_at);

create table ecu_observation (
    id          bigserial   primary key,
    result_id   bigint      not null references diagnostic_result(id),
    ecu_id      varchar(30) not null,
    sw_version  varchar(30),
    dtc_codes   text[]      not null,
    constraint uq_observation_result_ecu unique (result_id, ecu_id),
    constraint ck_observation_dtc_count check (cardinality(dtc_codes) <= 50)
);

create table vehicle_ecu_latest (
    vin          varchar(17) not null,
    ecu_id       varchar(30) not null,
    result_id    bigint      not null references diagnostic_result(id),
    occurred_at  timestamptz not null,
    dtc_codes    text[]      not null,
    updated_at   timestamptz not null default now(),
    primary key (vin, ecu_id)
);
