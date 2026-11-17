import type { QueueTicket } from './api';

type Props = {
  ticket: QueueTicket | null;
  capacity: number;
  onLeave: () => void;
};

/** Shown while the user is in the queue for a high-demand show. */
export function WaitingRoom({ ticket, capacity, onLeave }: Props) {
  const position = ticket?.position ?? null;
  return (
    <section className="waiting" aria-live="polite">
      <div className="waiting-ring" aria-hidden="true">
        <span>{position ?? '…'}</span>
      </div>
      <h2>{position === null ? 'Joining the queue…' : position === 1 ? 'You are next in line' : `You are number ${position} in line`}</h2>
      <p className="muted">
        This show is in high demand, so only {capacity} {capacity === 1 ? 'person' : 'people'} can choose seats at a
        time. You will be let in automatically. Keep this page open: closing it gives up your place.
      </p>
      {position !== null && position > 1 && (
        <p className="waiting-ahead">
          <strong>{position - 1}</strong> {position - 1 === 1 ? 'person is' : 'people are'} ahead of you
        </p>
      )}
      <button type="button" className="button button-quiet" onClick={onLeave}>
        Leave the queue
      </button>
    </section>
  );
}
