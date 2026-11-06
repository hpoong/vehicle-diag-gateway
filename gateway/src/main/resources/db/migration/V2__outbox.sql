-- gateway V2 전송 (outbox_event, delivery_attempt) — PostgreSQL 16

-- 3.4 outbox_event -------------------------------------------------------
create table outbox_event (
    id               bigserial    primary key,
    event_id         uuid         not null,
    result_id        bigint       not null,
    payload          jsonb        not null,
    status           varchar(10)  not null default 'PENDING',
    attempt_count    int          not null default 0,
    cycle_no         int          not null default 1,
    next_attempt_at  timestamptz  not null default now(),
    claim_token      uuid,
    locked_by        varchar(64),
    locked_until     timestamptz,
    last_outcome     varchar(24),
    last_http_status int,
    last_error       varchar(1000),
    dead_reason      varchar(20),
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now(),
    sent_at          timestamptz,
    dead_at          timestamptz,
    constraint uq_outbox_event_id      unique (event_id),
    constraint uq_outbox_result        unique (result_id),
    constraint fk_outbox_result        foreign key (result_id) references diagnostic_result (id),
    constraint ck_outbox_status        check (status in ('PENDING', 'SENT', 'DEAD')),
    constraint ck_outbox_dead_reason   check (dead_reason in ('NON_RETRYABLE', 'MAX_ATTEMPTS')),
    constraint ck_outbox_last_outcome  check (last_outcome in ('SUCCESS', 'RETRYABLE', 'UNCERTAIN',
                                                               'NON_RETRYABLE', 'SKIPPED_MAX_ATTEMPTS')),
    constraint ck_outbox_counts        check (attempt_count >= 0 and cycle_no >= 1),
    constraint ck_outbox_payload_event check (payload ->> 'eventId' = event_id::text),
    constraint ck_outbox_sent          check ((status = 'SENT') = (sent_at is not null)),
    constraint ck_outbox_dead          check ((status = 'DEAD' and dead_reason is not null and dead_at is not null)
                                           or (status <> 'DEAD' and dead_reason is null and dead_at is null)),
    constraint ck_outbox_claim         check ((claim_token is null and locked_by is null and locked_until is null)
                                           or (status = 'PENDING' and claim_token is not null
                                               and locked_by is not null and locked_until is not null))
);

-- 선점 대상만 담는 부분 인덱스: SENT가 쌓여도 선점 쿼리 비용이 늘지 않는다 (02 §4)
create index ix_outbox_pending on outbox_event (next_attempt_at, id) where status = 'PENDING';
-- 운영 목록 정렬 (04 §2.3)
create index ix_outbox_dead    on outbox_event (dead_at desc, id)    where status = 'DEAD';
create index ix_outbox_sent    on outbox_event (sent_at desc, id)    where status = 'SENT';

comment on table  outbox_event                  is '진단 결과 1건당 receiver 전송 이벤트 1건. 상태 PENDING → SENT/DEAD, DEAD → PENDING은 수동 재처리만';
comment on column outbox_event.id               is 'outbox ID';
comment on column outbox_event.event_id         is 'receiver 이벤트 ID. 앱이 생성하며 payload.eventId와 같아야 한다';
comment on column outbox_event.result_id        is '원본 진단 결과 ID(1:1)';
comment on column outbox_event.payload          is 'receiver 전송 본문. 수신 시 1회 생성하고 재전송 시 다시 만들지 않는다';
comment on column outbox_event.status           is '전송 상태: PENDING / SENT / DEAD';
comment on column outbox_event.attempt_count    is '현재 사이클의 시도 수. 선점 시 먼저 +1, 수동 재처리 시 0';
comment on column outbox_event.cycle_no         is '수동 재처리 사이클 번호. 재처리마다 +1';
comment on column outbox_event.next_attempt_at  is '다음 선점 가능 시각(백오프 반영)';
comment on column outbox_event.claim_token      is '현재 선점 토큰. 선점마다 새로 발급하며 결과 기록은 일치할 때만 허용';
comment on column outbox_event.locked_by        is '선점한 gateway 인스턴스 ID(표시용)';
comment on column outbox_event.locked_until     is '선점(lease) 만료 시각';
comment on column outbox_event.last_outcome     is '마지막 시도 결과: SUCCESS / RETRYABLE / UNCERTAIN / NON_RETRYABLE / SKIPPED_MAX_ATTEMPTS';
comment on column outbox_event.last_http_status is '마지막 시도의 HTTP 상태 코드';
comment on column outbox_event.last_error       is '마지막 시도의 오류 요약';
comment on column outbox_event.dead_reason      is 'DEAD 사유: NON_RETRYABLE / MAX_ATTEMPTS. DEAD일 때만 값이 있다';
comment on column outbox_event.created_at       is '생성(수신 트랜잭션) 시각';
comment on column outbox_event.updated_at       is '마지막 변경 시각';
comment on column outbox_event.sent_at          is 'SENT 확정 시각. SENT일 때만 값이 있다';
comment on column outbox_event.dead_at          is 'DEAD 확정 시각. DEAD일 때만 값이 있다';

-- 3.5 delivery_attempt ---------------------------------------------------
create table delivery_attempt (
    id             bigserial    primary key,
    outbox_id      bigint       not null,
    cycle_no       int          not null,
    attempt_no     int          not null,
    claim_token    uuid         not null,
    worker_id      varchar(100) not null,
    started_at     timestamptz  not null,
    finished_at    timestamptz,
    outcome        varchar(24)  not null,
    late_outcome   varchar(24),
    http_status    int,
    error_code     varchar(40),
    error_message  varchar(1000),
    latency_ms     int,
    constraint fk_attempt_outbox       foreign key (outbox_id) references outbox_event (id),
    constraint uq_attempt_claim_token  unique (claim_token),
    constraint uq_attempt_no           unique (outbox_id, cycle_no, attempt_no),
    constraint ck_attempt_outcome      check (outcome in ('STARTED', 'SUCCESS', 'RETRYABLE', 'UNCERTAIN',
                                                          'NON_RETRYABLE', 'ABANDONED', 'SKIPPED_MAX_ATTEMPTS')),
    constraint ck_attempt_late_outcome check (late_outcome is null
                                           or (outcome = 'ABANDONED'
                                               and late_outcome in ('SUCCESS', 'RETRYABLE', 'UNCERTAIN', 'NON_RETRYABLE'))),
    constraint ck_attempt_finished     check ((outcome = 'STARTED') = (finished_at is null)),
    constraint ck_attempt_numbers      check (cycle_no >= 1 and attempt_no >= 1 and (latency_ms is null or latency_ms >= 0))
);

-- 시도 이력 조회(04 §2.4), ABANDONED 정리 보조
create index ix_attempt_outbox on delivery_attempt (outbox_id, id);
-- outbox당 진행 중(STARTED) 시도는 최대 1개 (03 I-010)
create unique index uq_attempt_outbox_started on delivery_attempt (outbox_id) where outcome = 'STARTED';

comment on table  delivery_attempt               is '선점 1회당 전송 시도 1행. 수정은 결과 기록(outcome·finished_at 등)과 late_outcome만';
comment on column delivery_attempt.id            is '시도 ID';
comment on column delivery_attempt.outbox_id     is '대상 outbox ID';
comment on column delivery_attempt.cycle_no      is '선점 시점의 사이클 번호';
comment on column delivery_attempt.attempt_no    is '선점 직후 attempt_count(사이클 안 시도 번호)';
comment on column delivery_attempt.claim_token   is '이 시도의 선점 토큰. 결과 기록 시 시도 행을 찾는 키';
comment on column delivery_attempt.worker_id     is '전송기 식별자(인스턴스 ID + 스레드 이름)';
comment on column delivery_attempt.started_at    is '선점(시도 시작) 시각';
comment on column delivery_attempt.finished_at   is '시도 종료 시각. STARTED일 때만 null';
comment on column delivery_attempt.outcome       is '시도 결과: STARTED / SUCCESS / RETRYABLE / UNCERTAIN / NON_RETRYABLE / ABANDONED / SKIPPED_MAX_ATTEMPTS';
comment on column delivery_attempt.late_outcome  is 'lease 만료로 ABANDONED된 시도가 늦게 끝났을 때의 실제 결과';
comment on column delivery_attempt.http_status   is 'HTTP 응답 상태 코드';
comment on column delivery_attempt.error_code    is '분류 오류 코드(HTTP_5xx, CONNECT_FAILED, READ_TIMEOUT 등)';
comment on column delivery_attempt.error_message is '오류 메시지 요약';
comment on column delivery_attempt.latency_ms    is '전송 소요 시간(ms)';
