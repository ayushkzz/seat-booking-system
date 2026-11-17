-- How many users may be choosing seats for this show at the same time.
-- Null means the show has no waiting room and anyone can book straight away.
alter table shows add column waiting_room_capacity integer check (waiting_room_capacity > 0);
