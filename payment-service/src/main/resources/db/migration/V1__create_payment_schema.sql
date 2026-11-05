create table payments (
    id              uuid primary key,
    -- one charge per booking, however many times booking.created is delivered
    booking_id      uuid         not null unique,
    amount_cents    bigint       not null,
    status          varchar(16)  not null,
    failure_reason  varchar(200),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);
