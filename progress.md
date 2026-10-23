# JOpt Playground — Engineering & Refinement Progress

Persistent source of truth for the cross-platform engineering, UI/UX refinement, and documentation of JOpt.

## Project Summary

JOpt is a Java bytecode optimizer and interactive compilation playground. It compiles Java source in-memory with `javac --release 21`, constructs a CFG IR of basic blocks, runs cooperative optimization rounds (`fold`, `peephole`, `dse`, `cfg`) until reaching a fixed point, verifies output methods with ASM and HotSpot, and serves an interactive web playground for step-by-step replay and inspection.

---

## 1. Cross-Platform Compatibility

- [x] **Audit platform dependencies & path assumptions**:
  - `gradlew` script permissions: previously checked in as non-executable (`100644`); updated git index and file mode to executable (`100755` via `git update-index --chmod=+x gradlew`).
  - Added `.gitattributes` to enforce `eol=lf` for Unix/macOS scripts and text files while preserving `eol=crlf` for Windows `.bat`/`.cmd` files.
  - Path separator normalization: checked `ClassFiles.java` and `PlaygroundServer.java` for portable path handling (`replace('\\', '/')`, NIO `Path.resolve`, and `File.pathSeparator`).
- [x] **Runtime version standards**:
  - Java runtime standard: JDK 21 LTS (`toolchain { languageVersion = JavaLanguageVersion.of(21) }` in `build.gradle`).
  - Node runtime standard: Node 20 LTS (documented in `.nvmrc` and `.node-version`).
- [x] **Environment configuration**:
  - Added [`.env.example`](.env.example) documenting `PORT`, `JOPT_PORT`, `JOPT_BACKEND`, `JAVA_HOME`, and `VITE_PORT`.
  - Backend `Main.java` playground subcommand updated to check `PORT` and `JOPT_PORT` environment variables with CLI `--port` override.
  - Gradle `playground` task in `build.gradle` updated to support `-PplaygroundPort` as well as `PORT` and `JOPT_PORT` environment variables.
- [x] **Portable E2E Test Configuration**:
  - Updated `web/playwright.config.ts` to select `jopt.bat` on Windows (`win32`) vs `jopt` on POSIX.
  - Portable `JAVA_HOME` resolution in `playwright.config.ts`: handles macOS bundle structures (`Contents/Home`) and standard Linux/Windows JDK directories.
  - Quoted command paths to prevent whitespace path errors on Windows.
- [x] **Convenience project scripts**:
  - Added root `package.json` with cross-platform npm scripts (`npm run build`, `npm run dev`, `npm run test`, `npm run lint`, `npm run typecheck`, `npm run test:e2e`).

---

## 2. Frontend UI/UX Refinement

- [x] **Visual Hierarchy & Boundaries**:
  - Topbar given distinct surface styling (`--surface`) and bottom border to clearly separate application chrome from the optimization workspace.
  - Panels given subtle shadow elevation and crisp border definition in both dark and light themes.
  - Workspace layout padded evenly (`6px`) around all panels.
- [x] **Optimization Pipeline Stages**:
  - Visual status for all stages: upcoming (muted/semi-transparent), past (filled dot), current (accent border + accent left bar + bold text), and no-op (muted title with change indicator).
  - Selected stage highlighted unmistakably via `box-shadow: inset 3px 0 0 var(--pass-color)`.
  - Visual round grouping with fixed-point status badges ("fixed point: nothing changed" / "N unchanged hidden").
- [x] **Transformation Inspector & Code Comparison**:
  - Clear content hierarchy: Eyebrow (method + step + round), Title with pass dot, status badges, pass description.
  - Transformation cards with count badges (`1×`), counter titles, documentation text, and syntax-highlighted rewrite examples.
  - Metric strip comparing Before → After values with delta percentages.
  - Diff view column headers (`.codebox__labels`) elevated with distinct background, bold uppercase styling, and crisp separation.
  - Seamless toggle between side-by-side and inline diffs, Bytecode vs. IR representations, and diff vs input or previous step.
- [x] **WAI-ARIA Roving Tabindex & Accessibility**:
  - Completed roving-tabindex arrow-key navigation in `Segmented` radiogroup controls (`web/src/ui/controls.tsx`).
  - Implemented roving-tabindex arrow-key navigation for Bottom Panel tabs (`Problems`, `Summary`, `Classes`, `Decisions`) in `web/src/components/BottomPanel.tsx`.
  - Implemented roving-tabindex arrow-key navigation for Narrow layout tabs (`Source`, `Pipeline`, `Inspector`) in `web/src/App.tsx`.
  - Global step shortcuts (`→`, `←`, `Home`, `End`, `Space`) cleanly bypass focused tabs, radios, editors, and dialogs.

---

## 3. Documentation & Guides

- [x] **Complete Root README.md**:
  - Professional overview explaining JOpt's purpose, problem statement, and user audience.
  - Mermaid architecture dataflow diagram detailing Frontend ↔ Backend ↔ Core Optimizer.
  - Optimization pass table explaining `fold`, `peephole`, `dse`, and `cfg`.
  - Platform support matrix distinguishing locally verified vs CI-verified platforms.
  - Step-by-step installation and execution instructions for macOS, Linux, and Windows.
  - Environment variable reference matching `.env.example`.
  - Comprehensive usage guide covering both the Interactive Playground and the CLI.
  - Complete test and verification commands.
  - Transparent security boundaries, trust models, known limitations, and troubleshooting tips.

---

## 4. Automated Cross-Platform CI

- [x] **GitHub Actions Workflow** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)):
  - Matrix testing across 3 operating systems: `ubuntu-latest`, `macos-latest`, `windows-latest`.
  - Matrix testing with Java 21 (Temurin) and Node 20 LTS.
  - Gradle test execution with platform-appropriate wrappers (`./gradlew` on POSIX, `gradlew.bat` on Windows).
  - CLI distribution build (`installDist`).
  - Frontend checks: `npm ci`, `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`.

---

## 5. Verification Results

All tests executed and verified in the current environment (macOS 15+ aarch64, JDK 21.0.12, Node v24.18.0):

| Test Suite | Total Tests | Passed | Failed | Execution Time | Notes |
|---|---|---|---|---|---|
| **Java Gradle Tests** (`./gradlew test`) | 75 | **75** | 0 | 2.1s | Differential (26 algorithms × 2,000 runs), Golden bytecode, Real-world ASM library, HTTP API tests |
| **Frontend Unit Tests** (`npm test`) | 36 | **36** | 0 | 0.5s | Reducer transitions, boundary checks, replay timeline, stale result guards, metric calculation |
| **Frontend TypeScript** (`npm run typecheck`) | — | **0 errors** | 0 | 1.8s | Strict type-checking across `web/src/` and `web/vite.config.ts` |
| **Frontend ESLint** (`npm run lint`) | — | **0 warnings**| 0 | 0.9s | Clean linting with react-hooks and typescript-eslint |
| **Production Build** (`npm run build`) | — | **Success** | 0 | 3.3s | Emitted optimized bundle in `web/dist/` with split Monaco chunks |
| **Playwright E2E Tests** (`npm run e2e`) | 7 | **7** | 0 | 12.1s | Full user journey, timeline replay, compile error handling, empty input guard, 500 retry recovery, stale input banner, narrow screen responsive view |
| **Interactive Server** (`./gradlew playground`) | — | **Verified** | 0 | — | Server verified healthy on `http://localhost:7070/api/health`, serving production UI |

---

## Known Limitations

1. **Replay Snapshots**: The optimizer executes synchronously in a few milliseconds; the timeline replays recorded snapshots.
2. **Size Optimization**: Reductions target bytecode instruction count and code bytes; runtime performance on HotSpot JIT is largely unchanged due to C2 runtime optimizations.
3. **Single-Method Scope**: Optimizations are strictly intraprocedural (no cross-method inlining).
4. **Local Single-User Security**: Binds to loopback (`127.0.0.1`) only; not designed for untrusted multi-tenant hosting.
