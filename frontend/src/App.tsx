import { useEffect, useState } from 'react';
import { Notifications } from './Notifications';
import { ShowList } from './ShowList';
import { ShowPage } from './ShowPage';
import { useStoredState } from './hooks';

/** The show id in the address bar (#/shows/3), or null on the home page. */
function showIdFromHash(): number | null {
  const match = /^#\/shows\/(\d+)$/.exec(window.location.hash);
  return match ? Number(match[1]) : null;
}

export function App() {
  const [showId, setShowId] = useState(showIdFromHash);
  // There are no accounts: the name typed here is the userId sent to the API.
  const [user, setUser] = useStoredState('seat-booking-user', () => `guest-${Math.floor(1000 + Math.random() * 9000)}`);
  const [draftName, setDraftName] = useState(user);

  useEffect(() => {
    const onHashChange = () => setShowId(showIdFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const commitName = () => {
    const name = draftName.trim().replace(/\s+/g, '-').slice(0, 40);
    if (name) setUser(name);
    setDraftName(name || user);
  };

  return (
    <>
      <header className="topbar">
        <a className="brand" href="#/">
          <span className="brand-mark" aria-hidden="true" />
          Seat Booking
        </a>
        <div className="topbar-right">
          <label className="who">
            <span>Booking as</span>
            <input
              value={draftName}
              onChange={(e) => setDraftName(e.target.value)}
              onBlur={commitName}
              onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
              spellCheck={false}
              aria-label="Your name"
            />
          </label>
          <Notifications user={user} />
        </div>
      </header>
      <main>
        {/* key: switching show or user starts the page from scratch (new queue ticket, no selection) */}
        {showId === null ? <ShowList /> : <ShowPage key={`${showId}:${user}`} showId={showId} user={user} />}
      </main>
    </>
  );
}
