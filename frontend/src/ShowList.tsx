import { useState } from 'react';
import { api, type Show } from './api';
import { showDate } from './format';
import { usePolling } from './hooks';

export function ShowList() {
  const [shows, setShows] = useState<Show[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  usePolling(async () => {
    try {
      setShows(await api.shows());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, 10_000);

  return (
    <section className="page">
      <h1>Upcoming shows</h1>
      <p className="lead">Pick a show to choose your seats.</p>
      {error && <p className="banner banner-error">{error}</p>}
      {!shows && !error && <p className="muted">Loading shows…</p>}
      {shows?.length === 0 && <p className="muted">No upcoming shows yet.</p>}
      <div className="show-grid">
        {shows?.map((show) => (
          <a key={show.id} className="show-card" href={`#/shows/${show.id}`}>
            {show.waitingRoomCapacity !== null && <span className="tag tag-hot">High demand · waiting room</span>}
            <h2>{show.title}</h2>
            <p className="muted">{show.venue}</p>
            <p className="show-meta">
              <span>{showDate(show.startsAt)}</span>
              <span>{show.totalSeats} seats</span>
            </p>
          </a>
        ))}
      </div>
    </section>
  );
}
