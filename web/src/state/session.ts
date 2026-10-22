import type { ApiError } from '../api/client';
import type { MethodTrace, Session } from '../api/types';
import { pickDefaultMethod, visibleSteps } from './derived';

/**
 * idle     no results yet
 * running  a request is in flight (the previous results, if any, stay visible but marked as old)
 * ready    a response arrived: either an optimized session or a compile error
 * error    the request failed (network, timeout, server error, unexpected response)
 *
 * Playback is a flag inside "ready": it replays recorded steps; nothing is re-optimized.
 */
export type Phase = 'idle' | 'running' | 'ready' | 'error';

export interface RunInput {
  source: string;
  disabled: string[];
}

export interface PlaygroundState {
  phase: Phase;
  /** Incremented per run; responses for an older id are ignored. */
  requestId: number;
  /** What the in-flight or most recent run was asked to optimize. */
  pending: RunInput | null;
  session: Session | null;
  /** The input that produced {@code session}; compared with the editor to detect stale results. */
  sessionInput: RunInput | null;
  error: ApiError | null;
  methodId: string | null;
  stepIndex: number;
  playing: boolean;
  /** Playback speed multiplier. */
  speed: number;
  hideNoOps: boolean;
}

export const SPEEDS = [0.5, 1, 2, 4] as const;

export const initialState: PlaygroundState = {
  phase: 'idle',
  requestId: 0,
  pending: null,
  session: null,
  sessionInput: null,
  error: null,
  methodId: null,
  stepIndex: 0,
  playing: false,
  speed: 1,
  hideNoOps: true,
};

export type Action =
  | { type: 'run/start'; input: RunInput }
  | { type: 'run/succeeded'; requestId: number; session: Session }
  | { type: 'run/failed'; requestId: number; error: ApiError }
  | { type: 'reset' }
  | { type: 'method/select'; methodId: string }
  | { type: 'step/goto'; index: number }
  | { type: 'step/next' }
  | { type: 'step/prev' }
  | { type: 'step/first' }
  | { type: 'step/last' }
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'play/toggle' }
  | { type: 'tick' }
  | { type: 'speed/set'; speed: number }
  | { type: 'noops/toggle' };

export function selectedMethod(state: PlaygroundState): MethodTrace | null {
  if (!state.session || !state.methodId) return null;
  return state.session.methods.find((m) => m.id === state.methodId) ?? null;
}

function visible(state: PlaygroundState): number[] {
  const m = selectedMethod(state);
  return m ? visibleSteps(m.steps, state.hideNoOps) : [];
}

/** Position of the current step among the visible ones (or of the nearest visible step before it). */
export function visiblePosition(indices: readonly number[], stepIndex: number): number {
  let pos = 0;
  for (let i = 0; i < indices.length; i++) if ((indices[i] ?? Infinity) <= stepIndex) pos = i;
  return pos;
}

function moveTo(state: PlaygroundState, pos: number, keepPlaying = false): PlaygroundState {
  const indices = visible(state);
  if (indices.length === 0) return state;
  const clamped = Math.max(0, Math.min(indices.length - 1, pos));
  return { ...state, stepIndex: indices[clamped] ?? 0, playing: keepPlaying };
}

function atEnd(state: PlaygroundState): boolean {
  const indices = visible(state);
  return indices.length === 0 || state.stepIndex >= (indices[indices.length - 1] ?? 0);
}

export function reducer(state: PlaygroundState, action: Action): PlaygroundState {
  switch (action.type) {
    case 'run/start':
      return { ...state, phase: 'running', requestId: state.requestId + 1, pending: action.input, error: null, playing: false };

    case 'run/succeeded': {
      if (action.requestId !== state.requestId) return state; // an older request finished late
      const s = action.session;
      return {
        ...state,
        phase: 'ready',
        session: s,
        sessionInput: state.pending,
        error: null,
        methodId: s.status === 'optimized' ? pickDefaultMethod(s.methods) : null,
        stepIndex: 0,
        playing: false,
      };
    }

    case 'run/failed': {
      if (action.requestId !== state.requestId) return state;
      if (action.error.kind === 'aborted') return { ...state, phase: state.session ? 'ready' : 'idle' };
      return { ...state, phase: 'error', error: action.error, playing: false };
    }

    case 'reset':
      return { ...initialState, requestId: state.requestId + 1, speed: state.speed, hideNoOps: state.hideNoOps };

    case 'method/select':
      if (!state.session?.methods.some((m) => m.id === action.methodId)) return state;
      return { ...state, methodId: action.methodId, stepIndex: 0, playing: false };

    case 'step/goto': {
      const m = selectedMethod(state);
      if (!m) return state;
      return { ...state, stepIndex: Math.max(0, Math.min(m.steps.length - 1, action.index)), playing: false };
    }
    case 'step/next':
      return moveTo(state, visiblePosition(visible(state), state.stepIndex) + 1);
    case 'step/prev': {
      const indices = visible(state);
      const pos = visiblePosition(indices, state.stepIndex);
      // On a hidden step, "previous" is the visible step at or before it.
      return moveTo(state, indices[pos] === state.stepIndex ? pos - 1 : pos);
    }
    case 'step/first':
      return moveTo(state, 0);
    case 'step/last':
      return moveTo(state, Number.MAX_SAFE_INTEGER);

    case 'play': {
      if (!selectedMethod(state)) return state;
      const restarted = atEnd(state) ? moveTo(state, 0) : state;
      return { ...restarted, playing: true };
    }
    case 'pause':
      return { ...state, playing: false };
    case 'play/toggle':
      return reducer(state, { type: state.playing ? 'pause' : 'play' });
    case 'tick': {
      if (!state.playing) return state;
      if (atEnd(state)) return { ...state, playing: false };
      const next = moveTo(state, visiblePosition(visible(state), state.stepIndex) + 1, true);
      return atEnd(next) ? { ...next, playing: false } : next;
    }

    case 'speed/set':
      return SPEEDS.includes(action.speed as (typeof SPEEDS)[number]) ? { ...state, speed: action.speed } : state;

    case 'noops/toggle': {
      const toggled = { ...state, hideNoOps: !state.hideNoOps };
      const indices = visible(toggled);
      if (indices.includes(toggled.stepIndex) || indices.length === 0) return toggled;
      return { ...toggled, stepIndex: indices[visiblePosition(indices, toggled.stepIndex)] ?? 0 };
    }
  }
}

/** True when the visible editor/pass selection no longer matches the input that produced the results. */
export function isStale(state: PlaygroundState, current: RunInput): boolean {
  const input = state.sessionInput;
  if (!state.session || !input) return false;
  return input.source !== current.source || [...input.disabled].sort().join() !== [...current.disabled].sort().join();
}
