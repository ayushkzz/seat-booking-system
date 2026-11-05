create table shows (
    id         bigserial primary key,
    title      varchar(200) not null,
    venue      varchar(200) not null,
    starts_at  timestamptz  not null
);

create table seats (
    id           bigserial primary key,
    show_id      bigint      not null references shows (id),
    label        varchar(10) not null,
    price_cents  bigint      not null check (price_cents >= 0),
    status       varchar(16) not null default 'AVAILABLE',
    version      bigint      not null default 0,
    unique (show_id, label)
);
create index idx_seats_show on seats (show_id);

create table bookings (
    id               uuid primary key,
    show_id          bigint       not null references shows (id),
    user_id          varchar(100) not null,
    idempotency_key  varchar(100) not null,
    status           varchar(20)  not null,
    amount_cents     bigint       not null,
    created_at       timestamptz  not null,
    expires_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    -- a retried request (same user + key) can never create a second booking
    unique (user_id, idempotency_key)
);
create index idx_bookings_show on bookings (show_id);
create index idx_bookings_pending_expiry on bookings (expires_at) where status = 'PENDING_PAYMENT';

create table booking_seats (
    booking_id  uuid   not null references bookings (id),
    seat_id     bigint not null references seats (id),
    primary key (booking_id, seat_id)
);

-- Transactional outbox: events are written in the same transaction as the booking
-- and relayed to Kafka afterwards, so the database and the event stream never disagree.
create table outbox_events (
    id            uuid primary key,
    topic         varchar(100) not null,
    message_key   varchar(100) not null,
    event_type    varchar(100) not null,
    payload       text         not null,
    created_at    timestamptz  not null,
    published_at  timestamptz
);
create index idx_outbox_unpublished on outbox_events (created_at) where published_at is null;
