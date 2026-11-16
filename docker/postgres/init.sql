-- One database per service: the services never share tables.
-- The *_test databases are only used when running the tests with ITEST_LOCAL=true.
create role booking login password 'booking';
create database bookings owner booking;
create database bookings_test owner booking;

create role payment login password 'payment';
create database payments owner payment;
create database payments_test owner payment;

create role notification login password 'notification';
create database notifications owner notification;
create database notifications_test owner notification;
