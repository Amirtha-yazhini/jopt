import { describe, expect, it } from 'vitest';
import { parseSession } from '../api/validate';
import debugFlagJson from '../test/fixtures/DebugFlag.session.json';
import {
  formatChange,
  lineDiffStats,
  methodLabel,
  parseMethodDescriptor,
  percentChange,
  pickDefaultMethod,
  resolveSnapshot,
  visibleSteps,
} from './derived';

describe('percentChange', () => {
  it('is (after - before) / before * 100', () => {
    expect(percentChange(20, 5)).toBe(-75);
    expect(percentChange(10, 12)).toBeCloseTo(20);
    expect(percentChange(7, 7)).toBe(0);
  });

  it('is undefined for a zero baseline or missing values', () => {
    expect(percentChange(0, 5)).toBeNull();
    expect(percentChange(null, 5)).toBeNull();
    expect(percentChange(5, null)).toBeNull();
  });
});

describe('formatChange', () => {
  it('labels smaller, larger, unchanged and unknown values', () => {
    expect(formatChange(14, 4)).toEqual({ text: '−10 (−71.4%)', direction: 'smaller' });
    expect(formatChange(14, 16)).toEqual({ text: '+2 (+14.3%)', direction: 'larger' });
    expect(formatChange(3, 3)).toEqual({ text: '±0', direction: 'same' });
    expect(formatChange(null, 3)).toEqual({ text: 'n/a', direction: 'unknown' });
    expect(formatChange(0, 3)).toEqual({ text: '+3', direction: 'larger' });
  });
});

describe('method descriptors', () => {
  it('parses primitives, objects and arrays', () => {
    expect(parseMethodDescriptor('(I[JLjava/lang/String;[[Ljava/util/Map$Entry;)V')).toEqual({
      params: ['int', 'long[]', 'String', 'Map.Entry[][]'],
      returns: 'void',
    });
    expect(parseMethodDescriptor('()Ljava/lang/Object;')).toEqual({ params: [], returns: 'Object' });
  });

  it('formats methods and constructors', () => {
    expect(methodLabel({ owner: 'DebugFlag', name: 'compute', desc: '(I)I' })).toBe('compute(int) → int');
    expect(methodLabel({ owner: 'a/b/Foo', name: '<init>', desc: '()V' })).toBe('new Foo()');
  });
});

describe('snapshots of a real run', () => {
  const session = parseSession(debugFlagJson);
  const compute = session.methods.find((m) => m.name === 'compute')!;

  it('picks the method with the largest code-size saving by default', () => {
    expect(pickDefaultMethod(session.methods)).toBe(compute.id);
  });

  it('falls back to the first ordinary method when nothing was replaced', () => {
    const unchanged = session.methods.map((m) => ({ ...m, outcome: 'NOTHING_APPLIED' as const }));
    expect(pickDefaultMethod(unchanged)).toBe(compute.id);
    expect(pickDefaultMethod([])).toBeNull();
  });

  it('resolves every step to a snapshot and ends smaller than javac produced', () => {
    for (const st of compute.steps) expect(resolveSnapshot(compute.steps, st.index)).not.toBeNull();
    const first = resolveSnapshot(compute.steps, 0)!;
    const last = resolveSnapshot(compute.steps, compute.steps.length - 1)!;
    expect(last.codeBytes!).toBeLessThan(first.codeBytes!);
  });

  it('hides only pass runs that left the IR unchanged', () => {
    const all = visibleSteps(compute.steps, false);
    const shown = visibleSteps(compute.steps, true);
    expect(all).toHaveLength(compute.steps.length);
    expect(shown.length).toBeLessThan(all.length);
    expect(shown).toContain(0);
    expect(shown).toContain(compute.steps.length - 1);
  });
});

describe('lineDiffStats', () => {
  it('counts added and removed lines', () => {
    expect(lineDiffStats(['a', 'b', 'c'], ['a', 'c', 'd'])).toEqual({ added: 1, removed: 1 });
    expect(lineDiffStats([], ['x'])).toEqual({ added: 1, removed: 0 });
    expect(lineDiffStats(['x', 'x'], ['x', 'x'])).toEqual({ added: 0, removed: 0 });
  });
});
