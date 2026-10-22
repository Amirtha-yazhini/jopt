import type { ReactNode } from 'react';
import type { Tone } from './vocabulary';

interface IconButtonProps {
  label: string;
  onClick: () => void;
  children: ReactNode;
  disabled?: boolean;
  pressed?: boolean;
  shortcut?: string;
  variant?: 'ghost' | 'solid';
}

/** Icon-only button; {@code label} is its accessible name and tooltip. */
export function IconButton({ label, onClick, children, disabled, pressed, shortcut, variant = 'ghost' }: IconButtonProps) {
  const title = shortcut ? `${label} (${shortcut})` : label;
  return (
    <button
      type="button"
      className={`icon-btn icon-btn--${variant}`}
      aria-label={label}
      aria-pressed={pressed}
      title={title}
      // aria-disabled keeps focus on the button when it becomes unavailable (disabled would drop it to <body>).
      aria-disabled={disabled || undefined}
      onClick={disabled ? undefined : onClick}
    >
      {children}
    </button>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="kbd">{children}</kbd>;
}

export function ToneBadge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}

interface SegmentedProps<T extends string> {
  label: string;
  value: T;
  options: readonly { value: T; label: string; disabled?: boolean }[];
  onChange: (value: T) => void;
}

/** A small radio group styled as a segmented control. */
export function Segmented<T extends string>({ label, value, options, onChange }: SegmentedProps<T>) {
  return (
    <div className="segmented" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          className="segmented__item"
          disabled={o.disabled}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);
export const MOD_KEY = IS_MAC ? '⌘' : 'Ctrl';
