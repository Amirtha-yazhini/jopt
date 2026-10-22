import { ChevronFirst, ChevronLast, ChevronLeft, ChevronRight, Pause, Play } from 'lucide-react';
import type { Dispatch } from 'react';
import { type Action, SPEEDS, visiblePosition } from '../state/session';
import { IconButton } from '../ui/controls';

type Props = {
  indices: readonly number[];
  stepIndex: number;
  playing: boolean;
  speed: number;
  dispatch: Dispatch<Action>;
};

/** Replay controls for the recorded steps of one method. */
export function Timeline({ indices, stepIndex, playing, speed, dispatch }: Props) {
  const pos = visiblePosition(indices, stepIndex);
  const last = indices.length - 1;
  const atStart = pos === 0 && stepIndex === indices[0];
  const atEnd = pos === last;

  return (
    <div className="timeline" role="group" aria-label="Replay controls">
      <div className="timeline__buttons">
        <IconButton label="First step" shortcut="Home" disabled={atStart} onClick={() => dispatch({ type: 'step/first' })}>
          <ChevronFirst size={16} />
        </IconButton>
        <IconButton label="Previous step" shortcut="←" disabled={atStart} onClick={() => dispatch({ type: 'step/prev' })}>
          <ChevronLeft size={16} />
        </IconButton>
        <IconButton
          label={playing ? 'Pause replay' : atEnd ? 'Replay from the start' : 'Play replay'}
          shortcut="Space"
          variant="solid"
          onClick={() => dispatch({ type: playing ? 'pause' : 'play' })}
        >
          {playing ? <Pause size={16} /> : <Play size={16} />}
        </IconButton>
        <IconButton label="Next step" shortcut="→" disabled={atEnd} onClick={() => dispatch({ type: 'step/next' })}>
          <ChevronRight size={16} />
        </IconButton>
        <IconButton label="Last step" shortcut="End" disabled={atEnd} onClick={() => dispatch({ type: 'step/last' })}>
          <ChevronLast size={16} />
        </IconButton>
      </div>
      <input
        className="timeline__scrub"
        type="range"
        min={0}
        max={Math.max(0, last)}
        value={pos}
        aria-label="Step"
        aria-valuetext={`Step ${pos + 1} of ${indices.length}`}
        onChange={(e) => dispatch({ type: 'step/goto', index: indices[Number(e.target.value)] ?? 0 })}
      />
      <span className="timeline__count num" aria-hidden="true">
        {pos + 1}/{indices.length}
      </span>
      <label className="timeline__speed">
        <span className="visually-hidden">Replay speed</span>
        <select value={speed} onChange={(e) => dispatch({ type: 'speed/set', speed: Number(e.target.value) })}>
          {SPEEDS.map((s) => (
            <option key={s} value={s}>
              {s}×
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
