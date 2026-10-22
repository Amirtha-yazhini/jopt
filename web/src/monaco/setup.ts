import * as monaco from 'monaco-editor/esm/vs/editor/edcore.main';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { loader } from '@monaco-editor/react';

// Use the bundled Monaco instead of @monaco-editor/react's default CDN download.
self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

export const JVM_LANGUAGE = 'jvm';
export const THEMES = { dark: 'jopt-dark', light: 'jopt-light' } as const;

/** Highlighting for jopt's bytecode listings (ASM Textifier style) and IR text. */
monaco.languages.register({ id: JVM_LANGUAGE });
monaco.languages.setMonarchTokensProvider(JVM_LANGUAGE, {
  tokenizer: {
    root: [
      [/^\s*(?:L|B)\d+:/, 'label'],
      [/\(entry\)/, 'comment'],
      [/try \{ catch [^}]*\}/, 'comment'],
      [/"(?:[^"\\]|\\.)*"/, 'string'],
      [/\b(?:GOTO|IF[A-Z_]*|SWITCH|TABLESWITCH|LOOKUPSWITCH|[ILFDA]?RETURN|ATHROW|TRYCATCHBLOCK|else|default)\b/, 'keyword.control'],
      [/\b(?:INVOKE[A-Z]+|GETFIELD|PUTFIELD|GETSTATIC|PUTSTATIC)\b/, 'keyword.call'],
      [/\b(?:CONST|[ILFDA]CONST_\w+|BIPUSH|SIPUSH|LDC)\b/, 'keyword.const'],
      [/\b[A-Z][A-Z0-9_]{1,}\b/, 'keyword'],
      [/\b(?:L|B)\d+\b/, 'label'],
      [/-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?[LfdF]?\b/, 'number'],
      [/[A-Za-z_$][\w$/]*(?:\.[\w$<>]+)?/, 'identifier'],
    ],
  },
});

const shared: monaco.editor.ITokenThemeRule[] = [{ token: 'keyword', fontStyle: '' }];

monaco.editor.defineTheme(THEMES.dark, {
  base: 'vs-dark',
  inherit: true,
  rules: [
    ...shared,
    { token: 'label', foreground: 'e5c07b' },
    { token: 'keyword', foreground: '8fb4ff' },
    { token: 'keyword.control', foreground: 'c79bf2' },
    { token: 'keyword.call', foreground: '6ccfe0' },
    { token: 'keyword.const', foreground: 'f0a875' },
    { token: 'number', foreground: 'f0a875' },
    { token: 'string', foreground: '9fd28a' },
    { token: 'comment', foreground: '6b7381', fontStyle: 'italic' },
    { token: 'identifier', foreground: 'c5cad3' },
  ],
  colors: {
    'editor.background': '#0f1216',
    'editor.foreground': '#d7dbe2',
    'editorLineNumber.foreground': '#4a525e',
    'editorLineNumber.activeForeground': '#9aa3b0',
    'editor.lineHighlightBackground': '#161b22',
    'editor.selectionBackground': '#2b3a55',
    'editorGutter.background': '#0f1216',
    'diffEditor.insertedLineBackground': '#1f3a2a66',
    'diffEditor.removedLineBackground': '#4a1f2466',
    'diffEditor.insertedTextBackground': '#2f6b4855',
    'diffEditor.removedTextBackground': '#8a2e3755',
    'scrollbarSlider.background': '#2a313b88',
    'editorWidget.background': '#151a20',
  },
});

monaco.editor.defineTheme(THEMES.light, {
  base: 'vs',
  inherit: true,
  rules: [
    ...shared,
    { token: 'label', foreground: '9a6700' },
    { token: 'keyword', foreground: '1f5fd1' },
    { token: 'keyword.control', foreground: '7a3fc0' },
    { token: 'keyword.call', foreground: '0b7a8a' },
    { token: 'keyword.const', foreground: 'b4540d' },
    { token: 'number', foreground: 'b4540d' },
    { token: 'string', foreground: '2f7a1f' },
    { token: 'comment', foreground: '7a818c', fontStyle: 'italic' },
    { token: 'identifier', foreground: '2b3038' },
  ],
  colors: {
    'editor.background': '#fbfbfa',
    'editorGutter.background': '#fbfbfa',
    'editor.lineHighlightBackground': '#f1f2f4',
    'diffEditor.insertedLineBackground': '#d9f2e399',
    'diffEditor.removedLineBackground': '#fbe0e299',
  },
});

export { monaco };
