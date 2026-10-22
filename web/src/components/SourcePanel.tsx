import { RotateCcw, Zap } from 'lucide-react';
import type { Diagnostic, Example, PassInfo } from '../api/types';
import { Kbd, MOD_KEY } from '../ui/controls';
import { passColor } from '../ui/vocabulary';
import { SourceEditor } from './SourceEditor';

export type InputProblem = { code: 'empty' | 'too_long'; message: string } | null;

export function validateSource(source: string, maxChars: number): InputProblem {
  if (source.trim() === '') return { code: 'empty', message: 'Enter some Java source to optimize.' };
  if (source.length > maxChars)
    return { code: 'too_long', message: `Source is ${source.length.toLocaleString()} characters; the limit is ${maxChars.toLocaleString()}.` };
  return null;
}

type Props = {
  examples: readonly Example[];
  exampleId: string | null;
  onSelectExample: (id: string) => void;
  source: string;
  onSourceChange: (s: string) => void;
  onResetInput: () => void;
  passes: readonly PassInfo[];
  disabled: readonly string[];
  onTogglePass: (id: string) => void;
  onRun: () => void;
  running: boolean;
  maxChars: number;
  theme: 'dark' | 'light';
  diagnostics: readonly Diagnostic[];
  highlightLine: number | null;
  onCursorLine: (line: number) => void;
};

export function SourcePanel(props: Props) {
  const { examples, exampleId, source, passes, disabled, running, maxChars } = props;
  const example = examples.find((e) => e.id === exampleId) ?? null;
  const problem = validateSource(source, maxChars);
  const edited = example !== null && example.source !== source;
  const allOff = passes.length > 0 && passes.every((p) => disabled.includes(p.id));

  return (
    <section className="source" aria-labelledby="source-title">
      <div className="panel-head">
        <h2 id="source-title" className="panel-head__title">Source</h2>
        <span className="panel-head__meta mono">Java · javac --release 21</span>
        <span className="panel-head__spacer" />
        <label className="field field--inline">
          <span className="visually-hidden">Example</span>
          <select
            className="field__select"
            value={exampleId ?? ''}
            onChange={(e) => props.onSelectExample(e.target.value)}
            aria-label="Load an example"
          >
            {exampleId === null && <option value="">Your code</option>}
            {examples.map((e, i) => (
              <option key={e.id} value={e.id}>
                {i + 1}. {e.title}
              </option>
            ))}
          </select>
        </label>
      </div>

      {example && (
        <div className="example">
          <div className="example__head">
            <span className="example__concept mono">{example.concept}</span>
            {edited && <span className="example__edited">edited</span>}
            {edited && (
              <button type="button" className="link-btn" onClick={props.onResetInput}>
                <RotateCcw size={12} aria-hidden="true" /> Restore example
              </button>
            )}
          </div>
          <p className="example__desc">{example.description}</p>
          {example.expected.length > 0 && (
            <p className="example__expect small">
              <span className="muted">Expect:</span>{' '}
              {example.expected.map((x) => (
                <span key={x} className="tag">{x}</span>
              ))}
            </p>
          )}
        </div>
      )}

      <div className="source__editor">
        <SourceEditor
          value={source}
          onChange={props.onSourceChange}
          onRun={props.onRun}
          theme={props.theme}
          diagnostics={props.diagnostics}
          highlightLine={props.highlightLine}
          onCursorLine={props.onCursorLine}
        />
      </div>

      <div className="source__foot">
        <fieldset className="passes">
          <legend className="section-label">Passes</legend>
          {passes.map((p) => {
            const on = !disabled.includes(p.id);
            return (
              <label key={p.id} className={`pass-toggle${on ? ' is-on' : ''}`} title={p.summary} style={{ ['--pass-color' as string]: passColor(p.id) }}>
                <input type="checkbox" checked={on} onChange={() => props.onTogglePass(p.id)} />
                <span className="pass-toggle__dot" aria-hidden="true" />
                <span className="mono">{p.id}</span>
              </label>
            );
          })}
        </fieldset>
        <div className="run-row">
          <span className={`charcount num small${problem?.code === 'too_long' ? ' is-bad' : ''}`}>
            {source.length.toLocaleString()} / {maxChars.toLocaleString()}
          </span>
          {problem && <span className="run-row__problem small" role="status">{problem.message}</span>}
          {!problem && allOff && <span className="run-row__problem small">All passes are off: jopt will only build and lower the IR.</span>}
          <span className="panel-head__spacer" />
          <button type="button" className="btn btn--primary" onClick={props.onRun} disabled={running || problem !== null}>
            <Zap size={14} aria-hidden="true" />
            {running ? 'Optimizing…' : 'Optimize'}
            <Kbd>{MOD_KEY}↵</Kbd>
          </button>
        </div>
      </div>
    </section>
  );
}
