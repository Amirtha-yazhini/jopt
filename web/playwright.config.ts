import { defineConfig } from '@playwright/test';
import { existsSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// The journey runs against the real backend serving the production build:
//   ./gradlew installDist   (repo root)   and   npm run build   (web/)
const PORT = 7072;
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const tools = resolve(root, '.tools');
const localJdk = existsSync(tools)
  ? readdirSync(tools).find((d) => d.startsWith('jdk-21'))
  : undefined;
const env: Record<string, string> = { ...(process.env as Record<string, string>) };
if (localJdk && !env.JAVA_HOME) {
  const macHome = resolve(tools, localJdk, 'Contents/Home');
  env.JAVA_HOME = existsSync(macHome) ? macHome : resolve(tools, localJdk);
}

const isWin = process.platform === 'win32';
const joptBin = resolve(root, 'build/install/jopt/bin', isWin ? 'jopt.bat' : 'jopt');

export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    // The system Chrome: no browser download needed.
    channel: 'chrome',
    viewport: { width: 1440, height: 900 },
  },
  webServer: {
    command: `"${joptBin}" playground --port ${PORT} --static "${resolve(here, 'dist')}"`,
    url: `http://localhost:${PORT}/api/health`,
    reuseExistingServer: false,
    timeout: 60_000,
    env,
  },
});
