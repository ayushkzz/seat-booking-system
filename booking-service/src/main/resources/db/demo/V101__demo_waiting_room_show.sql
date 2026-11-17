-- Demo data: a high-demand show that lets only 3 people choose seats at a time.
insert into shows (title, venue, starts_at, waiting_room_capacity)
values ('Stadium Tour: Opening Night', 'City Arena', now() + interval '45 days', 3);

insert into seats (show_id, label, price_cents)
select (select id from shows where title = 'Stadium Tour: Opening Night'),
       chr(64 + r) || c,
       case when r <= 2 then 120000 when r <= 5 then 75000 else 45000 end
from generate_series(1, 8) as r,
     generate_series(1, 16) as c;
