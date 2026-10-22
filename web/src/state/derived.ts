import type { MethodTrace, Row, Snapshot, Step } from '../api/types';

/**
 * The snapshot in effect at {@code index}. Steps whose IR did not change carry no snapshot of their
 * own (sameAsPrevious), so this walks back to the most recent recorded one.
 */
export function resolveSnapshot(steps: readonly Step[], index: number): Snapshot | null {
  for (let i = Math.min(index, steps.length - 1); i >= 0; i--) {
    const s = steps[i];
    if (s?.snapshot) return s.snapshot;
  }
  return null;
}

/** Steps shown in the timeline: pass runs that changed nothing can be hidden. */
export function visibleSteps(steps: readonly Step[], hideNoOps: boolean): number[] {
  return steps.filter((s) => !(hideNoOps && s.kind === 'pass' && s.sameAsPrevious)).map((s) => s.index);
}

/**
 * Relative size change, defined as (after - before) / before * 100. Negative means smaller.
 * Null when it is undefined (missing values or a zero baseline).
 */
export function percentChange(before: number | null, after: number | null): number | null {
  if (before === null || after === null || before === 0) return null;
  return ((after - before) / before) * 100;
}

export type ChangeDirection = 'smaller' | 'larger' | 'same' | 'unknown';

export interface FormattedChange {
  text: string;
  direction: ChangeDirection;
}

export function formatChange(before: number | null, after: number | null): FormattedChange {
  if (before === null || after === null) return { text: 'n/a', direction: 'unknown' };
  if (before === after) return { text: '±0', direction: 'same' };
  const pct = percentChange(before, after);
  const delta = after - before;
  const sign = delta > 0 ? '+' : '−';
  const pctText = pct === null ? '' : ` (${sign}${Math.abs(pct).toFixed(1)}%)`;
  return { text: `${sign}${Math.abs(delta)}${pctText}`, direction: delta < 0 ? 'smaller' : 'larger' };
}

const PRIMITIVES: Record<string, string> = {
  Z: 'boolean', B: 'byte', C: 'char', S: 'short', I: 'int', J: 'long', F: 'float', D: 'double', V: 'void',
};

/** Parses one JVM field type starting at {@code i}; returns the Java name and the next index. */
function parseType(desc: string, i: number): [string, number] {
  let dims = 0;
  while (desc[i] === '[') {
    dims++;
    i++;
  }
  const c = desc[i];
  let name: string;
  if (c === 'L') {
    const end = desc.indexOf(';', i);
    if (end < 0) return [desc.slice(i), desc.length];
    const internal = desc.slice(i + 1, end);
    name = internal.slice(internal.lastIndexOf('/') + 1).replace(/\$/g, '.');
    i = end + 1;
  } else {
    name = (c && PRIMITIVES[c]) ?? '?';
    i++;
  }
  return [name + '[]'.repeat(dims), i];
}

/** "(ILjava/lang/String;)V" -> { params: ["int", "String"], returns: "void" }. */
export function parseMethodDescriptor(desc: string): { params: string[]; returns: string } {
  const params: string[] = [];
  let i = desc.indexOf('(') + 1;
  if (i <= 0) return { params, returns: desc };
  while (i < desc.length && desc[i] !== ')') {
    const [name, next] = parseType(desc, i);
    params.push(name);
    if (next <= i) break;
    i = next;
  }
  const [returns] = parseType(desc, i + 1);
  return { params, returns };
}

export function simpleClassName(internal: string): string {
  return internal.slice(internal.lastIndexOf('/') + 1);
}

/** "scaled(int) → int", constructors shown as "new Foo(...)". */
export function methodLabel(m: Pick<MethodTrace, 'owner' | 'name' | 'desc'>): string {
  const { params, returns } = parseMethodDescriptor(m.desc);
  const args = params.join(', ');
  if (m.name === '<init>') return `new ${simpleClassName(m.owner)}(${args})`;
  if (m.name === '<clinit>') return `static {} of ${simpleClassName(m.owner)}`;
  return `${m.name}(${args}) → ${returns}`;
}

/** The method to show first: the one whose code shrank most, else the first ordinary method. */
export function pickDefaultMethod(methods: readonly MethodTrace[]): string | null {
  let best: MethodTrace | null = null;
  let bestSaved = 0;
  for (const m of methods) {
    if (m.outcome !== 'REPLACED') continue;
    const before = m.steps[0]?.snapshot?.codeBytes ?? 0;
    const after = resolveSnapshot(m.steps, m.steps.length - 1)?.codeBytes ?? before;
    if (before - after > bestSaved) {
      best = m;
      bestSaved = before - after;
    }
  }
  if (best) return best.id;
  const ordinary = methods.find((m) => !m.name.startsWith('<'));
  return (ordinary ?? methods[0])?.id ?? null;
}

export function rowsText(rows: readonly Row[] | null): string {
  return rows ? rows.map((r) => r.text).join('\n') : '';
}

/** Counts lines only in {@code before} (removed) and only in {@code after} (added), via an LCS. */
export function lineDiffStats(before: readonly string[], after: readonly string[]): { added: number; removed: number } {
  const n = before.length;
  const m = after.length;
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    const row = lcs[i]!;
    const below = lcs[i + 1]!;
    for (let j = m - 1; j >= 0; j--) {
      row[j] = before[i] === after[j] ? below[j + 1]! + 1 : Math.max(below[j]!, row[j + 1]!);
    }
  }
  const common = lcs[0]![0]!;
  return { added: m - common, removed: n - common };
}
