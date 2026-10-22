import { useEffect, useState } from 'react';
import { Check, Copy, Maximize2, Minimize2 } from 'lucide-react';
import type { Meta, MethodTrace, Row } from '../api/types';
import { lineDiffStats, methodLabel, resolveSnapshot, rowsText } from '../state/derived';
import { IconButton, Segmented, ToneBadge } from '../ui/controls';
import { OUTCOMES, passColor, STEP_DESCRIPTIONS, stepTitle } from '../ui/vocabulary';
import { CodeView } from './CodeView';
import { Metrics, Transformations } from './StepDetails';

type View = 'bytecode' | 'ir';
type Compare = 'previous' | 'input';

type Props = {
  method: MethodTrace;
  stepIndex: number;
  meta: Meta;
  theme: 'dark' | 'light';
  linkedSourceLine: number | null;
  onSourceLine: (line: number | null) => void;
};

function rowsFor(view: View, snap: { ir: Row[] | null; bytecode: Row[] | null } | null): Row[] | null {
  if (!snap) return null;
  return view === 'ir' ? snap.ir : snap.bytecode;
}

function CopyButton({ label, text }: { label: string; text: string }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return;
    const t = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(t);
  }, [copied]);
  const copy = () => {
    navigator.clipboard?.writeText(text).then(
      () => setCopied(true),
      () => setCopied(false),
    );
  };
  return (
    <IconButton label={copied ? 'Copied' : label} onClick={copy}>
      {copied ? <Check size={14} /> : <Copy size={14} />}
    </IconButton>
  );
}

export function Inspector({ method, stepIndex, meta, theme, linkedSourceLine, onSourceLine }: Props) {
  const [view, setView] = useState<View>('bytecode');
  const [compare, setCompare] = useState<Compare>('previous');
  const [sideBySide, setSideBySide] = useState(true);
  const [expanded, setExpanded] = useState(false);

  const step = method.steps[stepIndex] ?? method.steps[0]!;
  const current = resolveSnapshot(method.steps, step.index)!;
  const previous = step.index > 0 ? resolveSnapshot(method.steps, step.index - 1) : null;
  const input = method.steps[0]!.snapshot!;
  const built = method.steps[1]?.snapshot ?? null;
  const outcome = OUTCOMES[method.outcome];

  // The javac input has no IR; the IR view starts at the "IR built" step.
  const irAvailable = current.ir !== null;
  const activeView: View = view === 'ir' && !irAvailable ? 'bytecode' : view;
  const baseSnap = compare === 'previous' ? previous : activeView === 'ir' ? built : input;
  const modifiedRows = rowsFor(activeView, current);
  const baseRows = step.index === 0 ? null : rowsFor(activeView, baseSnap);
  const sameAsBase =
    baseSnap === current || (baseRows !== null && modifiedRows !== null && rowsText(baseRows) === rowsText(modifiedRows));
  const stats = modifiedRows && baseRows && !sameAsBase
    ? lineDiffStats(baseRows.map((r) => r.text), modifiedRows.map((r) => r.text))
    : null;

  useEffect(() => {
    if (!expanded) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setExpanded(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [expanded]);

  const title = stepTitle(step, meta.passes);
  const passInfo = meta.passes.find((p) => p.id === step.pass);
  const description = step.kind === 'pass' ? passInfo?.summary : STEP_DESCRIPTIONS[step.kind];
  const compareLabel = compare === 'previous' ? 'previous step' : activeView === 'ir' ? 'first IR' : 'javac output';

  return (
    <section className="inspector" aria-labelledby="inspector-title">
      <header className="inspector__head" style={{ ['--pass-color' as string]: passColor(step.pass) }}>
        <div className="inspector__eyebrow">
          <span className="mono">{methodLabel(method)}</span>
          <span aria-hidden="true">·</span>
          <span>Step {step.index + 1} of {method.steps.length}</span>
          {step.kind === 'pass' && <span>· Round {step.round}</span>}
        </div>
        <h2 id="inspector-title" className="inspector__title">
          <span className="pass-dot" aria-hidden="true" />
          {title}
          {step.kind === 'pass' && (
            <ToneBadge tone={step.changes > 0 ? 'good' : 'neutral'}>
              {step.changes > 0 ? `${step.changes} change${step.changes === 1 ? '' : 's'}` : 'no change'}
            </ToneBadge>
          )}
          {step.kind === 'decision' && <ToneBadge tone={outcome.tone}>{outcome.label}</ToneBadge>}
        </h2>
        {description && <p className="inspector__desc">{description}</p>}
      </header>

      <div className="inspector__body">
        {step.kind === 'decision' && (
          <div className={`callout callout--${outcome.tone}`}>
            <strong>{outcome.label}.</strong> {outcome.explanation}
            {method.outcomeDetail && <span className="callout__detail mono"> {method.outcomeDetail}</span>}
          </div>
        )}
        {step.kind === 'pass' && <Transformations step={step} method={method} meta={meta} />}

        <Metrics
          before={step.kind === 'decision' ? input : previous}
          after={current}
          beforeLabel={step.kind === 'decision' ? 'javac' : 'Before'}
          afterLabel={step.kind === 'decision' ? 'In class file' : 'After'}
        />

        <div className={`codebox${expanded ? ' codebox--expanded' : ''}`}>
          <div className="codebox__bar">
            <Segmented<View>
              label="Representation"
              value={activeView}
              onChange={setView}
              options={[
                { value: 'bytecode', label: 'Bytecode' },
                { value: 'ir', label: 'IR', disabled: !irAvailable },
              ]}
            />
            <Segmented<Compare>
              label="Compare with"
              value={compare}
              onChange={setCompare}
              options={[
                { value: 'previous', label: 'vs previous' },
                { value: 'input', label: activeView === 'ir' ? 'vs first IR' : 'vs javac' },
              ]}
            />
            {stats && (
              <span className="diffstat num" aria-label={`${stats.added} lines added, ${stats.removed} removed`}>
                <span className="diffstat__add">+{stats.added}</span> <span className="diffstat__del">−{stats.removed}</span>
              </span>
            )}
            {sameAsBase && step.index > 0 && <span className="muted small">identical to {compareLabel}</span>}
            <span className="codebox__spacer" />
            <IconButton label={sideBySide ? 'Inline diff' : 'Side-by-side diff'} onClick={() => setSideBySide((v) => !v)} pressed={!sideBySide}>
              <span className="mono small">{sideBySide ? '⫼' : '≡'}</span>
            </IconButton>
            {baseRows && <CopyButton label={`Copy ${compareLabel}`} text={baseRows.map((r) => r.text).join('\n')} />}
            {modifiedRows && <CopyButton label="Copy this step's code" text={modifiedRows.map((r) => r.text).join('\n')} />}
            <IconButton label={expanded ? 'Exit full screen (Esc)' : 'Full screen'} onClick={() => setExpanded((v) => !v)}>
              {expanded ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
            </IconButton>
          </div>
          {baseRows && !sameAsBase && (
            <div className="codebox__labels small">
              <span>{compareLabel}</span>
              <span>{title}</span>
            </div>
          )}
          <div className="codebox__editor">
            {modifiedRows ? (
              <CodeView
                original={sameAsBase ? null : baseRows}
                modified={modifiedRows}
                theme={theme}
                sideBySide={sideBySide}
                linkedSourceLine={linkedSourceLine}
                onSourceLine={onSourceLine}
                ariaLabel={`${activeView === 'ir' ? 'IR' : 'Bytecode'} after ${title}`}
              />
            ) : (
              <div className="note note--pad">
                {current.loweringError
                  ? `This intermediate IR could not be lowered to bytecode on its own (${current.loweringError}). Switch to the IR view to see it.`
                  : 'Nothing to show in this view for this step.'}
              </div>
            )}
          </div>
          <p className="codebox__hint small muted">
            Click an instruction to highlight the Java line it came from (from the class file's line number table).
          </p>
        </div>
      </div>
    </section>
  );
}
