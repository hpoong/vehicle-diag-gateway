-- gateway V3 운영 감사 (ops_action_log) — PostgreSQL 16

-- 3.6 ops_action_log -----------------------------------------------------
create table ops_action_log (
    id                    bigserial    primary key,
    action                varchar(20)  not null,
    outbox_id             bigint       not null,
    operator              varchar(64)  not null,
    reason                varchar(500) not null,
    before_status         varchar(10)  not null,
    after_status          varchar(10)  not null,
    before_attempt_count  int          not null,
    new_cycle_no          int          not null,
    request_id            varchar(64)  not null,
    created_at            timestamptz  not null default now(),
    constraint fk_ops_action_outbox foreign key (outbox_id) references outbox_event (id),
    constraint ck_ops_action        check (action in ('REQUEUE')),
    constraint ck_ops_status        check (before_status in ('PENDING', 'SENT', 'DEAD')
                                       and after_status  in ('PENDING', 'SENT', 'DEAD')),
    constraint ck_ops_counts        check (before_attempt_count >= 0 and new_cycle_no >= 2),
    constraint ck_ops_text          check (char_length(operator) >= 1 and char_length(reason) >= 10)
);

-- outbox별 감사 기록 조회 (테스트 O2·O3·S10)
create index ix_ops_action_outbox on ops_action_log (outbox_id, id);

comment on table  ops_action_log                      is '운영자 수동 작업 감사 기록. 상태 전이와 같은 트랜잭션에서 INSERT';
comment on column ops_action_log.id                   is '로그 ID';
comment on column ops_action_log.action               is '작업 종류: REQUEUE';
comment on column ops_action_log.outbox_id            is '대상 outbox ID';
comment on column ops_action_log.operator             is '작업자(X-Operator, 1~64자)';
comment on column ops_action_log.reason               is '작업 사유(10~500자)';
comment on column ops_action_log.before_status        is '작업 전 상태';
comment on column ops_action_log.after_status         is '작업 후 상태';
comment on column ops_action_log.before_attempt_count is '작업 전 attempt_count';
comment on column ops_action_log.new_cycle_no         is '작업 후 cycle_no';
comment on column ops_action_log.request_id           is '요청 추적 ID(X-Request-Id, 최대 64자)';
comment on column ops_action_log.created_at           is '기록 시각';
