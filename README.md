# jopt — a Java bytecode optimizer

`jopt` reads compiled Java code (`.jar` files, class directories or single `.class` files) and
rewrites each method to be smaller and simpler while behaving exactly the same. It builds a
control-flow graph of every method, runs classic compiler optimizations over it, checks the result
with a bytecode verifier, and writes new class files.

`javac` does almost no optimization: it translates source code nearly literally. For example:

```java
int scale = 100;                  // a "constant" held in a local variable
boolean debug = false;
int r = x * 8;
if (debug) System.out.println(r); // dead code, still compiled
i = i + 1;                        // compiled as load, push 1, add, store
```

`jopt` turns that into `r = x << 3`, deletes the `if` and its body, removes the unused locals, and
emits `iinc` for the increment.

## Quick start

Requires JDK 21 (the Gradle wrapper downloads Gradle itself).

```sh
./gradlew demo        # optimize the sample app, verify it, run both versions, write build/demo/report.html
./gradlew test        # differential and real-world tests
./gradlew installDist # builds the CLI in build/install/jopt/bin/jopt
```

```sh
jopt optimize app.jar -o app-opt.jar --report report.html
jopt show app.jar com.example.Foo bar     # one method, before and after, side by side
jopt verify app-opt.jar                   # load and link every class (runs HotSpot's verifier)
jopt playground --port 7070               # interactive playground (see below)
```

Options: `--cp a.jar;b.jar` adds libraries the input depends on (needed to compute stack map frames
for classes that use types from them). `--disable fold,peephole,dse,cfg` switches passes off.

## Interactive playground

A local web app for watching the optimizer work: write Java (or pick one of ten examples), press
**Optimize**, and replay every pass jopt ran on each method, with bytecode/IR diffs, the pass's own
counts, explanations, measured sizes, and links from each instruction back to its source line.

```sh
cd web && npm install && npm run build && cd ..   # build the frontend once (Node 20+)
./gradlew playground                              # http://localhost:7070
```

For frontend development, run `./gradlew playground` and `npm run dev` in `web/` (Vite on
http://localhost:5173, proxying `/api` to port 7070).

What is real and what is not:

- **Everything shown comes from the actual optimizer.** The backend compiles the source with the
  JDK's `javac` (`--release 21`), runs jopt's `Optimizer` with a trace hook that snapshots the IR
  after **every pass run**, lowers each snapshot with jopt's own `IRWriter`, and finally loads and
  links the output classes in HotSpot (its bytecode verifier runs; static initializers do not).
- **Replay, not live execution.** jopt optimizes a method in a few milliseconds; the timeline replays
  the recorded snapshots. Pass runs that changed nothing can be shown or hidden.
- **Sizes, not speed.** Instruction counts and code bytes are measured. There are no runtime
  benchmarks: the playground never runs your code.
- Explanations are documentation keyed by the counter names the passes emit
  ([`Catalog`](src/main/java/jopt/playground/Catalog.java)); a test checks every counter the examples
  produce is documented.

Trust boundary: the server listens on 127.0.0.1 only, rejects non-loopback `Host` headers, limits
source size (64 KiB), run time (20 s) and concurrency (2 runs), compiles with annotation processing
off and an empty class path, and never executes submitted code. It is a local developer tool, not a
hardened public service. Requires a JDK (not a JRE) because it uses `javac`.

API (all responses are `{"ok": true, "data": ...}` or `{"ok": false, "error": {"code", "message"}}`):

| Endpoint | |
|---|---|
| `GET /api/meta` | passes and the explanation catalog |
| `GET /api/examples` | example programs with the transformations they demonstrate |
| `POST /api/optimize?disable=fold,dse` | body: Java source (UTF-8 text); returns the recorded session |

Tests: `./gradlew test` (includes the playground service, HTTP API and a check that tracing does not
change optimizer output), `npm test` (reducer, metrics, response validation), `npm run lint`,
`npm run typecheck`, and `npm run e2e` (Playwright, system Chrome, against the real backend; needs
`./gradlew installDist` and `npm run build` first).

## How it works

```
 .class / .jar ─► ASM reader ─► IRBuilder ─► CFG ─► passes (until nothing changes) ─► IRWriter ─► verifier ─► ASM writer ─► .class / .jar
```

ASM is used only to parse and serialize class files. Everything in between is part of this project.

**IR** ([`jopt.ir`](src/main/java/jopt/ir)). Each method becomes a list of basic blocks. A block is
straight-line instructions plus one terminator (`Goto`, `If`, `Switch`, `Return`, `Throw`), and
records which exception handlers cover it. The IR stays stack-based like bytecode, so it maps back to
class files without register allocation, but it normalizes encodings: `iconst_2`, `bipush 100` and
`ldc 70000` are all one `Const` instruction. The writer chooses the shortest encoding, lays out blocks
so that fall-through replaces jumps where possible, inverts branch conditions when that saves a
`goto`, picks `tableswitch` or `lookupswitch` by cost, and rebuilds the exception table.

**Analyses** ([`jopt.analysis`](src/main/java/jopt/analysis)).
- *Constant propagation* is a forward dataflow analysis over the CFG, running an abstract interpreter
  that tracks which locals and stack slots hold known constants. When a branch condition is known,
  only the taken edge is followed, so dead code does not dilute what is known at the join.
- *Liveness* is a backward analysis over local variable slots. Inside a `try` block, every slot the
  handler reads counts as live at every instruction, since any of them could throw.
- Constant folding follows exact JVM semantics: wrapping overflow, shift distances masked to 5/6 bits,
  `Integer.MIN_VALUE / -1`, IEEE floats, NaN-aware comparisons. Integer division by zero is never
  folded, so it still throws.

**Passes** ([`jopt.opt`](src/main/java/jopt/opt)). These are repeated until a full round changes
nothing.

| Pass | Does |
|---|---|
| `fold`: constant folding | replaces loads of constant locals with the constant, evaluates constant arithmetic, turns branches and switches on constants into `goto` |
| `peephole` | removes push-then-pop pairs and unused pure computations; algebraic identities (`x+0`, `x*1`, `x\|0`, `--x`); strength reduction (`x*8` → `x<<3`); `i = i + k` → `iinc`; `store x; load x` → `dup; store x`; comparisons against `0`/`null` → `ifeq`/`ifnull` |
| `dse`: dead-store elimination | a store to a variable that is never read again becomes a `pop` (which the peephole pass then cancels against the value's push) |
| `cfg`: CFG simplification | jump threading, removing unreachable blocks, merging a block into its only predecessor |

The passes are small because they cooperate. Constant folding never needs to find where an operand
was pushed: it emits `pop; pop; push result`, and the peephole pass deletes the leftover
`push; pop` pairs. Likewise, dead-store elimination plus the `store/load → dup` rewrite add up to copy
propagation.

## Correctness

An optimizer that breaks code is worse than none, so there are several layers of protection:

1. **Every rewritten method is checked by ASM's bytecode verifier.** If it fails, the original method
   is kept and a warning is reported. Any unexpected exception inside the optimizer is handled the
   same way.
2. **A method is only replaced if it got smaller.** Otherwise the original is kept, debug info included.
3. **`jopt verify`** loads every output class into a fresh class loader and links it, which makes the
   JVM's own verifier check every method.
4. **Tests** ([`src/test`](src/test/java/jopt)):
   - *Differential:* 26 sample methods covering loops, switches, try/catch/finally, `synchronized`,
     longs, doubles, `-0.0f`, NaN, division by zero, out-of-bounds array access and masked shifts.
     Each is run 2,000 times on random and edge-case inputs, and the original and optimized versions
     must return the same value or throw the same exception.
   - *Golden output:* tests check that the expected optimizations actually happen, e.g.
     `constantLocals` compiles to exactly `iload_0; bipush 15; imul; ireturn`.
   - *Real world:* the whole ASM library jar is optimized and must pass the JVM verifier. The
     optimized ASM is then used to rewrite every class file in that jar, and its output must be
     byte-for-byte identical to the original ASM's.
   - The link check is shown to reject deliberately broken bytecode, so a passing check means
     something.

## Results

**Demo app** ([`src/demo`](src/demo/java/demo)), written the way quick application code often looks:

| | before | after | change |
|---|---|---|---|
| instructions | 598 | 477 | −20.2% |
| bytecode | 1,101 B | 895 B | −18.7% |
| methods optimized | | 11 of 18 | |

Output is identical, and all classes pass HotSpot's verifier.

**Real libraries**, as published on Maven Central:

| jar | classes | methods optimized | bytecode | class files | verifier |
|---|---|---|---|---|---|
| commons-math3 3.6.1 | 1,301 | 984 | −0.8% | −5.7% | 1,301 / 1,301 pass |
| jmh-core 1.37 | 332 | 240 | −1.2% | −6.0% | 289 pass, 43 need libraries not given |
| jqwik-engine 1.9.2 | 519 | 303 | −1.0% | −3.1% | 132 pass, 360 need libraries not given |

Notes on these numbers:
- **Library code is already tidy, so bytecode gains are around 1%.** The big wins come from code with
  dead configuration branches, constants held in locals, and temporaries.
- **Most of the class-file reduction comes from rewriting classes, not from the optimizations.** A
  rewritten class gets a fresh, compacted constant pool. Optimized methods also lose their
  local-variable debug table, which describes slots and ranges that no longer exist; line numbers are
  kept.
- **A bytecode optimizer barely changes peak performance on HotSpot.** The C2 JIT compiler performs
  these optimizations itself at run time. The benefits are smaller downloads, faster startup and
  interpreted execution, and environments with weaker or no JIT compilers (Android's D8/ART, GraalVM
  native image, embedded VMs).

## Limitations and next steps

- Optimizations are intraprocedural: no inlining, and no constant propagation through `static final`
  fields that `javac` did not already fold.
- Local-variable debug tables are dropped for optimized methods.
- Classes compiled with `jsr`/`ret` subroutines (pre-Java 6) are left unchanged.
- Ideas: method inlining for small private/static methods, removing unused private methods and
  fields, a register-based SSA IR for global value numbering and loop-invariant code motion.

## Project layout

```
src/main/java/jopt/
  Main.java            CLI: optimize, show, verify, demo
  ir/                  IR (Insn, Terminator, Block, MethodIR), IRBuilder, IRWriter, stack effects
  analysis/            constant lattice + abstract interpreter, dataflow solver, constant evaluator
  opt/                 passes, the fixed-point driver (Optimizer), report model
  io/                  jar/dir I/O, class hierarchy for frame computation, link check, listings
  report/              HTML report
  playground/          playground backend: javac wrapper, trace recorder, HTTP API, examples
src/main/resources/jopt/playground/examples/   example programs for the playground
web/                   playground frontend (Vite + React + TypeScript + Monaco)
src/demo/java/demo/    sample app optimized by `./gradlew demo`
src/test/java/jopt/    differential, golden-output, real-world and unit tests
```
