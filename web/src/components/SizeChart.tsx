import type { MethodTrace } from '../api/types';
import { resolveSnapshot } from '../state/derived';
import { passColor } from '../ui/vocabulary';

type Props = {
  method: MethodTrace;
  indices: readonly number[];
  stepIndex: number;
  onSelect: (index: number) => void;
};

const HEIGHT = 44;
const BAR_GAP = 3;

/**
 * Bytecode instruction count after each shown step, as measured by the backend. A gap means the IR
 * could not be lowered at that point. The stage list below is the keyboard-accessible equivalent.
 */
export function SizeChart({ method, indices, stepIndex, onSelect }: Props) {
  const values = indices.map((i) => resolveSnapshot(method.steps, i)?.instructions ?? null);
  const max = Math.max(1, ...values.map((v) => v ?? 0));
  const first = values[0];
  const last = values[values.length - 1];
  const summary =
    first != null && last != null
      ? `Bytecode instructions per step, from ${first} (javac) to ${last} (in the class file); peak ${max}.`
      : 'Bytecode instructions per step.';
  const width = 100 / Math.max(1, indices.length);

  return (
    <figure className="sizechart">
      <figcaption className="sizechart__caption small">
        <span>Bytecode instructions after each step</span>
        <span className="num">
          {first ?? 'n/a'} → {last ?? 'n/a'}
        </span>
      </figcaption>
      <svg className="sizechart__svg" viewBox={`0 0 100 ${HEIGHT}`} preserveAspectRatio="none" role="img" aria-label={summary}>
        {values.map((v, k) => {
          const index = indices[k]!;
          const step = method.steps[index]!;
          const h = v === null ? 0 : Math.max(1.5, (v / max) * (HEIGHT - 4));
          const state = index === stepIndex ? 'current' : index < stepIndex ? 'past' : 'upcoming';
          return (
            <rect
              key={index}
              className={`sizechart__bar sizechart__bar--${state}`}
              x={k * width + BAR_GAP / 10}
              y={HEIGHT - h}
              width={Math.max(0.5, width - BAR_GAP / 5)}
              height={h}
              rx={0.6}
              fill={passColor(step.pass)}
              onClick={() => onSelect(index)}
            >
              <title>{`Step ${index + 1}: ${v ?? 'not lowered'} instructions`}</title>
            </rect>
          );
        })}
      </svg>
    </figure>
  );
}
