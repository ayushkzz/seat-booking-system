// Typed client for the gateway API. Every call goes to /api on the same origin.

export type Show = {
  id: number;
  title: string;
  venue: string;
  startsAt: string;
  totalSeats: number;
  /** How many users may choose seats at once; null when the show has no waiting room. */
  waitingRoomCapacity: number | null;
};

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';

export type Seat = { id: number; label: string; status: SeatStatus; priceCents: number };

export type SeatMap = { showId: number; summary: Partial<Record<SeatStatus, number>>; seats: Seat[] };

export type BookingStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED';

export type Booking = {
  id: string;
  showId: number;
  userId: string;
  status: BookingStatus;
  seatIds: number[];
  amountCents: number;
  createdAt: string;
  expiresAt: string;
};

export type QueueTicket = {
  ticketId: string;
  status: 'WAITING' | 'ADMITTED';
  /** 1 means next in line; null once admitted. */
  position: number | null;
  /** When an admitted user's turn ends. */
  expiresAt: string | null;
};

export type Notification = {
  id: string;
  bookingId: string;
  type: 'BOOKING_CONFIRMED' | 'BOOKING_CANCELLED' | 'BOOKING_EXPIRED';
  message: string;
  createdAt: string;
};

/** An error response from the API, with the machine-readable code from the problem body. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    message: string,
  ) {
    super(message);
  }
}

const FALLBACK_MESSAGES: Record<number, string> = {
  429: 'You are sending requests too quickly. Wait a moment and try again.',
  502: 'The booking service is not reachable right now.',
  503: 'The booking service is not reachable right now.',
  504: 'The booking service took too long to answer.',
};

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init.headers },
    });
  } catch {
    throw new ApiError(0, null, 'Could not reach the server. Check that the stack is running.');
  }
  const text = await response.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    // not JSON (for example a proxy error page); fall through to the status-based message
  }
  if (!response.ok) {
    const problem = (data ?? {}) as { code?: string; detail?: string };
    throw new ApiError(
      response.status,
      problem.code ?? null,
      problem.detail ?? FALLBACK_MESSAGES[response.status] ?? `Request failed (${response.status}).`,
    );
  }
  return data as T;
}

export const api = {
  shows: () => request<Show[]>('/shows'),
  show: (showId: number) => request<Show>(`/shows/${showId}`),
  seatMap: (showId: number) => request<SeatMap>(`/shows/${showId}/seats`),

  createBooking: (showId: number, userId: string, seatIds: number[], idempotencyKey: string, queueTicket?: string) =>
    request<Booking>(`/shows/${showId}/bookings`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey, ...(queueTicket ? { 'X-Queue-Ticket': queueTicket } : {}) },
      body: JSON.stringify({ userId, seatIds }),
    }),
  booking: (bookingId: string) => request<Booking>(`/bookings/${bookingId}`),

  joinQueue: (showId: number, userId: string) =>
    request<QueueTicket>(`/shows/${showId}/queue`, { method: 'POST', body: JSON.stringify({ userId }) }),
  queueStatus: (showId: number, ticketId: string) => request<QueueTicket>(`/shows/${showId}/queue/${ticketId}`),
  leaveQueue: (showId: number, ticketId: string) =>
    request<void>(`/shows/${showId}/queue/${ticketId}`, { method: 'DELETE', keepalive: true }),

  notifications: (userId: string) => request<Notification[]>(`/users/${encodeURIComponent(userId)}/notifications`),
};
