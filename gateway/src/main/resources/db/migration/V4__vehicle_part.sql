-- gateway V4 부품 장착 이력(OEM 생산 이력 사본) — PostgreSQL 16
create table vehicle_part (
    id            bigserial    primary key,
    vin           varchar(17)  not null,
    ecu_id        varchar(30)  not null,
    part_no       varchar(30)  not null,
    serial_no     varchar(40)  not null,
    lot_no        varchar(30),
    installed_at  timestamptz  not null,
    removed_at    timestamptz,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    constraint uq_vehicle_part_slot_serial unique (vin, ecu_id, serial_no),
    constraint ck_vehicle_part_period check (removed_at is null or removed_at > installed_at),
    constraint ck_vehicle_part_lot_no check (lot_no is null or char_length(lot_no) >= 1)
);

-- 슬롯당 현재 장착 부품은 1개 (과거 기간 겹침은 막지 않고 추적에서 '이력 모호'로 드러낸다)
create unique index uq_vehicle_part_current on vehicle_part (vin, ecu_id) where removed_at is null;

-- 순추적·장착 차량 수: (ecu_id, lot_no) → 차량
create index ix_vehicle_part_lot on vehicle_part (ecu_id, lot_no) where lot_no is not null;

comment on table  vehicle_part              is 'OEM 생산 이력에서 연계 받은 차량별 부품 장착 이력(사본). (vin, ecu_id, serial_no) 단위 upsert, 마지막 값 우선';
comment on column vehicle_part.vin          is '차대번호(17자)';
comment on column vehicle_part.ecu_id       is '장착 슬롯의 ECU 식별자. ecu_observation.ecu_id와 같은 값 체계';
comment on column vehicle_part.part_no      is '부품 번호';
comment on column vehicle_part.serial_no    is '부품 시리얼. 슬롯 안에서 고유';
comment on column vehicle_part.lot_no       is '부품 생산 LOT. null이면 LOT 미확인이며 추적에서 어떤 LOT에도 넣지 않는다';
comment on column vehicle_part.installed_at is '장착 시각(포함)';
comment on column vehicle_part.removed_at   is '탈거 시각(제외). null이면 현재 장착';
comment on column vehicle_part.created_at   is '최초 반영 시각';
comment on column vehicle_part.updated_at   is '마지막 값 변경 시각(값이 같으면 갱신하지 않음)';
