create table ops_action_log (
    id                    bigserial    primary key,
    action                varchar(20)  not null check (action in ('REQUEUE')),
    outbox_id             bigint       not null references outbox_event(id),
    operator              varchar(64)  not null,
    reason                varchar(500) not null,
    before_status         varchar(10)  not null,
    after_status          varchar(10)  not null,
    before_attempt_count  int          not null,
    new_cycle_no          int          not null,
    request_id            varchar(64)  not null,
    created_at            timestamptz  not null default now()
);
create index ix_ops_action_outbox on ops_action_log (outbox_id, id);
