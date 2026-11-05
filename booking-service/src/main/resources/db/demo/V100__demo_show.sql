-- Demo data: one show with 10 rows (A-J) x 20 seats. Rows A-C are premium.
insert into shows (title, venue, starts_at)
values ('Demo Night', 'Main Hall', now() + interval '30 days');

insert into seats (show_id, label, price_cents)
select (select id from shows where title = 'Demo Night'),
       chr(64 + r) || c,
       case when r <= 3 then 45000 else 25000 end
from generate_series(1, 10) as r,
     generate_series(1, 20) as c;
