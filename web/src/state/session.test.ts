import { describe, expect, it } from 'vitest';
import { parseSession } from '../api/validate';
import constantLocalsJson from '../test/fixtures/ConstantLocals.session.json';
import debugFlagJson from '../test/fixtures/DebugFlag.session.json';
import brokenJson from '../test/fixtures/Broken.session.json';
import { initialState, isStale, reducer, selectedMethod, type Action, type PlaygroundState } from './session';
import { resolveSnapshot, visibleSteps } from './derived';

const constantLocals = parseSession(constantLocalsJson);
const debugFlag = parseSession(debugFlagJson);
const broken = parseSession(brokenJson);
const input = { source: 'class A {}', disabled: [] };

function run(...actions: Action[]): PlaygroundState {
  return actions.reduce(reducer, initialState);
}

function loaded(session = constantLocals): PlaygroundState {
  return run({ type: 'run/start', input }, { type: 'run/succeeded', requestId: 1, session });
}

describe('run lifecycle', () => {
  it('goes idle → running → ready and selects the method that shrank most', () => {
    const running = run({ type: 'run/start', input });
    expect(running.phase).toBe('running');
    expect(running.requestId).toBe(1);

    const ready = reducer(running, { type: 'run/succeeded', requestId: 1, session: constantLocals });
    expect(ready.phase).toBe('ready');
    expect(selectedMethod(ready)?.name).toBe('scaled');
    expect(ready.stepIndex).toBe(0);
    expect(ready.sessionInput).toEqual(input);
  });

  it('ignores a response from an older request', () => {
    const second = run({ type: 'run/start', input }, { type: 'run/start', input: { ...input, source: 'class B {}' } });
    const late = reducer(second, { type: 'run/succeeded', requestId: 1, session: constantLocals });
    expect(late).toBe(second);
    expect(late.phase).toBe('running');
    const lateError = reducer(second, { type: 'run/failed', requestId: 1, error: { kind: 'network', code: 'x', message: 'x' } });
    expect(lateError).toBe(second);
  });

  it('reports failures and recovers on the next run', () => {
    const failed = run(
      { type: 'run/start', input },
      { type: 'run/failed', requestId: 1, error: { kind: 'timeout', code: 'timeout', message: 'slow' } },
    );
    expect(failed.phase).toBe('error');
    expect(failed.error?.code).toBe('timeout');
    const retried = reducer(failed, { type: 'run/start', input });
    expect(retried.phase).toBe('running');
    expect(retried.error).toBeNull();
  });

  it('returns to the previous results when a request is cancelled', () => {
    const s = reducer(reducer(loaded(), { type: 'run/start', input }), {
      type: 'run/failed', requestId: 2, error: { kind: 'aborted', code: 'aborted', message: '' },
    });
    expect(s.phase).toBe('ready');
    expect(s.session).toBe(constantLocals);
  });

  it('keeps compile errors as a ready session without a method', () => {
    const s = loaded(broken);
    expect(s.phase).toBe('ready');
    expect(s.session?.status).toBe('compile-error');
    expect(s.methodId).toBeNull();
    expect(reducer(s, { type: 'step/next' })).toBe(s);
    expect(reducer(s, { type: 'play' })).toBe(s);
  });

  it('reset clears results and invalidates in-flight requests', () => {
    const s = reducer(reducer(loaded(), { type: 'run/start', input }), { type: 'reset' });
    expect(s.phase).toBe('idle');
    expect(s.session).toBeNull();
    expect(reducer(s, { type: 'run/succeeded', requestId: 2, session: constantLocals })).toBe(s);
  });
});

describe('step navigation', () => {
  it('cannot move before the first or past the last step', () => {
    const start = loaded();
    expect(reducer(start, { type: 'step/prev' }).stepIndex).toBe(0);
    const end = reducer(start, { type: 'step/last' });
    const m = selectedMethod(end)!;
    expect(end.stepIndex).toBe(m.steps.length - 1);
    expect(reducer(end, { type: 'step/next' }).stepIndex).toBe(end.stepIndex);
  });

  it('skips pass runs that changed nothing while they are hidden', () => {
    let s = loaded();
    const m = selectedMethod(s)!;
    const shown = visibleSteps(m.steps, true);
    const seen = [s.stepIndex];
    for (let i = 0; i < m.steps.length; i++) {
      s = reducer(s, { type: 'step/next' });
      if (s.stepIndex !== seen[seen.length - 1]) seen.push(s.stepIndex);
    }
    expect(seen).toEqual(shown);
    expect(seen.every((i) => !(m.steps[i]!.kind === 'pass' && m.steps[i]!.sameAsPrevious))).toBe(true);
  });

  it('visits every step when no-op runs are shown, and back again', () => {
    let s = reducer(loaded(), { type: 'noops/toggle' });
    const total = selectedMethod(s)!.steps.length;
    for (let i = 0; i < total; i++) s = reducer(s, { type: 'step/next' });
    expect(s.stepIndex).toBe(total - 1);
    for (let i = 0; i < total; i++) s = reducer(s, { type: 'step/prev' });
    expect(s.stepIndex).toBe(0);
  });

  it('moves off a step that becomes hidden', () => {
    const showing = reducer(loaded(), { type: 'noops/toggle' });
    const m = selectedMethod(showing)!;
    const hiddenIndex = m.steps.find((st) => st.kind === 'pass' && st.sameAsPrevious)!.index;
    const onHidden = reducer(showing, { type: 'step/goto', index: hiddenIndex });
    const hidden = reducer(onHidden, { type: 'noops/toggle' });
    expect(visibleSteps(m.steps, true)).toContain(hidden.stepIndex);
    expect(hidden.stepIndex).toBeLessThan(hiddenIndex);
  });

  it('clamps direct jumps and resets the step when another method is selected', () => {
    const s = reducer(loaded(), { type: 'step/goto', index: 999 });
    expect(s.stepIndex).toBe(selectedMethod(s)!.steps.length - 1);
    const init = constantLocals.methods.find((m) => m.name === '<init>')!;
    const other = reducer(s, { type: 'method/select', methodId: init.id });
    expect(other.methodId).toBe(init.id);
    expect(other.stepIndex).toBe(0);
    expect(reducer(s, { type: 'method/select', methodId: 'nope' })).toBe(s);
  });
});

describe('playback', () => {
  it('replays to the end and stops there', () => {
    let s = reducer(loaded(debugFlag), { type: 'play' });
    expect(s.playing).toBe(true);
    for (let i = 0; i < 100 && s.playing; i++) s = reducer(s, { type: 'tick' });
    expect(s.playing).toBe(false);
    expect(s.stepIndex).toBe(selectedMethod(s)!.steps.length - 1);
  });

  it('restarts from the first step when play is pressed at the end', () => {
    const s = reducer(reducer(loaded(), { type: 'step/last' }), { type: 'play' });
    expect(s.stepIndex).toBe(0);
    expect(s.playing).toBe(true);
  });

  it('stops when the user navigates or pauses', () => {
    const playing = reducer(loaded(), { type: 'play' });
    expect(reducer(playing, { type: 'step/next' }).playing).toBe(false);
    expect(reducer(playing, { type: 'pause' }).playing).toBe(false);
    expect(reducer(reducer(playing, { type: 'pause' }), { type: 'tick' }).stepIndex).toBe(playing.stepIndex);
  });

  it('accepts only the offered speeds', () => {
    expect(reducer(initialState, { type: 'speed/set', speed: 2 }).speed).toBe(2);
    expect(reducer(initialState, { type: 'speed/set', speed: 3 }).speed).toBe(1);
  });
});

describe('staleness and snapshots', () => {
  it('marks results stale when the source or pass selection changes', () => {
    const s = loaded();
    expect(isStale(s, input)).toBe(false);
    expect(isStale(s, { ...input, source: 'class A { }' })).toBe(true);
    expect(isStale(s, { ...input, disabled: ['dse'] })).toBe(true);
    expect(isStale(initialState, input)).toBe(false);
  });

  it('resolves the snapshot of an unchanged step from the step before it', () => {
    const m = selectedMethod(loaded())!;
    const unchanged = m.steps.find((st) => st.sameAsPrevious)!;
    expect(unchanged.snapshot).toBeNull();
    expect(resolveSnapshot(m.steps, unchanged.index)).toBe(m.steps[unchanged.index - 1]!.snapshot);
  });
});
