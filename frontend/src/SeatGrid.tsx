import type { Seat } from './api';
import { money } from './format';

type Props = {
  seats: Seat[];
  selected: ReadonlySet<number>;
  /** False while the user is waiting or a booking is in progress. */
  enabled: boolean;
  onToggle: (seat: Seat) => void;
};

/** Seat labels look like "C12": row letters followed by the seat number. */
function rowOf(label: string): string {
  return label.replace(/\d+$/, '');
}

export function SeatGrid({ seats, selected, enabled, onToggle }: Props) {
  const rows = new Map<string, Seat[]>();
  for (const seat of seats) {
    const row = rowOf(seat.label);
    rows.set(row, [...(rows.get(row) ?? []), seat]);
  }

  return (
    <div className={`seat-grid${enabled ? '' : ' seat-grid-disabled'}`}>
      <div className="stage">Stage</div>
      {[...rows.entries()].map(([row, rowSeats]) => (
        <div className="seat-row" key={row}>
          <span className="row-label">{row}</span>
          {rowSeats.map((seat) => {
            const isSelected = selected.has(seat.id);
            const state = isSelected ? 'selected' : seat.status.toLowerCase();
            const taken = seat.status !== 'AVAILABLE' && !isSelected;
            return (
              <button
                key={seat.id}
                type="button"
                className={`seat seat-${state}`}
                disabled={!enabled || taken}
                aria-pressed={isSelected}
                aria-label={`Seat ${seat.label}, ${money(seat.priceCents)}, ${isSelected ? 'selected' : seat.status.toLowerCase()}`}
                title={`${seat.label} · ${money(seat.priceCents)}`}
                onClick={() => onToggle(seat)}
              >
                {seat.label.slice(row.length)}
              </button>
            );
          })}
          <span className="row-label">{row}</span>
        </div>
      ))}
      <ul className="legend">
        <li><span className="seat seat-available" /> Available</li>
        <li><span className="seat seat-selected" /> Your selection</li>
        <li><span className="seat seat-held" /> Being booked</li>
        <li><span className="seat seat-booked" /> Sold</li>
      </ul>
    </div>
  );
}
