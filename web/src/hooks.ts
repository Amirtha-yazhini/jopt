import { useEffect, useState, type Dispatch } from 'react';
import type { Action } from './state/session';

/** Base delay between replayed steps at 1× speed. */
export const STEP_INTERVAL_MS = 1100;

/** Advances the replay while {@code playing}; the timer is cleared on pause, speed change and unmount. */
export function usePlayback(playing: boolean, speed: number, dispatch: Dispatch<Action>): void {
  useEffect(() => {
    if (!playing) return;
    const id = window.setInterval(() => dispatch({ type: 'tick' }), STEP_INTERVAL_MS / speed);
    return () => window.clearInterval(id);
  }, [playing, speed, dispatch]);
}

/** True when the event comes from somewhere that needs the keys itself (editors, form fields, dialogs). */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  // Widgets with their own arrow/space keys: editors, dialogs, resize handles, tabs, radios, sliders.
  if (target.closest('.monaco-editor, dialog, [role=separator], [role=tab], [role=radio], [role=slider], [data-panel-resize-handle-id]')) return true;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

type Shortcuts = {
  onRun: () => void;
  onHelp: () => void;
  dispatch: Dispatch<Action>;
  enabled: boolean;
};

export function useGlobalShortcuts({ onRun, onHelp, dispatch, enabled }: Shortcuts): void {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        // Inside Monaco the editor's own command handles it.
        if (!(e.target instanceof HTMLElement && e.target.closest('.monaco-editor'))) {
          e.preventDefault();
          onRun();
        }
        return;
      }
      if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey || isTypingTarget(e.target)) return;
      if (e.key === '?') {
        e.preventDefault();
        onHelp();
        return;
      }
      if (!enabled) return;
      const map: Record<string, Action> = {
        ArrowRight: { type: 'step/next' },
        ArrowLeft: { type: 'step/prev' },
        Home: { type: 'step/first' },
        End: { type: 'step/last' },
      };
      const action = map[e.key];
      if (action) {
        e.preventDefault();
        dispatch(action);
      } else if (e.key === ' ' && !(e.target instanceof HTMLButtonElement)) {
        e.preventDefault();
        dispatch({ type: 'play/toggle' });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onRun, onHelp, dispatch, enabled]);
}

export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches);
  useEffect(() => {
    const mql = window.matchMedia(query);
    const onChange = () => setMatches(mql.matches);
    onChange();
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, [query]);
  return matches;
}
