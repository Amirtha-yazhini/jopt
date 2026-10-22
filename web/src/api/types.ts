/**
 * Shapes returned by the Java backend (`jopt.playground.Model`). Every number here is recorded or
 * measured by the backend; the frontend never estimates optimizer results.
 */

export interface Row {
  text: string;
  /** Source line from the class file's line number table, or -1. */
  line: number;
}

export interface Snapshot {
  /** IR text; null for the javac input step. */
  ir: Row[] | null;
  /** Bytecode listing: javac output for "input", otherwise the IR lowered by jopt's IRWriter. */
  bytecode: Row[] | null;
  loweringError: string | null;
  instructions: number | null;
  codeBytes: number | null;
  irBlocks: number | null;
  irInstructions: number | null;
}

export type StepKind = 'input' | 'build' | 'pass' | 'decision';

export interface Step {
  index: number;
  kind: StepKind;
  round: number;
  pass: string | null;
  changes: number;
  counters: Record<string, number>;
  sameAsPrevious: boolean;
  snapshot: Snapshot | null;
}

export type Outcome =
  | 'REPLACED'
  | 'NOTHING_APPLIED'
  | 'NO_GAIN'
  | 'VERIFY_FAILED'
  | 'UNSUPPORTED'
  | 'INTERNAL_ERROR'
  | 'CLASS_UNCHANGED';

export interface MethodTrace {
  id: string;
  owner: string;
  name: string;
  desc: string;
  outcome: Outcome;
  outcomeDetail: string | null;
  steps: Step[];
  rounds: number;
  counters: Record<string, number>;
}

export interface Diagnostic {
  kind: 'error' | 'warning' | 'note';
  line: number;
  column: number;
  text: string;
}

export interface ClassResult {
  name: string;
  fileBytesBefore: number;
  fileBytesAfter: number;
  rewritten: boolean;
  unchangedReason: string | null;
  verification: 'passed' | 'failed' | 'skipped';
  verificationDetail: string | null;
}

export interface Summary {
  classes: number;
  classesRewritten: number;
  methods: number;
  methodsReplaced: number;
  instructionsBefore: number;
  instructionsAfter: number;
  codeBytesBefore: number;
  codeBytesAfter: number;
  fileBytesBefore: number;
  fileBytesAfter: number;
  counters: Record<string, number>;
}

export interface Session {
  status: 'optimized' | 'compile-error';
  compilation: { fileName: string; release: string; diagnostics: Diagnostic[] };
  passes: string[];
  disabled: string[];
  classes: ClassResult[];
  methods: MethodTrace[];
  methodsNotTraced: number;
  summary: Summary | null;
  warnings: string[];
  timings: { compileMs: number; optimizeMs: number; verifyMs: number };
}

export interface PassInfo {
  id: string;
  name: string;
  summary: string;
}

export interface Rewrite {
  pass: string;
  counter: string;
  explanation: string;
  example: string;
}

export interface Meta {
  version: string;
  release: string;
  maxSourceChars: number;
  passes: PassInfo[];
  rewrites: Rewrite[];
}

export interface Example {
  id: string;
  title: string;
  concept: string;
  description: string;
  expected: string[];
  source: string;
}
