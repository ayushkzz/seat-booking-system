-- One database per service: booking-service and payment-service never share tables.
create role booking login password 'booking';
create database bookings owner booking;

create role payment login password 'payment';
create database payments owner payment;
