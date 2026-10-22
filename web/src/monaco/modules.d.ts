// Monaco's "editor core + all contributions, no languages" entry ships without typings of its own;
// its API is the same as the main package's.
declare module 'monaco-editor/esm/vs/editor/edcore.main' {
  export * from 'monaco-editor';
}
