import { useEffect, useRef, useState } from 'react';
import { api, type Notification } from './api';
import { timeAgo } from './format';
import { useNow, usePolling, useStoredState } from './hooks';

/** Bell with the user's notifications, as produced by notification-service. */
export function Notifications({ user }: { user: string }) {
  const [items, setItems] = useState<Notification[]>([]);
  const [open, setOpen] = useState(false);
  const [seenUntil, setSeenUntil] = useStoredState(`seat-booking-seen:${user}`, () => new Date(0).toISOString());
  const panel = useRef<HTMLDivElement>(null);
  const now = useNow();

  useEffect(() => setItems([]), [user]);

  usePolling(async () => {
    setItems(await api.notifications(user));
  }, 4000);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!panel.current?.contains(e.target as Node)) setOpen(false);
    };
    const closeOnNavigation = () => setOpen(false);
    document.addEventListener('mousedown', close);
    window.addEventListener('hashchange', closeOnNavigation);
    return () => {
      document.removeEventListener('mousedown', close);
      window.removeEventListener('hashchange', closeOnNavigation);
    };
  }, [open]);

  const unread = items.filter((n) => n.createdAt > seenUntil).length;

  const toggle = () => {
    if (!open && items.length > 0) setSeenUntil(items[0].createdAt);
    setOpen(!open);
  };

  return (
    <div className="notifications" ref={panel}>
      <button type="button" className="bell" onClick={toggle} aria-label={`Notifications, ${unread} unread`} aria-expanded={open}>
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
          <path d="M6 9a6 6 0 1 1 12 0c0 5 2 6 2 6H4s2-1 2-6m4 9a2 2 0 0 0 4 0" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        {unread > 0 && <span className="bell-count">{unread}</span>}
      </button>
      {open && (
        <div className="notifications-panel" role="dialog" aria-label="Notifications">
          <h3>Notifications</h3>
          {items.length === 0 && <p className="muted">Nothing yet. Notifications appear here when a booking is confirmed, cancelled or expires.</p>}
          <ul>
            {items.map((n) => (
              <li key={n.id} className={`note note-${n.type === 'BOOKING_CONFIRMED' ? 'ok' : 'bad'}`}>
                <p>{n.message}</p>
                <time dateTime={n.createdAt}>{timeAgo(n.createdAt, now)}</time>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
