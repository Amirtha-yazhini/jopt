import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import { Panel, PanelGroup, PanelResizeHandle, type ImperativePanelHandle } from 'react-resizable-panels';
import { AlertTriangle } from 'lucide-react';
import { ApiFailure, fetchExamples, fetchMeta, optimize, type ApiError } from './api/client';
import type { Example, Meta } from './api/types';
import { visibleSteps } from './state/derived';
import { initialState, isStale, reducer, selectedMethod, visiblePosition, type PlaygroundState } from './state/session';
import { useGlobalShortcuts, useMediaQuery, usePlayback } from './hooks';
import { Header, type Status } from './components/Header';
import { SourcePanel, validateSource } from './components/SourcePanel';
import { PipelinePanel } from './components/PipelinePanel';
import { Inspector } from './components/Inspector';
import { BottomPanel } from './components/BottomPanel';
import { CompileFailed, LoadFailed, NoMethods, PipelineIntro, Running, RunError } from './components/EmptyStates';
import { Kbd, MOD_KEY } from './ui/controls';
import { stepTitle } from './ui/vocabulary';

type Theme = 'dark' | 'light';
type Boot = { status: 'loading' } | { status: 'ready'; meta: Meta; examples: Example[] } | { status: 'error'; error: ApiError };

function readTheme(): Theme {
  try {
    return localStorage.getItem('jopt-theme') === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

function toApiError(e: unknown): ApiError {
  return e instanceof ApiFailure ? e.error : { kind: 'network', code: 'unknown', message: String(e) };
}

function statusOf(state: PlaygroundState, stale: boolean): Status {
  switch (state.phase) {
    case 'idle':
      return { tone: 'neutral', label: 'Ready to optimize' };
    case 'running':
      return { tone: 'busy', label: 'Compiling and optimizing…' };
    case 'error':
      return { tone: 'bad', label: 'Run failed' };
    case 'ready':
      if (state.session?.status === 'compile-error') return { tone: 'bad', label: 'Compile error' };
      if (stale) return { tone: 'warn', label: 'Results are outdated' };
      return { tone: 'good', label: state.playing ? 'Replaying…' : 'Replaying recorded run' };
  }
}

export default function App() {
  const [boot, setBoot] = useState<Boot>({ status: 'loading' });
  const [state, dispatch] = useReducer(reducer, initialState);
  const [source, setSource] = useState('');
  const [exampleId, setExampleId] = useState<string | null>(null);
  const [disabled, setDisabled] = useState<string[]>([]);
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [linkedLine, setLinkedLine] = useState<number | null>(null);
  const [bottomCollapsed, setBottomCollapsed] = useState(false);
  const [narrowTab, setNarrowTab] = useState<'source' | 'pipeline' | 'inspector'>('source');
  const narrow = useMediaQuery('(max-width: 1080px)');
  const narrowTabsRef = useRef<HTMLDivElement>(null);
  const inFlight = useRef<AbortController | null>(null);
  const requestId = useRef(0);
  const bottomRef = useRef<ImperativePanelHandle>(null);

  // ---- boot: pass catalog and examples -------------------------------------------------------
  const load = useCallback(() => {
    const controller = new AbortController();
    setBoot({ status: 'loading' });
    Promise.all([fetchMeta(controller.signal), fetchExamples(controller.signal)])
      .then(([meta, examples]) => {
        setBoot({ status: 'ready', meta, examples });
        const first = examples[0];
        if (first) {
          setExampleId(first.id);
          setSource(first.source);
        }
      })
      .catch((e: unknown) => {
        const error = toApiError(e);
        if (error.kind !== 'aborted') setBoot({ status: 'error', error });
      });
    return () => controller.abort();
  }, []);
  useEffect(() => load(), [load]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem('jopt-theme', theme);
    } catch {
      /* storage unavailable: the theme just is not remembered */
    }
  }, [theme]);

  useEffect(() => () => inFlight.current?.abort(), []);

  const meta = boot.status === 'ready' ? boot.meta : null;
  const examples = useMemo(() => (boot.status === 'ready' ? boot.examples : []), [boot]);
  const currentInput = useMemo(() => ({ source, disabled }), [source, disabled]);
  const stale = isStale(state, currentInput);
  const method = selectedMethod(state);
  const indices = useMemo(() => (method ? visibleSteps(method.steps, state.hideNoOps) : []), [method, state.hideNoOps]);
  const session = state.session;
  const optimized = session?.status === 'optimized' ? session : null;

  // ---- running the optimizer ---------------------------------------------------------------
  const run = useCallback(() => {
    if (!meta || validateSource(source, meta.maxSourceChars)) return;
    inFlight.current?.abort(); // the newest run wins; an older response would be ignored anyway
    const controller = new AbortController();
    inFlight.current = controller;
    const id = ++requestId.current;
    const input = { source, disabled: [...disabled] };
    dispatch({ type: 'run/start', input });
    setLinkedLine(null);
    optimize(input.source, input.disabled, controller.signal)
      .then((s) => dispatch({ type: 'run/succeeded', requestId: id, session: s }))
      .catch((e: unknown) => dispatch({ type: 'run/failed', requestId: id, error: toApiError(e) }))
      .finally(() => {
        if (inFlight.current === controller) inFlight.current = null;
      });
  }, [meta, source, disabled]);

  const reset = useCallback(() => {
    inFlight.current?.abort();
    requestId.current++;
    dispatch({ type: 'reset' });
    setLinkedLine(null);
  }, []);

  const selectExample = useCallback(
    (id: string) => {
      const ex = examples.find((e) => e.id === id);
      if (!ex) return;
      setExampleId(id);
      setSource(ex.source);
      reset(); // a new example starts a clean session
    },
    [examples, reset],
  );

  const restoreExample = () => {
    const ex = examples.find((e) => e.id === exampleId);
    if (ex) setSource(ex.source);
  };

  const togglePass = (id: string) =>
    setDisabled((d) => (d.includes(id) ? d.filter((x) => x !== id) : [...d, id]));

  const toggleBottom = () => {
    const panel = bottomRef.current;
    if (panel) {
      if (panel.isCollapsed()) panel.expand();
      else panel.collapse();
    } else {
      setBottomCollapsed((c) => !c);
    }
  };

  const jumpToLine = (line: number) => {
    setLinkedLine(line);
    if (narrow) setNarrowTab('source');
  };

  usePlayback(state.playing, state.speed, dispatch);
  useGlobalShortcuts({
    onRun: run,
    onHelp: () => (document.getElementById('shortcuts-dialog') as HTMLDialogElement | null)?.showModal(),
    dispatch,
    enabled: method !== null && state.phase !== 'error',
  });

  // Source/bytecode links only make sense while the results match the visible source.
  const shownLink = stale ? null : linkedLine;
  const announce = method && meta
    ? `Step ${visiblePosition(indices, state.stepIndex) + 1} of ${indices.length}: ${stepTitle(method.steps[state.stepIndex]!, meta.passes)}`
    : '';

  if (boot.status === 'loading') {
    return (
      <div className="boot" aria-busy="true">
        <div className="boot__mark" aria-hidden="true" />
        <p className="muted">Connecting to the jopt backend…</p>
      </div>
    );
  }
  if (boot.status === 'error') return <LoadFailed error={boot.error} onRetry={load} />;
  const readyMeta = boot.meta;

  const experiment = exampleId ? examples.find((e) => e.id === exampleId)?.title ?? 'Example' : 'Your code';
  const diagnostics = session && !stale ? session.compilation.diagnostics : [];

  const sourcePanel = (
    <SourcePanel
      examples={examples}
      exampleId={exampleId}
      onSelectExample={selectExample}
      source={source}
      onSourceChange={setSource}
      onResetInput={restoreExample}
      passes={readyMeta.passes}
      disabled={disabled}
      onTogglePass={togglePass}
      onRun={run}
      running={state.phase === 'running'}
      maxChars={readyMeta.maxSourceChars}
      theme={theme}
      diagnostics={diagnostics}
      highlightLine={shownLink}
      onCursorLine={(line) => !stale && optimized && setLinkedLine(line)}
    />
  );

  const staleBanner = stale && state.phase !== 'running' && (
    <div className="banner banner--warn" role="status">
      <AlertTriangle size={14} aria-hidden="true" />
      <span>The source or pass selection changed since this run. These results are for the previous input.</span>
      <button type="button" className="link-btn" onClick={run}>
        Re-run <Kbd>{MOD_KEY}↵</Kbd>
      </button>
    </div>
  );

  let pipeline: React.ReactNode;
  let inspector: React.ReactNode = null;
  if (state.phase === 'running' && !session) pipeline = <Running />;
  else if (state.phase === 'error' && state.error) pipeline = <RunError error={state.error} onRetry={run} />;
  else if (!session) pipeline = <PipelineIntro meta={readyMeta} />;
  else if (session.status === 'compile-error') pipeline = <CompileFailed session={session} onJumpToLine={jumpToLine} />;
  else if (!method) pipeline = <NoMethods />;
  else {
    pipeline = (
      <PipelinePanel
        session={session}
        method={method}
        meta={readyMeta}
        indices={indices}
        stepIndex={state.stepIndex}
        playing={state.playing}
        speed={state.speed}
        hideNoOps={state.hideNoOps}
        dispatch={dispatch}
      />
    );
    inspector = (
      <Inspector
        method={method}
        stepIndex={state.stepIndex}
        meta={readyMeta}
        theme={theme}
        linkedSourceLine={shownLink}
        onSourceLine={(line) => !stale && setLinkedLine(line)}
      />
    );
  }
  const busyOverlay = state.phase === 'running' && session && (
    <div className="busy-overlay" aria-hidden="true">
      <span>Running…</span>
    </div>
  );
  const pipelineArea = (
    <div className={`area${state.phase === 'running' && session ? ' is-busy' : ''}`}>
      {staleBanner}
      {pipeline}
      {busyOverlay}
    </div>
  );
  const inspectorArea = (
    <div className="area">
      {inspector ?? (
        <div className="state state--center muted">
          <p>{session ? 'Nothing to inspect for this run.' : 'Select a stage to inspect it here.'}</p>
        </div>
      )}
    </div>
  );
  const bottom = (
    <BottomPanel
      session={session}
      collapsed={bottomCollapsed}
      onToggle={toggleBottom}
      onJumpToLine={jumpToLine}
    />
  );

  return (
    <div className="app">
      <Header
        status={statusOf(state, stale)}
        experiment={experiment}
        theme={theme}
        onToggleTheme={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
        onReset={reset}
        canReset={state.phase !== 'idle'}
      />
      <div className="visually-hidden" aria-live="polite">{announce}</div>
      {narrow ? (
        <main className="narrow">
          <div
            ref={narrowTabsRef}
            className="narrow__tabs"
            role="tablist"
            aria-label="Views"
            onKeyDown={(e) => {
              const tabList: ('source' | 'pipeline' | 'inspector')[] = ['source', 'pipeline', 'inspector'];
              const cur = tabList.indexOf(narrowTab);
              let next = -1;
              if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = cur === tabList.length - 1 ? 0 : cur + 1;
              else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = cur <= 0 ? tabList.length - 1 : cur - 1;
              else if (e.key === 'Home') next = 0;
              else if (e.key === 'End') next = tabList.length - 1;
              if (next >= 0) {
                e.preventDefault();
                const target = tabList[next]!;
                setNarrowTab(target);
                narrowTabsRef.current?.querySelectorAll<HTMLButtonElement>('button[role="tab"]')?.[next]?.focus();
              }
            }}
          >
            {(['source', 'pipeline', 'inspector'] as const).map((t) => {
              const isSelected = narrowTab === t;
              return (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  id={`narrow-tab-${t}`}
                  aria-selected={isSelected}
                  tabIndex={isSelected ? 0 : -1}
                  className="bottom__tab"
                  onClick={() => setNarrowTab(t)}
                >
                  {t === 'source' ? 'Source' : t === 'pipeline' ? 'Pipeline' : 'Inspector'}
                </button>
              );
            })}
          </div>
          <div className="narrow__view panel">
            {narrowTab === 'source' ? sourcePanel : narrowTab === 'pipeline' ? pipelineArea : inspectorArea}
          </div>
          <div className="panel narrow__bottom">{bottom}</div>
        </main>
      ) : (
        <main className="workspace">
          <PanelGroup direction="vertical" autoSaveId="jopt-v">
            <Panel minSize={40} defaultSize={78}>
              <PanelGroup direction="horizontal" autoSaveId="jopt-h">
                <Panel minSize={20} defaultSize={31} className="panel">
                  {sourcePanel}
                </Panel>
                <PanelResizeHandle className="resize resize--v" aria-label="Resize source panel" />
                <Panel minSize={18} defaultSize={23} className="panel">
                  {pipelineArea}
                </Panel>
                <PanelResizeHandle className="resize resize--v" aria-label="Resize pipeline panel" />
                <Panel minSize={28} defaultSize={46} className="panel">
                  {inspectorArea}
                </Panel>
              </PanelGroup>
            </Panel>
            <PanelResizeHandle className="resize resize--h" aria-label="Resize details panel" />
            <Panel
              ref={bottomRef}
              minSize={12}
              defaultSize={22}
              collapsible
              collapsedSize={5}
              onCollapse={() => setBottomCollapsed(true)}
              onExpand={() => setBottomCollapsed(false)}
              className="panel"
            >
              {bottom}
            </Panel>
          </PanelGroup>
        </main>
      )}
    </div>
  );
}
