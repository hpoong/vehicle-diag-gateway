create table outbox_event (
    id               bigserial    primary key,
    event_id         uuid         not null unique default gen_random_uuid(), -- 수신 서버 이벤트 ID
    result_id        bigint       not null unique references diagnostic_result(id),
    payload          jsonb        not null,
    status           varchar(10)  not null default 'PENDING'
                     check (status in ('PENDING', 'SENT', 'DEAD')),
    attempt_count    int          not null default 0,   -- 현재 사이클 시도 수 (선점 시 +1)
    cycle_no         int          not null default 1,   -- 수동 재처리마다 +1
    next_attempt_at  timestamptz  not null default now(),
    claim_token      uuid,                              -- 선점마다 새로 발급
    locked_by        varchar(64),                       -- 표시용 인스턴스 ID
    locked_until     timestamptz,
    last_outcome     varchar(24),
    last_http_status int,
    last_error       varchar(1000),
    dead_reason      varchar(20)  check (dead_reason in ('NON_RETRYABLE', 'MAX_ATTEMPTS')),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    sent_at          timestamptz,
    dead_at          timestamptz
);

-- 선점 대상만 담는 부분 인덱스: SENT가 쌓여도 선점 쿼리 비용이 늘지 않는다
create index ix_outbox_pending on outbox_event (next_attempt_at, id) where status = 'PENDING';
create index ix_outbox_dead    on outbox_event (dead_at desc, id)    where status = 'DEAD';
create index ix_outbox_sent    on outbox_event (sent_at desc, id)    where status = 'SENT';

create table delivery_attempt (
    id             bigserial    primary key,
    outbox_id      bigint       not null references outbox_event(id),
    cycle_no       int          not null,
    attempt_no     int          not null,               -- 선점 직후 attempt_count
    claim_token    uuid         not null unique,
    worker_id      varchar(100) not null,
    started_at     timestamptz  not null,
    finished_at    timestamptz,
    outcome        varchar(24)  not null
                   check (outcome in ('STARTED', 'SUCCESS', 'RETRYABLE', 'UNCERTAIN',
                                      'NON_RETRYABLE', 'ABANDONED', 'SKIPPED_MAX_ATTEMPTS')),
    late_outcome   varchar(24),                         -- ABANDONED 후 늦게 끝난 실제 결과
    http_status    int,
    error_code     varchar(40),
    error_message  varchar(1000),
    latency_ms     int,
    constraint uq_attempt_no unique (outbox_id, cycle_no, attempt_no)
);
create index ix_attempt_outbox  on delivery_attempt (outbox_id, id);
create index ix_attempt_started on delivery_attempt (started_at);
