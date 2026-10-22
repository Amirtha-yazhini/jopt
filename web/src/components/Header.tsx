import { useRef } from 'react';
import { Keyboard, Moon, RotateCcw, Sun } from 'lucide-react';
import { IconButton, Kbd, MOD_KEY } from '../ui/controls';
import type { Tone } from '../ui/vocabulary';

export type Status = { tone: Tone | 'busy'; label: string };

type Props = {
  status: Status;
  experiment: string;
  theme: 'dark' | 'light';
  onToggleTheme: () => void;
  onReset: () => void;
  canReset: boolean;
};

const SHORTCUTS: [string, string][] = [
  [`${MOD_KEY} Enter`, 'Optimize (works inside the editor)'],
  ['→  /  ←', 'Next / previous step'],
  ['Home  /  End', 'First / last step'],
  ['Space', 'Play or pause the replay'],
  ['?', 'Show this list'],
];

export function ShortcutsDialog({ dialogRef }: { dialogRef: React.RefObject<HTMLDialogElement> }) {
  return (
    <dialog ref={dialogRef} id="shortcuts-dialog" className="dialog" aria-labelledby="shortcuts-title">
      <h2 id="shortcuts-title" className="dialog__title">Keyboard shortcuts</h2>
      <p className="small muted">Step shortcuts work when focus is outside the code editors.</p>
      <dl className="shortcuts">
        {SHORTCUTS.map(([k, v]) => (
          <div key={k} className="shortcuts__row">
            <dt><Kbd>{k}</Kbd></dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      <form method="dialog">
        <button type="submit" className="btn">Close</button>
      </form>
    </dialog>
  );
}

export function Header({ status, experiment, theme, onToggleTheme, onReset, canReset }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand__mark" aria-hidden="true">
          <span />
          <span />
          <span />
        </span>
        <span className="brand__name">jopt</span>
        <span className="brand__sub">playground</span>
      </div>
      <p className="topbar__tagline">Write Java, then step through each pass of the bytecode optimizer.</p>
      <span className="topbar__spacer" />
      <span className="topbar__experiment small" title="Current experiment">{experiment}</span>
      <span className={`status status--${status.tone}`} role="status" aria-live="polite">
        <span className="status__dot" aria-hidden="true" />
        {status.label}
      </span>
      <IconButton label="Keyboard shortcuts" shortcut="?" onClick={() => dialog.current?.showModal()}>
        <Keyboard size={16} />
      </IconButton>
      <IconButton label={theme === 'dark' ? 'Light theme' : 'Dark theme'} onClick={onToggleTheme}>
        {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
      </IconButton>
      <IconButton label="Clear results" disabled={!canReset} onClick={onReset}>
        <RotateCcw size={16} />
      </IconButton>
      <ShortcutsDialog dialogRef={dialog} />
    </header>
  );
}
