import type { Example, Meta, Session } from './types';
import { parseExamples, parseMeta, parseSession, ResponseShapeError } from './validate';

/** Why a request failed, normalized so the UI can show one kind of error message. */
export interface ApiError {
  kind: 'network' | 'http' | 'shape' | 'timeout' | 'aborted';
  code: string;
  message: string;
  status?: number;
}

export class ApiFailure extends Error {
  readonly error: ApiError;

  constructor(error: ApiError) {
    super(error.message);
    this.name = 'ApiFailure';
    this.error = error;
  }
}

/** The server gives up after 20 s; the client allows a little more for transfer. */
export const CLIENT_TIMEOUT_MS = 30_000;

type Fetch = typeof fetch;

/** Combines a caller's abort signal with a timeout; reports which of the two fired. */
function withTimeout(signal: AbortSignal | undefined, ms: number) {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, ms);
  const onAbort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  else signal?.addEventListener('abort', onAbort, { once: true });
  return {
    signal: controller.signal,
    timedOut: () => timedOut,
    dispose: () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    },
  };
}

async function request<T>(
  path: string,
  init: RequestInit,
  parse: (data: unknown) => T,
  options: { signal?: AbortSignal; timeoutMs?: number; fetchImpl?: Fetch } = {},
): Promise<T> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const t = withTimeout(options.signal, options.timeoutMs ?? CLIENT_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetchImpl(path, { ...init, signal: t.signal });
  } catch (e: unknown) {
    t.dispose();
    if (t.timedOut())
      throw new ApiFailure({ kind: 'timeout', code: 'timeout', message: 'The server did not answer in time.' });
    if (options.signal?.aborted)
      throw new ApiFailure({ kind: 'aborted', code: 'aborted', message: 'The request was cancelled.' });
    throw new ApiFailure({
      kind: 'network',
      code: 'network',
      message: `Cannot reach the jopt backend (${e instanceof Error ? e.message : 'network error'}). Is \`gradle playground\` running?`,
    });
  }
  try {
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      if (t.timedOut()) throw new ApiFailure({ kind: 'timeout', code: 'timeout', message: 'The server did not answer in time.' });
      if (options.signal?.aborted) throw new ApiFailure({ kind: 'aborted', code: 'aborted', message: 'The request was cancelled.' });
      throw new ApiFailure({
        kind: 'shape',
        code: 'not_json',
        status: response.status,
        message: `The server answered ${response.status} with a body that is not JSON.`,
      });
    }
    const envelope = body as { ok?: unknown; data?: unknown; error?: { code?: unknown; message?: unknown } };
    if (envelope?.ok === true && response.ok) return parse(envelope.data);
    const code = typeof envelope?.error?.code === 'string' ? envelope.error.code : 'http_error';
    const message =
      typeof envelope?.error?.message === 'string' ? envelope.error.message : `The server answered ${response.status}.`;
    throw new ApiFailure({ kind: 'http', code, message, status: response.status });
  } catch (e: unknown) {
    if (e instanceof ApiFailure) throw e;
    if (e instanceof ResponseShapeError) throw new ApiFailure({ kind: 'shape', code: 'bad_shape', message: e.message });
    if (t.timedOut())
      throw new ApiFailure({ kind: 'timeout', code: 'timeout', message: 'The server did not answer in time.' });
    if (options.signal?.aborted)
      throw new ApiFailure({ kind: 'aborted', code: 'aborted', message: 'The request was cancelled.' });
    throw new ApiFailure({ kind: 'network', code: 'network', message: 'The response could not be read.' });
  } finally {
    t.dispose();
  }
}

export function fetchMeta(signal?: AbortSignal, fetchImpl?: Fetch): Promise<Meta> {
  return request('/api/meta', { method: 'GET' }, parseMeta, { signal, fetchImpl });
}

export function fetchExamples(signal?: AbortSignal, fetchImpl?: Fetch): Promise<Example[]> {
  return request('/api/examples', { method: 'GET' }, parseExamples, { signal, fetchImpl });
}

export function optimize(
  source: string,
  disabled: readonly string[],
  signal?: AbortSignal,
  fetchImpl?: Fetch,
): Promise<Session> {
  const query = disabled.length > 0 ? `?disable=${encodeURIComponent(disabled.join(','))}` : '';
  return request(
    `/api/optimize${query}`,
    { method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8', 'X-Jopt-Client': 'web' }, body: source },
    parseSession,
    { signal, fetchImpl },
  );
}
