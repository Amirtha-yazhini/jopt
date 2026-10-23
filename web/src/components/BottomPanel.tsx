import { useRef, useState } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import type { Session } from '../api/types';
import { formatChange, methodLabel, simpleClassName } from '../state/derived';
import { IconButton, ToneBadge } from '../ui/controls';
import { OUTCOMES } from '../ui/vocabulary';
import { METRIC_DEFINITIONS } from './StepDetails';

type Tab = 'problems' | 'summary' | 'classes' | 'log';

type Props = {
  session: Session | null;
  collapsed: boolean;
  onToggle: () => void;
  onJumpToLine: (line: number) => void;
};

function Problems({ session, onJumpToLine }: { session: Session; onJumpToLine: (line: number) => void }) {
  const diags = session.compilation.diagnostics;
  if (diags.length === 0) return <p className="note">javac reported no errors or warnings for {session.compilation.fileName}.</p>;
  return (
    <ul className="problems">
      {diags.map((d, i) => (
        <li key={i}>
          <button type="button" className={`problem problem--${d.kind}`} onClick={() => d.line > 0 && onJumpToLine(d.line)}>
            <span className="problem__kind">{d.kind}</span>
            <span className="problem__loc num">{d.line > 0 ? `${session.compilation.fileName}:${d.line}:${d.column}` : session.compilation.fileName}</span>
            <span className="problem__text">{d.text}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function SummaryTab({ session }: { session: Session }) {
  const s = session.summary;
  if (!s) return <p className="note">No summary: the source did not compile.</p>;
  const rows: [string, number, number, string][] = [
    ['Bytecode instructions (all methods)', s.instructionsBefore, s.instructionsAfter, METRIC_DEFINITIONS.instructions],
    ['Code bytes (all methods)', s.codeBytesBefore, s.codeBytesAfter, METRIC_DEFINITIONS.codeBytes],
    ['Class file bytes', s.fileBytesBefore, s.fileBytesAfter,
      'Total size of the .class files. Rewritten classes also get a freshly compacted constant pool and lose local-variable debug tables, so this changes for reasons besides the optimizations.'],
  ];
  return (
    <div className="summary">
      <table className="metrics">
        <thead>
          <tr>
            <th scope="col">Measure</th>
            <th scope="col" className="metrics__num">javac</th>
            <th scope="col" className="metrics__num">jopt</th>
            <th scope="col" className="metrics__num">Change</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, before, after, def]) => {
            const c = formatChange(before, after);
            return (
              <tr key={label}>
                <th scope="row"><abbr title={def}>{label}</abbr></th>
                <td className="metrics__num num">{before.toLocaleString()}</td>
                <td className="metrics__num num">{after.toLocaleString()}</td>
                <td className={`metrics__num num delta delta--${c.direction}`}>{c.text}</td>
              </tr>
            );
          })}
          <tr>
            <th scope="row">Methods replaced</th>
            <td className="metrics__num num" colSpan={2}>{s.methodsReplaced} of {s.methods}</td>
            <td />
          </tr>
        </tbody>
      </table>
      <div className="summary__side small">
        <p>
          Server time: javac <span className="num">{session.timings.compileMs.toFixed(0)} ms</span>, jopt{' '}
          <span className="num">{session.timings.optimizeMs.toFixed(0)} ms</span> (including snapshot recording),
          HotSpot verification <span className="num">{session.timings.verifyMs.toFixed(0)} ms</span>.
        </p>
        <p className="muted">
          These are code sizes, not speed. Fewer instructions do not by themselves make code faster: HotSpot's JIT
          performs similar optimizations at run time. jopt never runs your code, so there are no runtime measurements.
        </p>
        {Object.keys(s.counters).length > 0 && (
          <p>
            <span className="muted">Rewrites in replaced methods:</span>{' '}
            {Object.entries(s.counters).map(([k, n]) => (
              <span key={k} className="tag">{n}× {k}</span>
            ))}
          </p>
        )}
      </div>
    </div>
  );
}

function Classes({ session }: { session: Session }) {
  if (session.classes.length === 0) return <p className="note">No classes: the source did not compile.</p>;
  return (
    <div>
      <p className="small muted">
        Each output class is loaded and linked in a fresh class loader, which runs HotSpot's bytecode verifier.
        Static initializers are not run.
      </p>
      <table className="metrics">
        <thead>
          <tr>
            <th scope="col">Class</th>
            <th scope="col" className="metrics__num">Bytes before</th>
            <th scope="col" className="metrics__num">Bytes after</th>
            <th scope="col">Written</th>
            <th scope="col">HotSpot verification</th>
          </tr>
        </thead>
        <tbody>
          {session.classes.map((c) => (
            <tr key={c.name}>
              <th scope="row" className="mono">{c.name}</th>
              <td className="metrics__num num">{c.fileBytesBefore}</td>
              <td className="metrics__num num">{c.fileBytesAfter}</td>
              <td>{c.rewritten ? 'rewritten' : c.unchangedReason ? `kept: ${c.unchangedReason}` : 'unchanged'}</td>
              <td>
                <ToneBadge tone={c.verification === 'passed' ? 'good' : c.verification === 'failed' ? 'bad' : 'warn'}>
                  {c.verification}
                </ToneBadge>
                {c.verificationDetail && <span className="small mono"> {c.verificationDetail}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Log({ session }: { session: Session }) {
  if (session.methods.length === 0 && session.warnings.length === 0) return <p className="note">Nothing was optimized.</p>;
  return (
    <ul className="log mono small">
      {session.methods.map((m) => (
        <li key={m.id}>
          <ToneBadge tone={OUTCOMES[m.outcome].tone}>{OUTCOMES[m.outcome].label}</ToneBadge>{' '}
          {simpleClassName(m.owner)}.{methodLabel(m)}
          {m.outcomeDetail && <span className="muted"> — {m.outcomeDetail}</span>}
        </li>
      ))}
      {session.methodsNotTraced > 0 && <li className="muted">{session.methodsNotTraced} more methods were optimized without snapshots (tracing limit).</li>}
      {session.warnings.map((w, i) => (
        <li key={`w${i}`} className="log__warn">warning: {w}</li>
      ))}
    </ul>
  );
}

export function BottomPanel({ session, collapsed, onToggle, onJumpToLine }: Props) {
  const [tab, setTab] = useState<Tab>('summary');
  const tabsRef = useRef<HTMLDivElement>(null);
  const problemCount = session?.compilation.diagnostics.length ?? 0;
  const failed = session?.status === 'compile-error';
  const active: Tab = failed ? 'problems' : tab;
  const tabs: { id: Tab; label: string }[] = [
    { id: 'problems', label: `Problems${problemCount ? ` (${problemCount})` : ''}` },
    { id: 'summary', label: 'Summary' },
    { id: 'classes', label: 'Classes' },
    { id: 'log', label: 'Decisions' },
  ];

  const enabledTabs = tabs.filter((t) => !(!session || (failed && t.id !== 'problems')));

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (enabledTabs.length === 0) return;
    const currentIndex = enabledTabs.findIndex((t) => t.id === active);
    let nextIndex = -1;

    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = currentIndex < 0 || currentIndex === enabledTabs.length - 1 ? 0 : currentIndex + 1;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = currentIndex <= 0 ? enabledTabs.length - 1 : currentIndex - 1;
    } else if (e.key === 'Home') {
      nextIndex = 0;
    } else if (e.key === 'End') {
      nextIndex = enabledTabs.length - 1;
    }

    if (nextIndex >= 0) {
      e.preventDefault();
      const target = enabledTabs[nextIndex]!;
      setTab(target.id);
      if (collapsed) onToggle();
      const buttons = tabsRef.current?.querySelectorAll<HTMLButtonElement>('button[role="tab"]:not(:disabled)');
      buttons?.[nextIndex]?.focus();
    }
  };

  return (
    <section className="bottom" aria-label="Run details">
      <div ref={tabsRef} className="bottom__tabs" role="tablist" aria-label="Run details" onKeyDown={onKeyDown}>
        {tabs.map((t) => {
          const isSelected = active === t.id;
          const isDisabled = !session || (failed && t.id !== 'problems');
          return (
            <button
              key={t.id}
              type="button"
              role="tab"
              id={`tab-${t.id}`}
              aria-selected={isSelected}
              tabIndex={isSelected ? 0 : -1}
              aria-controls={collapsed ? undefined : 'bottom-panel'}
              className="bottom__tab"
              disabled={isDisabled}
              onClick={() => {
                setTab(t.id);
                if (collapsed) onToggle();
              }}
            >
              {t.label}
            </button>
          );
        })}
        <span className="panel-head__spacer" />
        <IconButton label={collapsed ? 'Expand details panel' : 'Collapse details panel'} onClick={onToggle}>
          {collapsed ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </IconButton>
      </div>
      {!collapsed && (
        <div className="bottom__body" id="bottom-panel" role="tabpanel" aria-labelledby={`tab-${active}`}>
          {!session ? (
            <p className="note">Run the optimizer to see javac diagnostics, the size summary and HotSpot verification.</p>
          ) : active === 'problems' ? (
            <Problems session={session} onJumpToLine={onJumpToLine} />
          ) : active === 'summary' ? (
            <SummaryTab session={session} />
          ) : active === 'classes' ? (
            <Classes session={session} />
          ) : (
            <Log session={session} />
          )}
        </div>
      )}
    </section>
  );
}
