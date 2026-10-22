import { useEffect, useRef, useState } from 'react';
import Editor, { DiffEditor, type DiffOnMount, type OnMount } from '@monaco-editor/react';
import type { Row } from '../api/types';
import { rowsText } from '../state/derived';
import { JVM_LANGUAGE, monaco, THEMES } from '../monaco/setup';

type Props = {
  /** Null shows {@code modified} alone (nothing to compare against). */
  original: readonly Row[] | null;
  modified: readonly Row[];
  theme: 'dark' | 'light';
  sideBySide: boolean;
  /** Rows that came from this source line are marked. */
  linkedSourceLine: number | null;
  /** Called with the source line of the row the cursor moved to (null when the row has none). */
  onSourceLine: (line: number | null) => void;
  ariaLabel: string;
};

const BASE_OPTIONS: monaco.editor.IEditorOptions = {
  readOnly: true,
  domReadOnly: true,
  fontFamily: 'JetBrains Mono, ui-monospace, Menlo, monospace',
  fontSize: 12.5,
  lineHeight: 19,
  minimap: { enabled: false },
  scrollBeyondLastLine: false,
  renderLineHighlight: 'line',
  padding: { top: 8, bottom: 8 },
  automaticLayout: true,
  folding: false,
  lineNumbersMinChars: 3,
  contextmenu: false,
};

type Code = monaco.editor.ICodeEditor;

/** Keeps one editor's "linked row" decorations in sync with the selected source line. */
function linkRows(editor: Code, rows: readonly Row[], line: number | null, col: monaco.editor.IEditorDecorationsCollection) {
  if (line === null) {
    col.clear();
    return;
  }
  const ranges = rows
    .map((r, i) => (r.line === line ? i + 1 : 0))
    .filter((n) => n > 0)
    .map((n) => ({
      range: new monaco.Range(n, 1, n, 1),
      options: { isWholeLine: true, className: 'linked-line', linesDecorationsClassName: 'linked-line-gutter' },
    }));
  col.set(ranges);
  if (ranges[0]) editor.revealLineInCenterIfOutsideViewport(ranges[0].range.startLineNumber);
}

export function CodeView({ original, modified, theme, sideBySide, linkedSourceLine, onSourceLine, ariaLabel }: Props) {
  const editors = useRef<{ modified: Code; original: Code | null } | null>(null);
  const decorations = useRef<{ modified: monaco.editor.IEditorDecorationsCollection; original: monaco.editor.IEditorDecorationsCollection | null } | null>(null);
  // Bumped on every mount (single and diff views swap editors), so the new editor gets its decorations.
  const [mounts, setMounts] = useState(0);
  const rowsRef = useRef({ original, modified });
  rowsRef.current = { original, modified };
  const onSourceRef = useRef(onSourceLine);
  onSourceRef.current = onSourceLine;

  const follow = (editor: Code, side: 'original' | 'modified') => {
    editor.onDidChangeCursorPosition((e) => {
      // Only user moves: Monaco also moves the cursor when it swaps models between steps.
      if (e.source !== 'mouse' && e.source !== 'keyboard') return;
      const rows = rowsRef.current[side];
      const line = rows?.[e.position.lineNumber - 1]?.line ?? -1;
      onSourceRef.current(line > 0 ? line : null);
    });
  };

  const mountSingle: OnMount = (editor) => {
    editors.current = { modified: editor, original: null };
    decorations.current = { modified: editor.createDecorationsCollection(), original: null };
    setMounts((n) => n + 1);
    follow(editor, 'modified');
  };

  const mountDiff: DiffOnMount = (diff) => {
    const mod = diff.getModifiedEditor();
    const orig = diff.getOriginalEditor();
    editors.current = { modified: mod, original: orig };
    decorations.current = { modified: mod.createDecorationsCollection(), original: orig.createDecorationsCollection() };
    setMounts((n) => n + 1);
    follow(mod, 'modified');
    follow(orig, 'original');
  };

  useEffect(() => {
    const e = editors.current;
    const d = decorations.current;
    if (!e || !d) return;
    linkRows(e.modified, modified, linkedSourceLine, d.modified);
    if (e.original && d.original && original) linkRows(e.original, original, linkedSourceLine, d.original);
  }, [linkedSourceLine, original, modified, mounts]);

  if (original === null) {
    return (
      <Editor
        key="single"
        language={JVM_LANGUAGE}
        theme={THEMES[theme]}
        value={rowsText(modified)}
        onMount={mountSingle}
        loading={<div className="editor-loading">Loading viewer…</div>}
        options={{ ...BASE_OPTIONS, ariaLabel }}
      />
    );
  }
  return (
    <DiffEditor
      key="diff"
      language={JVM_LANGUAGE}
      theme={THEMES[theme]}
      original={rowsText(original)}
      modified={rowsText(modified)}
      onMount={mountDiff}
      loading={<div className="editor-loading">Loading diff…</div>}
      options={{
        ...BASE_OPTIONS,
        renderSideBySide: sideBySide,
        useInlineViewWhenSpaceIsLimited: true,
        renderOverviewRuler: false,
        ignoreTrimWhitespace: false,
        originalEditable: false,
        enableSplitViewResizing: true,
        renderIndicators: true,
        diffAlgorithm: 'advanced',
        hideUnchangedRegions: { enabled: false },
        ariaLabel,
      }}
    />
  );
}
