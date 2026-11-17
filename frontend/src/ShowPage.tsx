import { useEffect, useRef, useState } from 'react';
import { api, ApiError, type Booking, type QueueTicket, type Seat, type SeatMap, type Show } from './api';
import { BookingProgress } from './BookingProgress';
import { countdown, money, showDate } from './format';
import { useNow, usePolling } from './hooks';
import { SeatGrid } from './SeatGrid';
import { WaitingRoom } from './WaitingRoom';

const MAX_SEATS = 6;

type Notice = { tone: 'error' | 'info'; text: string };

/** 'none': not in the queue (yet). 'joined': has a ticket. 'lost': the place or turn is gone. */
type QueueState = 'none' | 'joined' | 'lost';

function newKey(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function ShowPage({ showId, user }: { showId: number; user: string }) {
  const [show, setShow] = useState<Show | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [seatMap, setSeatMap] = useState<SeatMap | null>(null);
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set());
  const [ticket, setTicket] = useState<QueueTicket | null>(null);
  const [queue, setQueue] = useState<QueueState>('none');
  const [booking, setBooking] = useState<Booking | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  // Sent with the booking request. Reused if the same request is retried, so a retry after a
  // network error can never create a second booking; replaced whenever the selection changes.
  const idempotencyKey = useRef<string | null>(null);
  const now = useNow();

  const needsQueue = show !== null && show.waitingRoomCapacity !== null;
  const admitted = ticket?.status === 'ADMITTED';
  const canChoose = show !== null && !booking && (!needsQueue || admitted);

  useEffect(() => {
    api.show(showId).then(setShow, (e: Error) => setLoadError(e.message));
  }, [showId]);

  // The seat map is refreshed all the time so seats taken by other people show up.
  usePolling(async () => {
    const map = await api.seatMap(showId);
    setSeatMap(map);
    if (booking) return;
    const gone = map.seats.filter((s) => selected.has(s.id) && s.status !== 'AVAILABLE');
    if (gone.length > 0) {
      setSelected(new Set([...selected].filter((id) => !gone.some((s) => s.id === id))));
      idempotencyKey.current = null;
      setNotice({ tone: 'info', text: `${gone.map((s) => s.label).join(', ')} was just taken by someone else.` });
    }
  }, 2000);

  // Join the waiting room as soon as we know the show has one.
  useEffect(() => {
    if (!needsQueue || queue !== 'none' || booking) return;
    let cancelled = false;
    api.joinQueue(showId, user).then(
      (joined) => {
        if (cancelled) return;
        setTicket(joined);
        setQueue('joined');
      },
      (e: Error) => !cancelled && setNotice({ tone: 'error', text: e.message }),
    );
    return () => {
      cancelled = true;
    };
  }, [needsQueue, queue, booking, showId, user]);

  // Checking the ticket moves us forward in the queue and tells the server we are still here.
  usePolling(
    async () => {
      if (!ticket) return;
      try {
        setTicket(await api.queueStatus(showId, ticket.ticketId));
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) {
          setTicket(null);
          setQueue('lost');
          setSelected(new Set());
        }
      }
    },
    2000,
    queue === 'joined' && ticket !== null,
  );

  usePolling(
    async () => {
      if (booking) setBooking(await api.booking(booking.id));
    },
    1000,
    booking?.status === 'PENDING_PAYMENT',
  );

  const toggleSeat = (seat: Seat) => {
    const next = new Set(selected);
    if (next.has(seat.id)) {
      next.delete(seat.id);
    } else if (next.size >= MAX_SEATS) {
      setNotice({ tone: 'info', text: `You can book up to ${MAX_SEATS} seats at a time.` });
      return;
    } else {
      next.add(seat.id);
    }
    setSelected(next);
    idempotencyKey.current = null;
    setNotice(null);
  };

  const book = async () => {
    idempotencyKey.current ??= newKey();
    setSubmitting(true);
    setNotice(null);
    try {
      const created = await api.createBooking(showId, user, [...selected], idempotencyKey.current, ticket?.ticketId);
      setBooking(created);
      // booking uses up the turn in the waiting room
      setTicket(null);
      setQueue('none');
    } catch (e) {
      const error = e as ApiError;
      if (error.status === 409 && error.code === 'seat-unavailable') {
        idempotencyKey.current = null;
        setSelected(new Set());
        setNotice({ tone: 'error', text: 'Someone just took one of those seats. Pick again.' });
        api.seatMap(showId).then(setSeatMap, () => undefined);
      } else if (error.status === 403) {
        idempotencyKey.current = null;
        setTicket(null);
        setQueue('lost');
        setSelected(new Set());
      } else {
        // network trouble or rate limiting: keep the key so pressing Book again is a safe retry
        setNotice({ tone: 'error', text: error.message });
      }
    } finally {
      setSubmitting(false);
    }
  };

  const startOver = () => {
    setBooking(null);
    setSelected(new Set());
    idempotencyKey.current = null;
    setNotice(null);
  };

  const leave = () => {
    if (ticket) api.leaveQueue(showId, ticket.ticketId).catch(() => undefined);
    window.location.hash = '#/';
  };

  if (loadError) {
    return (
      <section className="page">
        <p className="banner banner-error">{loadError}</p>
        <a href="#/">Back to all shows</a>
      </section>
    );
  }
  if (!show) {
    return <section className="page"><p className="muted">Loading…</p></section>;
  }

  const seats = seatMap?.seats ?? [];
  const chosen = seats.filter((s) => selected.has(s.id));
  const total = chosen.reduce((sum, s) => sum + s.priceCents, 0);
  const summary = seatMap?.summary ?? {};
  const turnLeft = ticket?.expiresAt ? (new Date(ticket.expiresAt).getTime() - now) / 1000 : null;
  const waiting = needsQueue && !booking && queue !== 'lost' && !admitted;

  return (
    <section className="page">
      <a className="back" href="#/" onClick={(e) => { e.preventDefault(); leave(); }}>← All shows</a>
      <div className="show-head">
        <div>
          <h1>{show.title}</h1>
          <p className="muted">{show.venue} · {showDate(show.startsAt)}</p>
        </div>
        <p className="availability">
          <strong>{summary.AVAILABLE ?? 0}</strong> available
          <span>{summary.HELD ?? 0} being booked</span>
          <span>{summary.BOOKED ?? 0} sold</span>
        </p>
      </div>

      {admitted && !booking && turnLeft !== null && (
        <p className="banner banner-turn">
          <strong>It is your turn.</strong> Choose your seats and book within <b>{countdown(turnLeft)}</b>.
        </p>
      )}
      {queue === 'lost' && !booking && (
        <p className="banner banner-error">
          Your turn ended or you lost your place in the queue.{' '}
          <button type="button" className="link" onClick={() => setQueue('none')}>Join the queue again</button>
        </p>
      )}
      {notice && <p className={`banner banner-${notice.tone}`}>{notice.text}</p>}

      {waiting && <WaitingRoom ticket={ticket} capacity={show.waitingRoomCapacity ?? 1} onLeave={leave} />}

      <div className="layout">
        <SeatGrid seats={seats} selected={selected} enabled={canChoose && !submitting} onToggle={toggleSeat} />
        <aside className="side">
          {booking ? (
            <BookingProgress booking={booking} seats={seats} onDone={startOver} />
          ) : (
            <section className="selection">
              <h2>Your selection</h2>
              {chosen.length === 0 ? (
                <p className="muted">
                  {canChoose ? 'Click seats on the map to select them.' : 'You can choose seats once it is your turn.'}
                </p>
              ) : (
                <ul>
                  {chosen.map((s) => (
                    <li key={s.id}><span>Seat {s.label}</span><span>{money(s.priceCents)}</span></li>
                  ))}
                </ul>
              )}
              <p className="total"><span>Total</span><strong>{money(total)}</strong></p>
              <button type="button" className="button" disabled={!canChoose || chosen.length === 0 || submitting} onClick={book}>
                {submitting ? 'Booking…' : chosen.length === 0 ? 'Book seats' : `Book ${chosen.length} ${chosen.length === 1 ? 'seat' : 'seats'}`}
              </button>
              <p className="fine">Seats are held for 5 minutes while the payment is processed. Payments are simulated.</p>
            </section>
          )}
        </aside>
      </div>
    </section>
  );
}
