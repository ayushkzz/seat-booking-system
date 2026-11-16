// k6 load test: people browsing seat maps while others book, plus a rush for one seat.
//
//   ./scripts/load-test.sh            (starts the stack, runs this, checks for double bookings)
//
// Tunables (environment variables): BASE_URL, BROWSE_RATE, BOOK_RATE, DURATION, HOT_SEAT_USERS, SHOWS

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// The test talks to booking-service directly: the gateway would rate-limit a single load generator.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BROWSE_RATE = Number(__ENV.BROWSE_RATE || 400);
const BOOK_RATE = Number(__ENV.BOOK_RATE || 100);
const DURATION = __ENV.DURATION || '60s';
const HOT_SEAT_USERS = Number(__ENV.HOT_SEAT_USERS || 300);
const SHOWS = Number(__ENV.SHOWS || 30);

const bookingsCreated = new Counter('bookings_created');
const seatConflicts = new Counter('seat_conflicts');
const hotSeatWinners = new Counter('hot_seat_winners');
const serverErrors = new Rate('server_errors');

// 409 (seat already taken) is a correct answer under contention, not a failure.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate',
      exec: 'browse',
      rate: BROWSE_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 1000,
    },
    book: {
      executor: 'constant-arrival-rate',
      exec: 'book',
      rate: BOOK_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 1000,
    },
    // Halfway through, a crowd tries to book the same seat at the same moment.
    hot_seat: {
      executor: 'per-vu-iterations',
      exec: 'hotSeat',
      vus: HOT_SEAT_USERS,
      iterations: 1,
      startTime: '30s',
      maxDuration: '30s',
    },
  },
  thresholds: {
    server_errors: ['rate==0'],
    hot_seat_winners: ['count==1'],
    dropped_iterations: ['count==0'],
    'http_req_duration{scenario:browse}': ['p(95)<100'],
    'http_req_duration{scenario:book}': ['p(95)<250'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function createShow(title) {
  const res = http.post(`${BASE_URL}/api/shows`, JSON.stringify({
    title, venue: 'Load Test Arena', startsAt: '2099-01-01T19:30:00Z', rows: 26, seatsPerRow: 50, priceCents: 25000,
  }), { headers: JSON_HEADERS });
  if (res.status !== 201) {
    throw new Error(`could not create show: ${res.status} ${res.body}`);
  }
  const showId = res.json('id');
  const seats = http.get(`${BASE_URL}/api/shows/${showId}/seats`).json('seats');
  return { showId, firstSeat: seats[0].id, seatCount: seats.length };
}

export function setup() {
  const shows = [];
  for (let i = 0; i < SHOWS; i++) {
    shows.push(createShow(`Load test show ${i + 1}`));
  }
  return { shows, hot: createShow('Hot seat show') };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function browse(data) {
  // the body is not needed, so don't keep it: a seat map is ~70 KB
  const res = http.get(`${BASE_URL}/api/shows/${pick(data.shows).showId}/seats`, { responseType: 'none' });
  serverErrors.add(res.status >= 500);
  check(res, { 'seat map 200': (r) => r.status === 200 });
}

function postBooking(showId, seatIds, tag) {
  const user = `vu${__VU}-${__ITER}-${tag}`;
  return http.post(`${BASE_URL}/api/shows/${showId}/bookings`, JSON.stringify({ userId: user, seatIds }), {
    headers: Object.assign({ 'Idempotency-Key': `${user}-${Date.now()}` }, JSON_HEADERS),
  });
}

export function book(data) {
  const show = pick(data.shows);
  const first = show.firstSeat + Math.floor(Math.random() * (show.seatCount - 1));
  const seatIds = Math.random() < 0.5 ? [first] : [first, first + 1]; // one seat or two neighbours
  const res = postBooking(show.showId, seatIds, 'book');
  serverErrors.add(res.status >= 500);
  if (res.status === 201) bookingsCreated.add(1);
  if (res.status === 409) seatConflicts.add(1);
  check(res, { 'booking 201 or 409': (r) => r.status === 201 || r.status === 409 });
}

export function hotSeat(data) {
  const res = postBooking(data.hot.showId, [data.hot.firstSeat], 'hot');
  serverErrors.add(res.status >= 500);
  if (res.status === 201) hotSeatWinners.add(1);
  check(res, { 'hot seat 201 or 409': (r) => r.status === 201 || r.status === 409 });
}
