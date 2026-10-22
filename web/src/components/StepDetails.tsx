import type { Meta, MethodTrace, Snapshot, Step } from '../api/types';
import { formatChange } from '../state/derived';
import { passColor } from '../ui/vocabulary';

export const METRIC_DEFINITIONS = {
  instructions:
    'Real JVM instructions in the method\'s code (labels, line numbers and stack map frames are not counted). ' +
    'For IR steps this is the IR lowered to bytecode by jopt\'s IRWriter.',
  codeBytes:
    'Size of the method\'s code array in bytes, as jopt measures it (Bytecode.bytes: exact, except that every ldc ' +
    'is assumed to fit the 2-byte form).',
  irBlocks: 'Basic blocks in jopt\'s control-flow graph.',
  irInstructions: 'IR instructions, counting one terminator (goto, if, switch, return, throw) per block.',
} as const;

type MetricKey = keyof typeof METRIC_DEFINITIONS;

const METRIC_LABELS: Record<MetricKey, string> = {
  instructions: 'Bytecode instructions',
  codeBytes: 'Code bytes',
  irBlocks: 'IR blocks',
  irInstructions: 'IR instructions',
};

type MetricsProps = {
  before: Snapshot | null;
  after: Snapshot;
  beforeLabel: string;
  afterLabel: string;
};

/** Sizes before and after a step. Every value is measured by the backend; none are estimates. */
export function Metrics({ before, after, beforeLabel, afterLabel }: MetricsProps) {
  const keys: MetricKey[] = ['instructions', 'codeBytes', 'irBlocks', 'irInstructions'];
  const shown = keys.filter((k) => after[k] !== null || (before && before[k] !== null));
  return (
    <dl className="stats" aria-label={before ? `Measured sizes, ${beforeLabel} → ${afterLabel}` : 'Measured sizes'}>
      {shown.map((k) => {
        const change = before ? formatChange(before[k], after[k]) : null;
        return (
          <div key={k} className="stat">
            <dt className="stat__label">
              <abbr title={METRIC_DEFINITIONS[k]}>{METRIC_LABELS[k]}</abbr>
            </dt>
            <dd className="stat__value num">
              {before && (
                <>
                  <span className="stat__before" title={beforeLabel}>{before[k] ?? 'n/a'}</span>
                  <span className="stat__arrow" aria-label={`${beforeLabel} to ${afterLabel}`}>→</span>
                </>
              )}
              <span title={afterLabel}>{after[k] ?? 'n/a'}</span>
              {change && change.direction !== 'unknown' && (
                <span className={`stat__delta delta--${change.direction}`}>{change.text}</span>
              )}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

type TransformationsProps = {
  step: Step;
  method: MethodTrace;
  meta: Meta;
};

/** What a pass run did: the counts the pass itself recorded, with documentation for each kind of rewrite. */
export function Transformations({ step, method, meta }: TransformationsProps) {
  const entries = Object.entries(step.counters);
  const lastRound = step.round === method.rounds;
  if (entries.length === 0) {
    return (
      <div className="note">
        {step.changes === 0 ? 'This run found nothing to rewrite; the IR is unchanged.' : `${step.changes} changes, no named counts.`}
        {lastRound && step.changes === 0 && (
          <> Every pass in round {step.round} changed nothing, so the optimizer reached a fixed point and stopped.</>
        )}
      </div>
    );
  }
  return (
    <div className="transforms">
      <p className="transforms__source">
        Counts reported by the <code>{step.pass}</code> pass for this run. The text below each one documents that
        kind of rewrite.
      </p>
      <ul className="transforms__list">
        {entries.map(([counter, count]) => {
          const doc = meta.rewrites.find((r) => r.pass === step.pass && r.counter === counter);
          return (
            <li key={counter} className="transform" style={{ ['--pass-color' as string]: passColor(step.pass) }}>
              <div className="transform__head">
                <span className="transform__count num">{count}×</span>
                <span className="transform__name">{counter}</span>
              </div>
              {doc ? (
                <>
                  <p className="transform__text">{doc.explanation}</p>
                  <code className="transform__example">{doc.example}</code>
                </>
              ) : (
                <p className="transform__text muted">No documentation for this counter yet.</p>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
