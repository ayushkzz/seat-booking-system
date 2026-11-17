import { useEffect, useRef, useState } from 'react';

/**
 * Calls `task` immediately and then every `intervalMs` while `enabled`. The next call is only
 * scheduled after the previous one finished, so slow responses never pile up.
 */
export function usePolling(task: () => Promise<void>, intervalMs: number, enabled = true): void {
  const latest = useRef(task);
  latest.current = task;

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const run = async () => {
      try {
        await latest.current();
      } catch {
        // each caller handles its own errors; keep polling regardless
      }
      if (!cancelled) timer = setTimeout(run, intervalMs);
    };
    run();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [intervalMs, enabled]);
}

/** The current time, refreshed every second. For countdowns. */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return now;
}

/** State kept in localStorage, so it survives a page reload. */
export function useStoredState(key: string, initial: () => string): [string, (value: string) => void] {
  const [value, setValue] = useState(() => {
    try {
      const stored = localStorage.getItem(key);
      if (stored) return stored;
    } catch {
      // storage can be unavailable (private windows); fall back to memory
    }
    return initial();
  });
  useEffect(() => {
    try {
      localStorage.setItem(key, value);
    } catch {
      // ignore
    }
  }, [key, value]);
  return [value, setValue];
}
