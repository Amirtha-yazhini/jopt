import { useEffect, useRef, type Dispatch } from 'react';
import { EyeOff, Eye } from 'lucide-react';
import type { Meta, MethodTrace, Session, Step } from '../api/types';
import { formatChange, methodLabel, resolveSnapshot, simpleClassName } from '../state/derived';
import type { Action } from '../state/session';
import { IconButton, ToneBadge } from '../ui/controls';
import { OUTCOMES, passColor, stepTitle } from '../ui/vocabulary';
import { SizeChart } from './SizeChart';
import { Timeline } from './Timeline';

type Props = {
  session: Session;
  method: MethodTrace;
  meta: Meta;
  indices: readonly number[];
  stepIndex: number;
  playing: boolean;
  speed: number;
  hideNoOps: boolean;
  dispatch: Dispatch<Action>;
};

function stepSubtitle(step: Step): string {
  switch (step.kind) {
    case 'input':
      return 'bytecode as compiled';
    case 'build':
      return 'control-flow graph';
    case 'decision':
      return 'what the class file keeps';
    case 'pass': {
      const names = Object.entries(step.counters).map(([k, n]) => `${n}× ${k}`);
      return names.length > 0 ? names.join(', ') : 'no change';
    }
  }
}

function StageRow({ step, method, meta, current, onSelect }: {
  step: Step; method: MethodTrace; meta: Meta; current: number; onSelect: (i: number) => void;
}) {
  const before = step.index > 0 ? resolveSnapshot(method.steps, step.index - 1)?.instructions ?? null : null;
  const after = resolveSnapshot(method.steps, step.index)?.instructions ?? null;
  const delta = step.kind === 'pass' && !step.sameAsPrevious ? formatChange(before, after) : null;
  const state = step.index === current ? 'current' : step.index < current ? 'past' : 'upcoming';
  const outcome = step.kind === 'decision' ? OUTCOMES[method.outcome] : null;
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (state === 'current') ref.current?.scrollIntoView({ block: 'nearest' });
  }, [state]);
  return (
    <li>
      <button
        ref={ref}
        type="button"
        className={`stage stage--${state}${step.kind === 'pass' && step.sameAsPrevious ? ' stage--noop' : ''}`}
        aria-current={state === 'current' ? 'step' : undefined}
        onClick={() => onSelect(step.index)}
        style={{ ['--pass-color' as string]: passColor(step.pass) }}
      >
        <span className="stage__marker" aria-hidden="true" />
        <span className="stage__text">
          <span className="stage__title">
            {stepTitle(step, meta.passes)}
            {step.kind === 'pass' && <span className="stage__id mono">{step.pass}</span>}
          </span>
          <span className="stage__sub">{stepSubtitle(step)}</span>
        </span>
        {delta && (
          <span className={`stage__delta num delta--${delta.direction}`} title="Change in bytecode instructions">
            {delta.text.split(' ')[0]}
          </span>
        )}
        {outcome && <ToneBadge tone={outcome.tone}>{outcome.label}</ToneBadge>}
      </button>
    </li>
  );
}

export function PipelinePanel({ session, method, meta, indices, stepIndex, playing, speed, hideNoOps, dispatch }: Props) {
  const shown = new Set(indices);
  const steps = method.steps;
  const rounds = Array.from({ length: method.rounds }, (_, r) => r + 1);
  const select = (index: number) => dispatch({ type: 'step/goto', index });
  const head = steps.filter((s) => s.kind === 'input' || s.kind === 'build');
  const decision = steps[steps.length - 1]!;
  const ms = session.timings.optimizeMs;

  return (
    <section className="pipeline" aria-labelledby="pipeline-title">
      <div className="pipeline__top">
        <label className="field">
          <span className="field__label" id="pipeline-title">Method</span>
          <select
            className="field__select mono"
            value={method.id}
            onChange={(e) => dispatch({ type: 'method/select', methodId: e.target.value })}
          >
            {session.methods.map((m) => (
              <option key={m.id} value={m.id}>
                {simpleClassName(m.owner)} · {methodLabel(m)} — {OUTCOMES[m.outcome].label}
              </option>
            ))}
          </select>
        </label>
        <p className="replay-note small">
          <span className="replay-note__dot" aria-hidden="true" />
          Replaying a recorded run: jopt finished all {method.rounds} round{method.rounds === 1 ? '' : 's'} in{' '}
          <span className="num">{ms.toFixed(0)} ms</span> (server time, all methods). Each step is a snapshot taken
          after that pass.
        </p>
        <SizeChart method={method} indices={indices} stepIndex={stepIndex} onSelect={select} />
      </div>

      <div className="pipeline__list">
        <div className="pipeline__listhead">
          <span className="section-label">Stages</span>
          <IconButton
            label={hideNoOps ? 'Show pass runs that changed nothing' : 'Hide pass runs that changed nothing'}
            pressed={!hideNoOps}
            onClick={() => dispatch({ type: 'noops/toggle' })}
          >
            {hideNoOps ? <EyeOff size={14} /> : <Eye size={14} />}
          </IconButton>
        </div>
        <ol className="stages">
          {head.map((s) => (
            <StageRow key={s.index} step={s} method={method} meta={meta} current={stepIndex} onSelect={select} />
          ))}
          {rounds.map((r) => {
            const inRound = steps.filter((s) => s.kind === 'pass' && s.round === r);
            const visible = inRound.filter((s) => shown.has(s.index));
            const hidden = inRound.length - visible.length;
            const fixedPoint = r === method.rounds && inRound.every((s) => s.changes === 0);
            return (
              <li key={`round-${r}`} className="round">
                <div className="round__head">
                  <span>Round {r}</span>
                  {fixedPoint && <span className="round__tag">fixed point: nothing changed</span>}
                  {hidden > 0 && !fixedPoint && <span className="round__tag">{hidden} unchanged hidden</span>}
                </div>
                {visible.length > 0 && (
                  <ol className="stages">
                    {visible.map((s) => (
                      <StageRow key={s.index} step={s} method={method} meta={meta} current={stepIndex} onSelect={select} />
                    ))}
                  </ol>
                )}
              </li>
            );
          })}
          <StageRow step={decision} method={method} meta={meta} current={stepIndex} onSelect={select} />
        </ol>
      </div>

      <Timeline indices={indices} stepIndex={stepIndex} playing={playing} speed={speed} dispatch={dispatch} />
    </section>
  );
}
