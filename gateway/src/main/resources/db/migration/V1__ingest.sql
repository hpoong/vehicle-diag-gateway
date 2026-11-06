-- gateway V1 수신 (diagnostic_result, ecu_observation, vehicle_ecu_latest) — PostgreSQL 16

-- 3.1 diagnostic_result --------------------------------------------------
create table diagnostic_result (
    id            bigserial    primary key,
    device_id     varchar(50)  not null,
    event_id      varchar(64)  not null,
    payload_hash  char(64)     not null,
    brand         varchar(20)  not null,
    vin           varchar(17)  not null,
    session_id    varchar(64)  not null,
    occurred_at   timestamptz  not null,
    received_at   timestamptz  not null default now(),
    policy_code   varchar(30)  not null,
    raw_payload   jsonb        not null,
    constraint uq_result_device_event unique (device_id, event_id),
    constraint ck_result_payload_hash check (payload_hash ~ '^[0-9a-f]{64}$')
);

comment on table  diagnostic_result              is '단말 진단 결과 1건. (device_id, event_id)로 멱등 수신하며 저장 후 수정·삭제하지 않는다';
comment on column diagnostic_result.id           is '결과 ID';
comment on column diagnostic_result.device_id    is '진단 단말 ID';
comment on column diagnostic_result.event_id     is '단말이 만든 결과 ID(단말 안에서만 고유)';
comment on column diagnostic_result.payload_hash is '정규화한 요청의 SHA-256 소문자 hex 64자. 재전송(200)·충돌(409) 판정 기준';
comment on column diagnostic_result.brand        is '브랜드 코드';
comment on column diagnostic_result.vin          is '차대번호(17자)';
comment on column diagnostic_result.session_id   is '진단 세션 ID';
comment on column diagnostic_result.occurred_at  is '단말 진단 시각';
comment on column diagnostic_result.received_at  is 'gateway 수신 시각';
comment on column diagnostic_result.policy_code  is '적용한 검증 정책 코드와 버전(예: BRAND_A-v1)';
comment on column diagnostic_result.raw_payload  is '단말 요청 원본(파싱 결과)';

-- 3.2 ecu_observation ----------------------------------------------------
create table ecu_observation (
    id          bigserial   primary key,
    result_id   bigint      not null,
    ecu_id      varchar(30) not null,
    sw_version  varchar(30),
    dtc_codes   text[]      not null,
    constraint fk_observation_result     foreign key (result_id) references diagnostic_result (id),
    constraint uq_observation_result_ecu unique (result_id, ecu_id),
    constraint ck_observation_dtc_count  check (cardinality(dtc_codes) <= 50)
);

comment on table  ecu_observation            is '결과 1건 아래 ECU별 관측. 저장 후 수정·삭제하지 않는다';
comment on column ecu_observation.id         is '관측 ID';
comment on column ecu_observation.result_id  is '소속 진단 결과 ID';
comment on column ecu_observation.ecu_id     is 'ECU 식별자(결과 안에서 고유)';
comment on column ecu_observation.sw_version is 'ECU 소프트웨어 버전(선택, BRAND_A는 앱에서 필수)';
comment on column ecu_observation.dtc_codes  is '고장코드 목록(최대 50). 빈 배열은 고장코드 없음 관측';

-- 3.3 vehicle_ecu_latest -------------------------------------------------
create table vehicle_ecu_latest (
    vin          varchar(17) not null,
    ecu_id       varchar(30) not null,
    result_id    bigint      not null,
    occurred_at  timestamptz not null,
    dtc_codes    text[]      not null,
    updated_at   timestamptz not null default now(),
    primary key (vin, ecu_id),
    constraint fk_latest_result foreign key (result_id) references diagnostic_result (id)
);

comment on table  vehicle_ecu_latest             is '(VIN, ECU)별 최신 관측. (occurred_at, result_id)가 더 클 때만 조건부 UPSERT로 갱신';
comment on column vehicle_ecu_latest.vin         is '차대번호';
comment on column vehicle_ecu_latest.ecu_id      is 'ECU 식별자';
comment on column vehicle_ecu_latest.result_id   is '최신값의 출처 진단 결과 ID';
comment on column vehicle_ecu_latest.occurred_at is '최신값의 진단 시각';
comment on column vehicle_ecu_latest.dtc_codes   is '최신 고장코드 목록';
comment on column vehicle_ecu_latest.updated_at  is '마지막 갱신 시각';
