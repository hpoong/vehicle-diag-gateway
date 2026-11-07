create table received_event (
    event_id uuid primary key,
    payload_hash char(64) not null,
    source_result_id bigint not null,
    vin varchar(17) not null,
    case_count int not null,
    received_at timestamptz not null default now(),
    constraint ck_received_event_case_count check (case_count >= 0),
    constraint ck_received_event_payload_hash check (payload_hash ~ '^[0-9a-f]{64}$')
);

create table quality_case (
    id bigserial primary key,
    event_id uuid not null,
    vin varchar(17) not null,
    ecu_id varchar(30) not null,
    sw_version varchar(30),
    dtc_code varchar(10) not null,
    occurred_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint fk_quality_case_received_event foreign key (event_id) references received_event (event_id),
    constraint uq_case_event_ecu_dtc unique (event_id, ecu_id, dtc_code)
);

create table quality_case_naive (
    id bigserial primary key,
    event_id uuid not null,
    ecu_id varchar(30) not null,
    dtc_code varchar(10) not null,
    created_at timestamptz not null default now()
);

comment on table received_event is '반영된 품질 이벤트와 멱등 판정 정보';
comment on table quality_case is '이벤트의 ECU·DTC별 품질 건';
comment on table quality_case_naive is '멱등 방어 없는 비교 실험용 품질 건';
comment on column received_event.event_id is '게이트웨이 이벤트 UUID, 멱등 키';
comment on column received_event.payload_hash is '정규 JSON UTF-8 SHA-256 소문자 hex';
comment on column received_event.source_result_id is '게이트웨이 진단 결과 ID, 논리 참조';
comment on column received_event.vin is '차대번호';
comment on column received_event.case_count is '반영된 품질 건수';
comment on column received_event.received_at is '수신 반영 시각';
comment on column quality_case.id is '품질 건 ID';
comment on column quality_case.event_id is '반영된 수신 이벤트 FK';
comment on column quality_case.vin is '차대번호';
comment on column quality_case.ecu_id is 'ECU 식별자';
comment on column quality_case.sw_version is 'ECU 소프트웨어 버전';
comment on column quality_case.dtc_code is '고장 코드';
comment on column quality_case.occurred_at is '진단 발생 시각';
comment on column quality_case.created_at is '품질 건 생성 시각';
comment on column quality_case_naive.id is '비교 품질 건 ID';
comment on column quality_case_naive.event_id is '이벤트 UUID, FK·고유 제약 없음';
comment on column quality_case_naive.ecu_id is 'ECU 식별자';
comment on column quality_case_naive.dtc_code is '고장 코드';
comment on column quality_case_naive.created_at is '생성 시각';
