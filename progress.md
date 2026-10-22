# JOpt Playground — progress

Persistent source of truth for the interactive playground work. Another session should be able to
resume from the unchecked items below.

## Objective

Turn JOpt (a Java bytecode optimizer) into an interactive playground: the user writes Java source,
the backend compiles it with `javac`, runs the **real** JOpt optimizer over every method while
recording a snapshot after every pass execution, and the frontend replays those recorded snapshots
step by step with diffs, explanations and measured size metrics.

## Phase 0 — baseline findings (verified)

- Repo: single Gradle project (`build.gradle`, Gradle wrapper 8.10.2), Java 21 toolchain, ASM 9.7.1.
  No frontend, no HTTP API, no JSON library. CLI in `jopt.Main` (`optimize`, `show`, `verify`, `demo`).
- Input format: compiled class files (`.class`, `.jar`, dirs). **Not** Java source. The playground
  therefore needs a compile step (`javax.tools.JavaCompiler`, in-process) before optimizing.
- Pipeline (`jopt.opt.Optimizer.optimizeMethod`):
  `MethodNode` → `IRBuilder.build` (CFG of basic blocks, stack-based IR, `Insn.Line` markers kept)
  → rounds of `[fold, peephole, dse, cfg]` until a full round changes nothing (max 32 rounds)
  → `IRWriter.toMethodNode` → accepted only if smaller (bytes, then instruction count)
  → ASM `BasicVerifier` → replace method. Class re-emitted with `COMPUTE_FRAMES`.
- Passes mutate one shared `MethodIR` in place, sequentially. No snapshots, no undo.
- Statistics: `Counters` (named event counts emitted by each pass), `Bytecode.instructions` /
  `Bytecode.bytes` (code-array size; exact except it assumes `ldc` never needs `ldc_w`).
- Counter names emitted by passes (authoritative metadata):
  - fold: `loads replaced by constants`, `expressions folded`, `branches folded`, `switches folded`
  - peephole: `unused values removed`, `unused computations removed`, `algebraic identities`,
    `redundant load/store removed`, `store/load pairs`, `iinc formed`, `strength reductions`,
    `compare-with-zero branches`
  - dse: `dead stores removed`
  - cfg: `jumps threaded`, `branches folded`, `switches folded`, `unreachable blocks removed`, `blocks merged`
- Source mapping: javac line numbers survive in the IR as `Insn.Line` and in output as
  `LineNumberTable` → instruction→source-line mapping is real data.
- `LinkCheck` loads classes with `Class.forName(name, false, loader)` + `getDeclaredMethods()`:
  links/verifies with HotSpot **without running static initializers** (no user code executes).
- Tests: JUnit 5 — `UnitTest`, `SamplesTest` (differential + golden), `RealWorldTest`.

### Environment notes
- Machine JDK is 26; Gradle 8.10.2 cannot run on it ("Unsupported class file major version 70")
  and no JDK 21 was installed. Fix used: Temurin JDK 21.0.12 extracted into the gitignored
  `.tools/` directory; run Gradle with `JAVA_HOME=$PWD/.tools/jdk-21.0.12.1+1/Contents/Home`.
- `gradlew` is not executable in this checkout; invoke as `sh gradlew ...` (file mode not changed).

### Baseline results
- `sh gradlew test` (JDK 21): **41 passed, 0 failed** — BUILD SUCCESSFUL.

## Capability matrix

| Feature | Status | Notes |
|---|---|---|
| Accept Java source | REQUIRES IMPLEMENTATION | in-process `javac` (`-proc:none`, no execution) |
| Compiler diagnostics with line/column | REQUIRES IMPLEMENTATION | from `DiagnosticCollector` |
| Per-pass snapshots | REQUIRES IMPLEMENTATION | optional trace hook in `Optimizer`, no behavior change |
| IR (CFG blocks) view per step | DERIVABLE | printer over real `MethodIR` |
| Bytecode listing per step | DERIVABLE | lower the current IR with the real `IRWriter` |
| Per-pass transformation counts | SUPPORTED | `Counters`, captured per pass run |
| Transformation explanations | DERIVABLE | static docs keyed by the counter names passes emit |
| Instruction / code-byte metrics | SUPPORTED | `Bytecode.instructions/bytes` |
| Acceptance decision (smaller? verified?) | REQUIRES IMPLEMENTATION | surface the existing decision reasons |
| HotSpot link/verify of output | SUPPORTED | `LinkCheck` (no static init) |
| Instruction → source line mapping | DERIVABLE | `LineNumberNode` / `Insn.Line` |
| Live incremental execution | UNSUPPORTED (by design) | optimizer is synchronous; UI replays recorded snapshots |
| Runtime benchmarks / speedup | UNSUPPORTED | would require executing untrusted code; not claimed |
| Running original vs optimized program | UNSUPPORTED | same reason; differential testing stays in JUnit |
| SSA / register IR, inlining, LICM | UNSUPPORTED | not implemented by JOpt |

## Architecture decisions

1. **Backend = JDK built-in `com.sun.net.httpserver`** in a new `jopt.playground` package and a
   `jopt playground` CLI subcommand. No new runtime dependencies. Binds to 127.0.0.1 only.
2. **Trace hook, not a forked optimizer**: `Optimizer` gets an optional `OptimizerTrace` listener
   called after IR build, after every pass run, and with the final decision. Default = no tracing,
   identical behavior (existing tests guard this).
3. **Snapshots are taken from the real IR** after each pass: IR text + lowered bytecode listing
   (via the project's `IRWriter`) + measured sizes.
4. **API**: `GET /api/meta`, `GET /api/examples`, `POST /api/optimize?disable=…` (body = Java source,
   max 64 KiB, compile/optimize time-limited). Hand-written JSON writer (output only).
5. **Frontend = Vite + React + TypeScript** in `web/`, Monaco editor (bundled locally, no CDN) with
   built-in diff editor, `react-resizable-panels`. Session state = pure reducer (unit-tested).
   Request ids guard against stale responses.
6. Trust boundary: user code is compiled and verified but **never executed**. Annotation processing
   disabled. Local single-user tool; not hardened for public hosting.

## Phases and tasks

### Phase 0 — discovery
- [x] Inspect repo, README, build, optimizer, passes, IR, tests
- [x] Inspect ECC config (rules in ~/.claude/rules/ecc incl. java/typescript; agents available)
- [x] Record baseline build/test results
- [x] Capability matrix

### Phase 1 — backend trace + API  ✅ (2026-10-22)
- [x] `OptimizerTrace` hook in `Optimizer` (no behavior change) — `src/main/java/jopt/opt/OptimizerTrace.java`;
      each pass now gets a fresh `Counters` merged into the method total (equivalent). Evidence:
      `OptimizerTraceTest` (traced vs untraced output byte-identical) + all 41 original tests pass.
- [x] `Bytecode.rows()` listing with source lines; `listing()` text unchanged (golden tests pass)
- [x] `IRPrinter` for MethodIR text (`jopt.playground`)
- [x] `SourceCompiler` (in-memory javac, `--release 21`, `-proc:none`, empty class path)
- [x] `TraceRecorder` + `PlaygroundService` (compile → traced optimize → HotSpot `LinkCheck`)
- [x] `Json` writer (records/maps/lists; escapes `<>&`)
- [x] `PlaygroundServer` (loopback-only, Host-header check, 256 KiB body limit, 20 s timeout,
      2 concurrent runs, CSP, path-traversal-safe static files) + `jopt playground` + `gradle playground`
- [x] 10 examples in `src/main/resources/jopt/playground/examples/`, each validated by
      `PlaygroundServiceTest.examplesDoWhatTheySay` against real optimizer counters + HotSpot verification
- [x] Tests: `PlaygroundServiceTest` (22), `PlaygroundServerTest` (9), `OptimizerTraceTest` (1)
- Result: `sh gradlew test` → **73 passed, 0 failed**.
- Observed real behavior worth showing in UI: ConstantLocals.scaled goes 14 → 16 (fold) → 10 (peephole)
  → 10 (dse) → 4 (peephole, round 2) instructions; 3 rounds, last round all zero changes.

### Phase 2–6 — frontend  ✅ (2026-10-22)
- [x] Vite 5 + React 18 + TS 5.6 in `web/`; dev proxy `/api` → :7070; Monaco bundled locally
      (`monaco/setup.ts`, `edcore.main` + Java grammar + custom `jvm` grammar for listings/IR)
- [x] Design tokens (`styles/tokens.css`), dark default + light theme, Inter + JetBrains Mono (self-hosted)
- [x] Source panel: Monaco editor, javac diagnostics as markers, examples menu with description and
      expected transformations, "Restore example", pass toggles, char limit validation, Optimize (⌘/Ctrl+↵)
- [x] API client (`api/client.ts`) with timeout, abort, normalized errors; runtime validation (`api/validate.ts`)
- [x] Session reducer (`state/session.ts`): idle/running/ready/error, request-id stale guard,
      replay navigation over visible steps, playback flag, speed, hide no-op runs, staleness check
- [x] Pipeline panel: method picker, replay note (states it is a recorded run), measured
      instructions-per-step chart, stage list grouped by round with fixed-point label, auto-scroll
- [x] Timeline: first/prev/play-pause/next/last, scrubber, speed; disabled at boundaries;
      keyboard ← → Home End Space, ? for help (ignored while typing in editors/fields)
- [x] Inspector: step header, pass summary, transformations (pass's own counts + catalog docs),
      measured before/after stat strip with definitions, decision callout with backend detail,
      Monaco diff (side-by-side/inline, vs previous / vs javac or first IR, +/− line stats,
      copy both sides, full screen), identical-code detection
- [x] Source linking: click an instruction → highlights its source line (LineNumberTable data);
      editor cursor → highlights matching instructions; disabled when results are stale
- [x] Bottom panel: Problems (clickable), Summary (totals + metric definitions + "sizes, not speed"),
      Classes (HotSpot verification), Decisions log (per-method outcome + warnings)
- [x] Empty/loading/error states: boot failure with retry, intro listing the real passes from
      `/api/meta`, running state, run error with retry, compile error, no methods, stale banner
- [x] Narrow layout (<1080px): tabs Source/Pipeline/Inspector, no horizontal overflow (E2E-checked)

### Phase 7–9 — polish, tests, docs
- [x] Vitest unit tests: 36 (reducer transitions/boundaries/playback/stale guard, percent change
      incl. zero denominators, descriptor parsing, response validation, client error normalization)
      using real backend responses captured as fixtures in `web/src/test/fixtures/`
- [x] Integration: Java `PlaygroundServerTest` (real HTTP) + Playwright journey against real backend
- [x] E2E (Playwright 1.49, system Chrome via `channel: 'chrome'`): 7 tests — full journey,
      replay, compile error, empty input, failed request + retry (the one stubbed response),
      stale results, narrow layout
- [x] Browser verification by hand in the built-in browser (1440×900 + 800px), dark and light
- [x] `npm run typecheck`, `npm run lint` clean; `npm run build` OK
- [x] README section; this file
- [x] Security review (ECC security-reviewer) addressed: bounded run pool (max 2) with the slot held
      until the work really ends; Origin check + required `X-Jopt-Client` header on POST (forces a CORS
      preflight); 10 s request-read limit, 8 request threads; diagnostics capped at 100; test that
      user code cannot see the server class path. No CRITICAL findings.
- [x] Frontend review (typescript-reviewer) addressed: markers/highlights re-applied after editor
      remounts; timeout/abort while reading a body classified correctly; shortcuts disabled after a
      failed run and ignored on tabs/radios/resize handles/sliders; live-region count matches the
      timeline; bottom tabs honest on compile errors; step buttons keep focus at boundaries.
- [ ] Not done: roving-tabindex arrow-key navigation inside the tab and radio groups (each item is
      its own tab stop for now).

## Known limitations
- Replay only: the optimizer is synchronous and fast, so "live" stepping is a replay of snapshots.
- No runtime benchmarks or program execution (by design: user code is never run).
- `codeBytes` uses jopt's own estimator (exact except `ldc_w`). Class-file size changes include
  constant-pool compaction and dropped local-variable tables, not just optimizations.
- Intermediate IR that cannot be lowered on its own shows a lowering error instead of bytecode
  (not observed in the examples, but handled).
- Tracing is capped at 200 methods per run; the rest are optimized without snapshots.
- Server timeouts answer 504, but javac/optimizer work in-process cannot be killed: a pathological
  input keeps one of the 2 run slots busy until it finishes (further runs get 503). A real kill switch
  needs a child JVM with `-Xmx` + `destroyForcibly()`; not implemented.
- Other local users on the same machine can reach the loopback server (no auth); single-user tool.
- Monaco is ~850 kB gzipped (lazy-split chunk); acceptable for a local dev tool.
- Environment: the repo's Gradle 8.10.2 cannot run on JDK 26; use a JDK 21 (`.tools/` here).

## Commands (verified 2026-10-22, macOS, JDK 21.0.12 in `.tools/`, Node 24)
```
export JAVA_HOME=$PWD/.tools/jdk-21.0.12.1+1/Contents/Home
sh gradlew test                  # 75 passed
sh gradlew installDist           # builds build/install/jopt/bin/jopt
cd web && npm install            # deps (pinned)
npm run typecheck && npm run lint && npm test   # clean, 36 passed
npm run build                    # web/dist
npm run e2e                      # 7 passed (needs installDist + build)
sh gradlew playground            # http://localhost:7070 (serves web/dist)
```

## Log

- 2026-10-22: Phase 0 complete. Baseline 41/41 tests pass on JDK 21 (local `.tools`).
- 2026-10-22: Phase 1 complete. Backend API + tracing; 73/73 tests pass. Starting frontend in `web/`.
- 2026-10-22: Frontend complete; 36 unit + 7 E2E tests pass; fixed page-width overflow (grid min-content) and stat-strip shrink found in browser testing. Reviews running.
- 2026-10-22: Review findings fixed. Final: Java 75/75, Vitest 36/36, Playwright 7/7, typecheck + lint clean, build OK, verified in browser.
