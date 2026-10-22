import type { Outcome, PassInfo, Step } from '../api/types';

const PASS_COLOR: Record<string, string> = {
  fold: 'var(--pass-fold)',
  peephole: 'var(--pass-peephole)',
  dse: 'var(--pass-dse)',
  cfg: 'var(--pass-cfg)',
};

export function passColor(pass: string | null): string {
  return (pass && PASS_COLOR[pass]) ?? 'var(--pass-neutral)';
}

export function passName(passes: readonly PassInfo[], id: string | null): string {
  return passes.find((p) => p.id === id)?.name ?? id ?? '';
}

export function stepTitle(step: Step, passes: readonly PassInfo[]): string {
  switch (step.kind) {
    case 'input':
      return 'javac output';
    case 'build':
      return 'IR built';
    case 'decision':
      return 'Decision';
    case 'pass':
      return passName(passes, step.pass);
  }
}

/** One-line description of what a non-pass step is. Pass steps use the catalog's pass summary. */
export const STEP_DESCRIPTIONS: Record<Exclude<Step['kind'], 'pass'>, string> = {
  input:
    'The bytecode javac produced for this method (--release 21, with line numbers). This is what jopt reads.',
  build:
    'IRBuilder turned the bytecode into basic blocks with one terminator each. The IR stays stack-based but every ' +
    'constant push becomes CONST. The bytecode view is this IR lowered by jopt\'s IRWriter before any pass ran, so ' +
    'differences from javac come only from the writer\'s encoding and block layout.',
  decision:
    'After the last round, jopt lowers the IR to bytecode and keeps it only if it is smaller and passes ASM\'s ' +
    'bytecode verifier. This step shows the code that ends up in the class file.',
};

export type Tone = 'good' | 'bad' | 'warn' | 'neutral';

export const OUTCOMES: Record<Outcome, { label: string; tone: Tone; explanation: string }> = {
  REPLACED: {
    label: 'Optimized',
    tone: 'good',
    explanation: 'The rewritten code was smaller and passed ASM\'s bytecode verifier, so it replaced javac\'s code.',
  },
  NOTHING_APPLIED: {
    label: 'Unchanged',
    tone: 'neutral',
    explanation: 'No pass found anything to rewrite, so the method keeps javac\'s code and its debug info.',
  },
  NO_GAIN: {
    label: 'Kept original',
    tone: 'warn',
    explanation: 'The passes changed the IR, but the lowered code was not smaller, so jopt kept javac\'s code.',
  },
  VERIFY_FAILED: {
    label: 'Verification failed',
    tone: 'bad',
    explanation: 'The rewritten code failed ASM\'s bytecode verifier, so jopt kept javac\'s code.',
  },
  UNSUPPORTED: {
    label: 'Unsupported',
    tone: 'neutral',
    explanation: 'The method uses jsr/ret subroutines, which jopt\'s IR does not support; it is left as is.',
  },
  INTERNAL_ERROR: {
    label: 'Internal error',
    tone: 'bad',
    explanation: 'The optimizer failed unexpectedly on this method and kept javac\'s code.',
  },
  CLASS_UNCHANGED: {
    label: 'Class not rewritten',
    tone: 'warn',
    explanation: 'The method was optimized, but its class could not be written back, so the original class was kept.',
  },
};
