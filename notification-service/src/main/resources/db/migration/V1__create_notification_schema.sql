create table notifications (
    id          uuid primary key,
    -- the event that caused this notification; unique so a redelivered event never notifies twice
    event_id    uuid          not null unique,
    booking_id  uuid          not null,
    user_id     varchar(100)  not null,
    type        varchar(30)   not null,
    message     varchar(500)  not null,
    created_at  timestamptz   not null
);
create index idx_notifications_user on notifications (user_id, created_at desc);
