import type { Booking, Seat } from './api';
import { countdown, money } from './format';
import { useNow } from './hooks';

type Props = {
  booking: Booking;
  seats: Seat[];
  onDone: () => void;
};

const OUTCOME = {
  CONFIRMED: { title: 'Booking confirmed', tone: 'ok', text: 'Payment received. The seats are yours.' },
  CANCELLED: { title: 'Payment declined', tone: 'bad', text: 'You were not charged and the seats were released.' },
  EXPIRED: { title: 'Booking expired', tone: 'bad', text: 'Payment did not finish in time, so the seats were released.' },
} as const;

/** Follows a booking from "seats held" to its final state. */
export function BookingProgress({ booking, seats, onDone }: Props) {
  const now = useNow();
  const labels = seats.filter((s) => booking.seatIds.includes(s.id)).map((s) => s.label);
  const pending = booking.status === 'PENDING_PAYMENT';
  const outcome = pending ? null : OUTCOME[booking.status as keyof typeof OUTCOME];
  const holdLeft = (new Date(booking.expiresAt).getTime() - now) / 1000;

  return (
    <section className={`progress progress-${outcome?.tone ?? 'pending'}`} aria-live="polite">
      <h2>{outcome?.title ?? 'Processing payment…'}</h2>
      <p className="muted">
        {outcome?.text ?? `Your seats are held for ${countdown(holdLeft)} while the payment goes through.`}
      </p>
      <ol className="steps">
        <li className="step-done">Seats held</li>
        <li className={pending ? 'step-active' : booking.status === 'CONFIRMED' ? 'step-done' : 'step-failed'}>Payment</li>
        <li className={booking.status === 'CONFIRMED' ? 'step-done' : ''}>Confirmed</li>
      </ol>
      <dl className="receipt">
        <div><dt>Seats</dt><dd>{labels.length ? labels.join(', ') : `${booking.seatIds.length} seats`}</dd></div>
        <div><dt>Total</dt><dd>{money(booking.amountCents)}</dd></div>
        <div><dt>Booking</dt><dd className="mono">{booking.id.slice(0, 8)}</dd></div>
      </dl>
      {!pending && (
        <button type="button" className="button" onClick={onDone}>
          {booking.status === 'CONFIRMED' ? 'Book more seats' : 'Try again'}
        </button>
      )}
    </section>
  );
}
