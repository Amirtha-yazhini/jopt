import { useEffect, useRef, useState } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import type { Diagnostic } from '../api/types';
import { monaco, THEMES } from '../monaco/setup';

type Props = {
  value: string;
  onChange: (value: string) => void;
  onRun: () => void;
  theme: 'dark' | 'light';
  diagnostics: readonly Diagnostic[];
  /** A source line linked from the bytecode view; highlighted and scrolled into view. */
  highlightLine: number | null;
  onCursorLine: (line: number) => void;
};

const SEVERITY = {
  error: monaco.MarkerSeverity.Error,
  warning: monaco.MarkerSeverity.Warning,
  note: monaco.MarkerSeverity.Info,
} as const;

export function SourceEditor({ value, onChange, onRun, theme, diagnostics, highlightLine, onCursorLine }: Props) {
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const decorations = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
  // Effects below re-run once the editor exists, so a freshly mounted editor gets markers and highlights.
  const [mounted, setMounted] = useState(false);
  // Monaco keeps the first callbacks it is given; refs keep them current.
  const onRunRef = useRef(onRun);
  const onCursorRef = useRef(onCursorLine);
  onRunRef.current = onRun;
  onCursorRef.current = onCursorLine;

  const handleMount: OnMount = (editor) => {
    editorRef.current = editor;
    decorations.current = editor.createDecorationsCollection();
    setMounted(true);
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => onRunRef.current());
    editor.onDidChangeCursorPosition((e) => {
      if (e.source === 'mouse' || e.source === 'keyboard') onCursorRef.current(e.position.lineNumber);
    });
  };

  useEffect(() => {
    const model = editorRef.current?.getModel();
    if (!model) return;
    monaco.editor.setModelMarkers(
      model,
      'javac',
      diagnostics
        .filter((d) => d.line > 0)
        .map((d) => ({
          severity: SEVERITY[d.kind],
          message: d.text,
          startLineNumber: d.line,
          startColumn: Math.max(1, d.column),
          endLineNumber: d.line,
          endColumn: model.getLineMaxColumn(Math.min(d.line, model.getLineCount())),
        })),
    );
  }, [diagnostics, value, mounted]);

  useEffect(() => {
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (!editor || !model || !decorations.current) return;
    if (highlightLine === null || highlightLine < 1 || highlightLine > model.getLineCount()) {
      decorations.current.clear();
      return;
    }
    decorations.current.set([
      {
        range: new monaco.Range(highlightLine, 1, highlightLine, 1),
        options: { isWholeLine: true, className: 'linked-line', linesDecorationsClassName: 'linked-line-gutter' },
      },
    ]);
    editor.revealLineInCenterIfOutsideViewport(highlightLine);
  }, [highlightLine, mounted]);

  return (
    <Editor
      language="java"
      theme={THEMES[theme]}
      value={value}
      onChange={(v) => onChange(v ?? '')}
      onMount={handleMount}
      loading={<div className="editor-loading">Loading editor…</div>}
      options={{
        fontFamily: 'JetBrains Mono, ui-monospace, Menlo, monospace',
        fontSize: 13,
        lineHeight: 20,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        tabSize: 4,
        renderLineHighlight: 'line',
        padding: { top: 10, bottom: 10 },
        automaticLayout: true,
        fixedOverflowWidgets: true,
        ariaLabel: 'Java source editor',
      }}
    />
  );
}
