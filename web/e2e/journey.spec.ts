import { expect, test, type Page } from '@playwright/test';

const MOD = process.platform === 'darwin' ? 'Meta' : 'Control';

async function loadExample(page: Page, id: string) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'What happens when you press Optimize' })).toBeVisible();
  await page.getByLabel('Load an example').selectOption(id);
}

async function optimize(page: Page) {
  await page.getByRole('button', { name: /^Optimize/ }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Replaying recorded run' })).toBeVisible();
}

const inspectorTitle = (page: Page) => page.locator('#inspector-title');

test('optimize an example and step through the real passes', async ({ page }) => {
  await loadExample(page, 'DebugFlag');
  await optimize(page);

  // Starts at the javac input of the method that shrank most.
  await expect(inspectorTitle(page)).toContainText('javac output');
  await expect(page.locator('.pipeline .field__select')).toHaveValue('DebugFlag.compute(I)I');

  // Keyboard stepping (focus outside the editors).
  await page.locator('.pipeline__top').click();
  await page.keyboard.press('ArrowRight');
  await expect(inspectorTitle(page)).toContainText('IR built');
  await page.keyboard.press('ArrowRight');
  await expect(inspectorTitle(page)).toContainText('Constant folding');
  await expect(page.locator('.transform__name').first()).toHaveText(/loads replaced by constants|branches folded/);
  await page.keyboard.press('ArrowLeft');
  await expect(inspectorTitle(page)).toContainText('IR built');

  // Boundaries: Previous is disabled on the first step, Next on the last.
  await page.getByRole('button', { name: 'First step' }).click();
  await expect(page.getByRole('button', { name: 'Previous step' })).toHaveAttribute('aria-disabled', 'true');
  await page.getByRole('button', { name: 'Last step' }).click();
  await expect(inspectorTitle(page)).toContainText('Decision');
  await expect(page.getByRole('button', { name: 'Next step' })).toHaveAttribute('aria-disabled', 'true');
  await expect(inspectorTitle(page)).toContainText('Optimized');

  // The final code is what jopt really produced for x * 2: a shift, no println.
  const code = page.locator('.codebox__editor');
  await expect(code).toContainText('ISHL');
  await expect(code).not.toContainText('println');

  // Clicking a stage selects it; the CFG pass removed the dead block.
  await page.locator('.stage', { hasText: 'CFG simplification' }).first().click();
  await expect(page.locator('.transform__name', { hasText: 'unreachable blocks removed' })).toBeVisible();

  // HotSpot verified the output class.
  await page.getByRole('tab', { name: 'Classes' }).click();
  await expect(page.locator('.bottom__body')).toContainText('passed');

  // Reset clears the session.
  await page.getByRole('button', { name: 'Clear results' }).click();
  await expect(page.getByRole('heading', { name: 'What happens when you press Optimize' })).toBeVisible();
});

test('replay plays to the end and stops', async ({ page }) => {
  await loadExample(page, 'ConstantLocals');
  await optimize(page);
  await page.getByRole('combobox', { name: 'Replay speed' }).selectOption('4');
  await page.getByRole('button', { name: 'Play replay' }).click();
  await expect(inspectorTitle(page)).toContainText('Decision', { timeout: 20_000 });
  await expect(page.getByRole('button', { name: 'Replay from the start' })).toBeVisible();
});

test('compile errors are shown and the editor stays usable', async ({ page }) => {
  await loadExample(page, 'ConstantLocals');
  const editor = page.locator('.source .monaco-editor').first();
  await editor.click();
  await page.keyboard.press(`${MOD}+A`);
  await page.keyboard.press('Delete');
  await page.keyboard.type('public class Broken { int f() { return "x"; } ');
  await page.keyboard.press(`${MOD}+Enter`);
  await expect(page.getByText(/javac reported 1 error/)).toBeVisible();
  await expect(page.getByRole('tab', { name: /Problems \(1\)/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('status').filter({ hasText: 'Compile error' })).toBeVisible();
});

test('empty source cannot be submitted', async ({ page }) => {
  await loadExample(page, 'ConstantLocals');
  await page.locator('.source .monaco-editor').first().click();
  await page.keyboard.press(`${MOD}+A`);
  await page.keyboard.press('Delete');
  await expect(page.getByRole('button', { name: /^Optimize/ })).toBeDisabled();
  await expect(page.getByText('Enter some Java source to optimize.')).toBeVisible();
});

test('a failed request shows an error and recovers on retry', async ({ page }) => {
  await loadExample(page, 'ConstantLocals');
  // Isolates the error path: the first optimize call answers 500, later calls reach the real backend.
  let failed = false;
  await page.route('**/api/optimize*', async (route) => {
    if (failed) return route.continue();
    failed = true;
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ ok: false, error: { code: 'internal_error', message: 'simulated failure' } }),
    });
  });
  await page.getByRole('button', { name: /^Optimize/ }).click();
  await expect(page.getByText('The run failed')).toBeVisible();
  await expect(page.getByText('simulated failure')).toBeVisible();
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(inspectorTitle(page)).toContainText('javac output');
});

test('editing the source marks results as outdated', async ({ page }) => {
  await loadExample(page, 'ConstantLocals');
  await optimize(page);
  await page.locator('.source .monaco-editor').first().click();
  await page.keyboard.press('End');
  await page.keyboard.type(' ');
  await expect(page.getByText(/These results are for the previous input/)).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: 'Results are outdated' })).toBeVisible();
});

test('narrow screens switch to tabs without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('tab', { name: 'Source' })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('button', { name: /^Optimize/ }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Replaying recorded run' })).toBeVisible();
  await page.getByRole('tab', { name: 'Inspector' }).click();
  await expect(inspectorTitle(page)).toContainText('javac output');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
