import type { Example, Meta, MethodTrace, Row, Session, Snapshot, Step } from './types';

/** The backend answered, but not with the shape this frontend understands. */
export class ResponseShapeError extends Error {
  constructor(path: string, expected: string) {
    super(`unexpected response: ${path} should be ${expected}`);
    this.name = 'ResponseShapeError';
  }
}

type Obj = Record<string, unknown>;

function obj(v: unknown, path: string): Obj {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new ResponseShapeError(path, 'an object');
  return v as Obj;
}

function arr(v: unknown, path: string): unknown[] {
  if (!Array.isArray(v)) throw new ResponseShapeError(path, 'an array');
  return v;
}

function str(v: unknown, path: string): string {
  if (typeof v !== 'string') throw new ResponseShapeError(path, 'a string');
  return v;
}

function num(v: unknown, path: string): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new ResponseShapeError(path, 'a number');
  return v;
}

function bool(v: unknown, path: string): boolean {
  if (typeof v !== 'boolean') throw new ResponseShapeError(path, 'a boolean');
  return v;
}

function nullable<T>(v: unknown, path: string, check: (v: unknown, path: string) => T): T | null {
  return v === null || v === undefined ? null : check(v, path);
}

function oneOf<T extends string>(v: unknown, path: string, allowed: readonly T[]): T {
  const s = str(v, path);
  if (!(allowed as readonly string[]).includes(s)) throw new ResponseShapeError(path, `one of ${allowed.join(', ')}`);
  return s as T;
}

function counts(v: unknown, path: string): Record<string, number> {
  const o = obj(v, path);
  const out: Record<string, number> = {};
  for (const [k, n] of Object.entries(o)) out[k] = num(n, `${path}.${k}`);
  return out;
}

function rows(v: unknown, path: string): Row[] {
  return arr(v, path).map((r, i) => {
    const o = obj(r, `${path}[${i}]`);
    return { text: str(o.text, `${path}[${i}].text`), line: num(o.line, `${path}[${i}].line`) };
  });
}

function snapshot(v: unknown, path: string): Snapshot {
  const o = obj(v, path);
  return {
    ir: nullable(o.ir, `${path}.ir`, rows),
    bytecode: nullable(o.bytecode, `${path}.bytecode`, rows),
    loweringError: nullable(o.loweringError, `${path}.loweringError`, str),
    instructions: nullable(o.instructions, `${path}.instructions`, num),
    codeBytes: nullable(o.codeBytes, `${path}.codeBytes`, num),
    irBlocks: nullable(o.irBlocks, `${path}.irBlocks`, num),
    irInstructions: nullable(o.irInstructions, `${path}.irInstructions`, num),
  };
}

const STEP_KINDS = ['input', 'build', 'pass', 'decision'] as const;
const OUTCOMES = [
  'REPLACED', 'NOTHING_APPLIED', 'NO_GAIN', 'VERIFY_FAILED', 'UNSUPPORTED', 'INTERNAL_ERROR', 'CLASS_UNCHANGED',
] as const;

function step(v: unknown, path: string): Step {
  const o = obj(v, path);
  const s: Step = {
    index: num(o.index, `${path}.index`),
    kind: oneOf(o.kind, `${path}.kind`, STEP_KINDS),
    round: num(o.round, `${path}.round`),
    pass: nullable(o.pass, `${path}.pass`, str),
    changes: num(o.changes, `${path}.changes`),
    counters: counts(o.counters, `${path}.counters`),
    sameAsPrevious: bool(o.sameAsPrevious, `${path}.sameAsPrevious`),
    snapshot: nullable(o.snapshot, `${path}.snapshot`, snapshot),
  };
  if (!s.sameAsPrevious && s.snapshot === null) throw new ResponseShapeError(`${path}.snapshot`, 'present');
  return s;
}

function method(v: unknown, path: string): MethodTrace {
  const o = obj(v, path);
  const steps = arr(o.steps, `${path}.steps`).map((s, i) => step(s, `${path}.steps[${i}]`));
  if (steps.length < 2 || steps[0]?.kind !== 'input' || steps[steps.length - 1]?.kind !== 'decision')
    throw new ResponseShapeError(`${path}.steps`, 'input … decision');
  if (steps[0].snapshot === null) throw new ResponseShapeError(`${path}.steps[0].snapshot`, 'present');
  return {
    id: str(o.id, `${path}.id`),
    owner: str(o.owner, `${path}.owner`),
    name: str(o.name, `${path}.name`),
    desc: str(o.desc, `${path}.desc`),
    outcome: oneOf(o.outcome, `${path}.outcome`, OUTCOMES),
    outcomeDetail: nullable(o.outcomeDetail, `${path}.outcomeDetail`, str),
    steps,
    rounds: num(o.rounds, `${path}.rounds`),
    counters: counts(o.counters, `${path}.counters`),
  };
}

export function parseSession(v: unknown): Session {
  const o = obj(v, 'session');
  const comp = obj(o.compilation, 'session.compilation');
  const timings = obj(o.timings, 'session.timings');
  const summary = nullable(o.summary, 'session.summary', (s, p) => {
    const so = obj(s, p);
    const n = (k: string): number => num(so[k], `${p}.${k}`);
    return {
      classes: n('classes'), classesRewritten: n('classesRewritten'), methods: n('methods'),
      methodsReplaced: n('methodsReplaced'), instructionsBefore: n('instructionsBefore'),
      instructionsAfter: n('instructionsAfter'), codeBytesBefore: n('codeBytesBefore'),
      codeBytesAfter: n('codeBytesAfter'), fileBytesBefore: n('fileBytesBefore'),
      fileBytesAfter: n('fileBytesAfter'), counters: counts(so.counters, `${p}.counters`),
    };
  });
  return {
    status: oneOf(o.status, 'session.status', ['optimized', 'compile-error'] as const),
    compilation: {
      fileName: str(comp.fileName, 'session.compilation.fileName'),
      release: str(comp.release, 'session.compilation.release'),
      diagnostics: arr(comp.diagnostics, 'session.compilation.diagnostics').map((d, i) => {
        const p = `session.compilation.diagnostics[${i}]`;
        const dob = obj(d, p);
        return {
          kind: oneOf(dob.kind, `${p}.kind`, ['error', 'warning', 'note'] as const),
          line: num(dob.line, `${p}.line`),
          column: num(dob.column, `${p}.column`),
          text: str(dob.text, `${p}.text`),
        };
      }),
    },
    passes: arr(o.passes, 'session.passes').map((p, i) => str(p, `session.passes[${i}]`)),
    disabled: arr(o.disabled, 'session.disabled').map((p, i) => str(p, `session.disabled[${i}]`)),
    classes: arr(o.classes, 'session.classes').map((c, i) => {
      const p = `session.classes[${i}]`;
      const co = obj(c, p);
      return {
        name: str(co.name, `${p}.name`),
        fileBytesBefore: num(co.fileBytesBefore, `${p}.fileBytesBefore`),
        fileBytesAfter: num(co.fileBytesAfter, `${p}.fileBytesAfter`),
        rewritten: bool(co.rewritten, `${p}.rewritten`),
        unchangedReason: nullable(co.unchangedReason, `${p}.unchangedReason`, str),
        verification: oneOf(co.verification, `${p}.verification`, ['passed', 'failed', 'skipped'] as const),
        verificationDetail: nullable(co.verificationDetail, `${p}.verificationDetail`, str),
      };
    }),
    methods: arr(o.methods, 'session.methods').map((m, i) => method(m, `session.methods[${i}]`)),
    methodsNotTraced: num(o.methodsNotTraced, 'session.methodsNotTraced'),
    summary,
    warnings: arr(o.warnings, 'session.warnings').map((w, i) => str(w, `session.warnings[${i}]`)),
    timings: {
      compileMs: num(timings.compileMs, 'session.timings.compileMs'),
      optimizeMs: num(timings.optimizeMs, 'session.timings.optimizeMs'),
      verifyMs: num(timings.verifyMs, 'session.timings.verifyMs'),
    },
  };
}

export function parseMeta(v: unknown): Meta {
  const o = obj(v, 'meta');
  return {
    version: str(o.version, 'meta.version'),
    release: str(o.release, 'meta.release'),
    maxSourceChars: num(o.maxSourceChars, 'meta.maxSourceChars'),
    passes: arr(o.passes, 'meta.passes').map((p, i) => {
      const po = obj(p, `meta.passes[${i}]`);
      return {
        id: str(po.id, `meta.passes[${i}].id`),
        name: str(po.name, `meta.passes[${i}].name`),
        summary: str(po.summary, `meta.passes[${i}].summary`),
      };
    }),
    rewrites: arr(o.rewrites, 'meta.rewrites').map((r, i) => {
      const ro = obj(r, `meta.rewrites[${i}]`);
      return {
        pass: str(ro.pass, `meta.rewrites[${i}].pass`),
        counter: str(ro.counter, `meta.rewrites[${i}].counter`),
        explanation: str(ro.explanation, `meta.rewrites[${i}].explanation`),
        example: str(ro.example, `meta.rewrites[${i}].example`),
      };
    }),
  };
}

export function parseExamples(v: unknown): Example[] {
  return arr(v, 'examples').map((e, i) => {
    const p = `examples[${i}]`;
    const o = obj(e, p);
    return {
      id: str(o.id, `${p}.id`),
      title: str(o.title, `${p}.title`),
      concept: str(o.concept, `${p}.concept`),
      description: str(o.description, `${p}.description`),
      expected: arr(o.expected, `${p}.expected`).map((x, j) => str(x, `${p}.expected[${j}]`)),
      source: str(o.source, `${p}.source`),
    };
  });
}
