import { describe, expect, it } from 'vitest';
import { ApiFailure, fetchMeta, optimize } from './client';
import { parseMeta, parseSession, ResponseShapeError } from './validate';
import constantLocalsJson from '../test/fixtures/ConstantLocals.session.json';
import brokenJson from '../test/fixtures/Broken.session.json';
import metaJson from '../test/fixtures/meta.json';

// These tests isolate response handling; real backend integration is covered by the Java
// PlaygroundServerTest and the Playwright journey in e2e/.

function fetchReturning(status: number, body: unknown, raw = false): typeof fetch {
  return async () =>
    new Response(raw ? (body as string) : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
}

async function failure(p: Promise<unknown>): Promise<ApiFailure> {
  try {
    await p;
  } catch (e) {
    if (e instanceof ApiFailure) return e;
    throw e;
  }
  throw new Error('expected the request to fail');
}

describe('parseSession', () => {
  it('accepts real optimizer sessions and compile errors', () => {
    const s = parseSession(constantLocalsJson);
    expect(s.status).toBe('optimized');
    expect(s.methods.map((m) => m.name)).toContain('scaled');
    const b = parseSession(brokenJson);
    expect(b.status).toBe('compile-error');
    expect(b.compilation.diagnostics[0]).toMatchObject({ kind: 'error', line: 2 });
  });

  it('rejects malformed sessions with the failing path', () => {
    const bad = structuredClone(constantLocalsJson) as { methods: { steps: { kind: string }[] }[] };
    bad.methods[0]!.steps[1]!.kind = 'teleport';
    expect(() => parseSession(bad)).toThrow(ResponseShapeError);
    expect(() => parseSession(bad)).toThrow(/session\.methods\[0\]\.steps\[1\]\.kind/);
    expect(() => parseSession({ ...constantLocalsJson, methods: 'x' })).toThrow(/session\.methods/);
    expect(() => parseSession(null)).toThrow(ResponseShapeError);
  });

  it('requires a snapshot on steps that changed the IR', () => {
    const bad = structuredClone(constantLocalsJson) as { methods: { steps: { snapshot: unknown }[] }[] };
    bad.methods[1]!.steps[2]!.snapshot = null;
    expect(() => parseSession(bad)).toThrow(/snapshot should be present/);
  });

  it('parses the pass catalog', () => {
    const meta = parseMeta(metaJson);
    expect(meta.passes.map((p) => p.id)).toEqual(['fold', 'peephole', 'dse', 'cfg']);
    expect(meta.rewrites.length).toBeGreaterThan(10);
  });
});

describe('client error normalization', () => {
  it('returns parsed data from a success envelope', async () => {
    const meta = await fetchMeta(undefined, fetchReturning(200, { ok: true, data: metaJson }));
    expect(meta.release).toBe('21');
  });

  it('turns an error envelope into an http failure with the server message', async () => {
    const f = await failure(
      optimize('', [], undefined, fetchReturning(400, { ok: false, error: { code: 'empty_source', message: 'enter code' } })),
    );
    expect(f.error).toEqual({ kind: 'http', code: 'empty_source', message: 'enter code', status: 400 });
  });

  it('reports non-JSON and wrongly shaped responses', async () => {
    const notJson = await failure(fetchMeta(undefined, fetchReturning(502, '<html>bad gateway</html>', true)));
    expect(notJson.error.kind).toBe('shape');
    expect(notJson.error.status).toBe(502);
    const wrongShape = await failure(fetchMeta(undefined, fetchReturning(200, { ok: true, data: { version: 1 } })));
    expect(wrongShape.error.kind).toBe('shape');
  });

  it('reports network failures and cancellation', async () => {
    const down: typeof fetch = async () => {
      throw new TypeError('Failed to fetch');
    };
    expect((await failure(fetchMeta(undefined, down))).error.kind).toBe('network');

    const controller = new AbortController();
    const hanging: typeof fetch = (_url, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      });
    const pending = optimize('class A {}', [], controller.signal, hanging);
    controller.abort();
    expect((await failure(pending)).error.kind).toBe('aborted');
  });

  it('sends disabled passes in the query string and the source as the body', async () => {
    let seen: { url: string; body: unknown } | null = null;
    const spy: typeof fetch = async (url, init) => {
      seen = { url: String(url), body: init?.body };
      return new Response(JSON.stringify({ ok: true, data: brokenJson }), { status: 200 });
    };
    await optimize('class A {}', ['fold', 'dse'], undefined, spy);
    expect(seen).toEqual({ url: '/api/optimize?disable=fold%2Cdse', body: 'class A {}' });
  });
});
