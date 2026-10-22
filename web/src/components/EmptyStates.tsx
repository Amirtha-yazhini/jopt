import { AlertTriangle, Loader2, RefreshCw } from 'lucide-react';
import type { ApiError } from '../api/client';
import type { Meta, Session } from '../api/types';
import { Kbd, MOD_KEY } from '../ui/controls';
import { passColor } from '../ui/vocabulary';

/** Before the first run: what will happen, using the real pass list from the backend. */
export function PipelineIntro({ meta }: { meta: Meta }) {
  return (
    <div className="intro">
      <h2 className="intro__title">What happens when you press Optimize</h2>
      <ol className="intro__steps">
        <li>
          <span className="intro__n">1</span>
          <div>
            <strong>javac</strong> compiles your source in memory (<code>--release {meta.release}</code>). Your code is
            never run.
          </div>
        </li>
        <li>
          <span className="intro__n">2</span>
          <div>
            <strong>IRBuilder</strong> turns each method's bytecode into a control-flow graph of basic blocks.
          </div>
        </li>
        <li>
          <span className="intro__n">3</span>
          <div>
            The passes run in rounds, in this order, until a whole round changes nothing:
            <ul className="intro__passes">
              {meta.passes.map((p) => (
                <li key={p.id} style={{ ['--pass-color' as string]: passColor(p.id) }}>
                  <span className="pass-dot" aria-hidden="true" />
                  <span className="mono">{p.id}</span> <span className="muted">— {p.name}</span>
                </li>
              ))}
            </ul>
          </div>
        </li>
        <li>
          <span className="intro__n">4</span>
          <div>
            <strong>IRWriter</strong> lowers the IR back to bytecode. jopt keeps it only if it is smaller and passes
            ASM's verifier; then HotSpot loads and verifies every output class.
          </div>
        </li>
      </ol>
      <p className="intro__foot small muted">
        jopt records a snapshot after every pass. Here you replay those snapshots step by step.
        Press <Kbd>{MOD_KEY}↵</Kbd> to start.
      </p>
    </div>
  );
}

export function Running() {
  return (
    <div className="state state--center" aria-busy="true">
      <Loader2 className="spin" size={20} aria-hidden="true" />
      <p>Compiling with javac and running jopt…</p>
      <p className="small muted">One request; the snapshots appear when the run has finished.</p>
    </div>
  );
}

export function RunError({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  return (
    <div className="state state--center" role="alert">
      <AlertTriangle size={20} className="tone-bad" aria-hidden="true" />
      <p className="state__title">
        {error.kind === 'network' ? 'The backend is not reachable' : error.kind === 'timeout' ? 'The run timed out' : 'The run failed'}
      </p>
      <p className="small muted state__msg">{error.message}</p>
      {error.status !== undefined && <p className="small mono muted">HTTP {error.status} · {error.code}</p>}
      <button type="button" className="btn" onClick={onRetry}>
        <RefreshCw size={14} aria-hidden="true" /> Try again
      </button>
    </div>
  );
}

export function CompileFailed({ session, onJumpToLine }: { session: Session; onJumpToLine: (line: number) => void }) {
  const errors = session.compilation.diagnostics.filter((d) => d.kind === 'error');
  return (
    <div className="state" role="alert">
      <p className="state__title">
        <AlertTriangle size={16} className="tone-bad" aria-hidden="true" /> javac reported {errors.length} error
        {errors.length === 1 ? '' : 's'}; nothing was optimized.
      </p>
      <ul className="problems">
        {errors.map((d, i) => (
          <li key={i}>
            <button type="button" className="problem problem--error" onClick={() => d.line > 0 && onJumpToLine(d.line)}>
              <span className="problem__loc num">line {d.line}</span>
              <span className="problem__text">{d.text}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function NoMethods() {
  return (
    <div className="state state--center">
      <p className="state__title">No methods with code</p>
      <p className="small muted">The source compiled, but no class has a method body for jopt to optimize.</p>
    </div>
  );
}

export function LoadFailed({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  return (
    <div className="boot">
      <div className="state state--center" role="alert">
        <AlertTriangle size={22} className="tone-bad" aria-hidden="true" />
        <p className="state__title">Cannot reach the jopt backend</p>
        <p className="small muted state__msg">{error.message}</p>
        <p className="small">
          Start it with <code>./gradlew playground</code> (port 7070), then retry.
        </p>
        <button type="button" className="btn btn--primary" onClick={onRetry}>
          <RefreshCw size={14} aria-hidden="true" /> Retry
        </button>
      </div>
    </div>
  );
}
