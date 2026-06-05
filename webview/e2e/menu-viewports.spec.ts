import { expect, test, type Locator, type Page } from '@playwright/test';

type BridgeWindow = Window & typeof globalThis & {
  sendToJava?: (message: string) => void;
};

const NODE_PROCESS_SNAPSHOT = {
  snapshotAt: Date.now(),
  totals: { daemon: 1, channel: 1, orphan: 1, all: 3 },
  processes: [
    {
      id: 'daemon-1',
      kind: 'DAEMON',
      provider: 'claude',
      pid: 3010,
      alive: true,
      startedAt: Date.now() - 120_000,
      uptimeMs: 120_000,
      command: 'node daemon.js',
      heapUsed: 42 * 1024 * 1024,
      activeRequestCount: 0,
      orphan: false,
    },
    {
      id: 'channel-1',
      kind: 'CHANNEL',
      provider: 'codex',
      pid: 3011,
      alive: true,
      startedAt: Date.now() - 60_000,
      uptimeMs: 60_000,
      command: 'node channel.js',
      activeRequestCount: 1,
      tabName: 'CC GUI',
      orphan: false,
    },
    {
      id: 'orphan-1',
      kind: 'ORPHAN',
      provider: 'claude',
      pid: 3012,
      alive: true,
      startedAt: Date.now() - 30_000,
      uptimeMs: 30_000,
      command: 'node orphan.js',
      activeRequestCount: 0,
      orphan: true,
    },
  ],
};

const CLAUDE_PROVIDERS_PAYLOAD = [
  { id: 'local-settings', name: 'Use local settings.json', isActive: true },
  { id: 'cli-login', name: 'Use CLI login', isActive: false },
  { id: 'proxy-a', name: 'Proxy A', remark: 'fast route', isActive: false },
];

const CODEX_PROVIDERS_PAYLOAD = [
  { id: 'codex-cli-login', name: 'Use local Codex config', isActive: true },
  { id: 'codex-proxy', name: 'Codex Proxy', remark: 'workspace config', isActive: false },
];

const LONG_MODEL = {
  id: 'vendor/super-long-model-name-that-should-not-force-horizontal-overflow-in-selector-menus',
  label: 'Extremely Long Claude-Compatible Model Display Name With Multiple Provider And Capability Suffixes',
  description: 'A very long model description that should remain clipped inside the selector row instead of pushing the dropdown outside the visible webview viewport.',
};

async function installBridgeMocks(page: Page) {
  await page.addInitScript(({ processSnapshot, claudeProviders, codexProviders, longModel }) => {
    localStorage.setItem('model-selection-state', JSON.stringify({
      provider: 'claude',
      claudeModel: 'claude-sonnet-4-6',
      codexModel: 'gpt-5.5',
      claudePermissionMode: 'bypassPermissions',
      codexPermissionMode: 'default',
      longContextEnabled: true,
      reasoningEffort: 'high',
    }));
    localStorage.setItem('claude-custom-models', JSON.stringify([longModel]));
    localStorage.setItem('lastSeenChangelogVersion', '0.4.4');

    const hideVConsole = () => {
      const style = document.createElement('style');
      style.textContent = '#__vconsole { display: none !important; pointer-events: none !important; }';
      (document.head || document.documentElement)?.appendChild(style);
    };
    if (document.head || document.documentElement) {
      hideVConsole();
    } else {
      window.addEventListener('DOMContentLoaded', hideVConsole, { once: true });
    }

    const respond = (callbackName: string, payload: unknown) => {
      window.setTimeout(() => {
        const callback = (window as unknown as Record<string, unknown>)[callbackName];
        if (typeof callback === 'function') {
          callback(JSON.stringify(payload));
        }
      }, 0);
    };

    (window as BridgeWindow).sendToJava = (message: string) => {
      if (message.startsWith('get_node_processes:')) respond('updateNodeProcesses', processSnapshot);
      if (message.startsWith('get_providers:')) respond('updateProviders', claudeProviders);
      if (message.startsWith('get_codex_providers:')) respond('updateCodexProviders', codexProviders);
    };
  }, {
    processSnapshot: NODE_PROCESS_SNAPSHOT,
    claudeProviders: CLAUDE_PROVIDERS_PAYLOAD,
    codexProviders: CODEX_PROVIDERS_PAYLOAD,
    longModel: LONG_MODEL,
  });
}

function collectPageErrors(page: Page) {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  return errors;
}

function significantErrors(errors: string[]) {
  return errors.filter((error) => !error.includes('ResizeObserver loop'));
}

async function expectInsideViewport(page: Page, locator: Locator, label: string) {
  const box = await locator.boundingBox();
  expect(box, `${label} should have a visible bounding box`).not.toBeNull();
  const viewport = page.viewportSize();
  expect(viewport, 'viewport should be available').not.toBeNull();
  if (!box || !viewport) return;

  const tolerance = 2;
  expect(box.x, `${label} left edge`).toBeGreaterThanOrEqual(-tolerance);
  expect(box.y, `${label} top edge`).toBeGreaterThanOrEqual(-tolerance);
  expect(box.x + box.width, `${label} right edge`).toBeLessThanOrEqual(viewport.width + tolerance);
  expect(box.y + box.height, `${label} bottom edge`).toBeLessThanOrEqual(viewport.height + tolerance);
}

async function expectContainedWithin(container: Locator, child: Locator, label: string) {
  const containerBox = await container.boundingBox();
  const childBox = await child.boundingBox();
  expect(containerBox, `${label} container should have a visible bounding box`).not.toBeNull();
  expect(childBox, `${label} child should have a visible bounding box`).not.toBeNull();
  if (!containerBox || !childBox) return;

  const tolerance = 2;
  expect(childBox.x, `${label} left edge`).toBeGreaterThanOrEqual(containerBox.x - tolerance);
  expect(childBox.x + childBox.width, `${label} right edge`).toBeLessThanOrEqual(containerBox.x + containerBox.width + tolerance);
}

async function expectSubmenuAnchoredToRow(page: Page, trigger: Locator, submenu: Locator, label: string) {
  const triggerBox = await trigger.boundingBox();
  const submenuBox = await submenu.boundingBox();
  expect(triggerBox, `${label} trigger should have a visible bounding box`).not.toBeNull();
  expect(submenuBox, `${label} submenu should have a visible bounding box`).not.toBeNull();
  const viewport = page.viewportSize();
  expect(viewport, 'viewport should be available').not.toBeNull();
  if (!triggerBox || !submenuBox || !viewport) return;

  const verticalOverlap = Math.max(
    0,
    Math.min(triggerBox.y + triggerBox.height, submenuBox.y + submenuBox.height) - Math.max(triggerBox.y, submenuBox.y),
  );
  expect(verticalOverlap, `${label} should stay vertically attached to trigger row`).toBeGreaterThan(12);

  const rightSideGap = Math.abs(submenuBox.x - (triggerBox.x + triggerBox.width));
  const leftSideGap = Math.abs(triggerBox.x - (submenuBox.x + submenuBox.width));
  const horizontalGap = Math.min(rightSideGap, leftSideGap);
  const horizontalOverlap = Math.max(
    0,
    Math.min(triggerBox.x + triggerBox.width, submenuBox.x + submenuBox.width) - Math.max(triggerBox.x, submenuBox.x),
  );

  expect(
    horizontalGap <= 40 || horizontalOverlap > 20,
    `${label} should be adjacent to or intentionally overlap trigger row`,
  ).toBe(true);
}

async function closeOpenMenus(page: Page) {
  await page.mouse.click(8, 8);
  await page.waitForTimeout(50);
}

async function openSelectorMenu(page: Page, button: Locator, label: string) {
  await button.click();
  const dropdown = page.locator('.selector-dropdown').first();
  await expect(dropdown, `${label} dropdown`).toBeVisible();
  await expectInsideViewport(page, dropdown, `${label} dropdown`);
  await closeOpenMenus(page);
}

test.beforeEach(async ({ page }) => {
  await installBridgeMocks(page);
});

test('footer selector menus render inside the viewport', async ({ page }) => {
  const errors = collectPageErrors(page);
  await page.goto('/');
  await expect(page.locator('.button-area-left')).toBeVisible();
  await expect(page.locator('.button-area').first()).toHaveAttribute('data-provider', 'claude');

  const buttons = page.locator('.button-area-left .selector-button');
  await expect(buttons).toHaveCount(5);

  await openSelectorMenu(page, buttons.nth(0), 'config');
  await openSelectorMenu(page, buttons.nth(1), 'provider');
  await openSelectorMenu(page, buttons.nth(2), 'mode');
  await openSelectorMenu(page, buttons.nth(3), 'model');
  await openSelectorMenu(page, buttons.nth(4), 'reasoning');

  expect(significantErrors(errors)).toEqual([]);
});

test('config submenus stay visible across constrained viewports', async ({ page }) => {
  const errors = collectPageErrors(page);
  await page.goto('/');
  await expect(page.locator('.button-area-left')).toBeVisible();

  const configButton = page.locator('.button-area-left .selector-button').first();
  await configButton.click();
  const mainDropdown = page.locator('.selector-dropdown').first();
  await expect(mainDropdown).toBeVisible();
  await expectInsideViewport(page, mainDropdown, 'config dropdown');

  const topLevelRows = mainDropdown.locator(':scope > .selector-option');
  const nodeProcessRow = topLevelRows.filter({ hasText: /Node|进程|process/i }).first();
  await nodeProcessRow.hover();
  const nodeDropdown = page.locator('.node-process-dropdown');
  await expect(nodeDropdown).toBeVisible();
  await expect(page.getByText('PID 3012')).toBeVisible();
  await expectInsideViewport(page, nodeDropdown, 'node process submenu');
  await expectSubmenuAnchoredToRow(page, nodeProcessRow, nodeDropdown, 'node process submenu');

  const runtimeProviderRow = topLevelRows.filter({ hasText: /provider|切换|提供/i }).first();
  await runtimeProviderRow.hover();
  const runtimeDropdown = page.locator('.runtime-provider-dropdown');
  await expect(runtimeDropdown).toBeVisible();
  await expectInsideViewport(page, runtimeDropdown, 'runtime provider submenu');
  await expectSubmenuAnchoredToRow(page, runtimeProviderRow, runtimeDropdown, 'runtime provider submenu');

  const agentRow = topLevelRows.first();
  await agentRow.hover();
  const agentDropdown = agentRow.locator('.selector-dropdown').first();
  await expect(agentDropdown).toBeVisible();
  await expectInsideViewport(page, agentDropdown, 'agent submenu');
  await expectSubmenuAnchoredToRow(page, agentRow, agentDropdown, 'agent submenu');

  expect(significantErrors(errors)).toEqual([]);
});

test('long model and mode text stays contained in selector menus', async ({ page }) => {
  const errors = collectPageErrors(page);
  await page.goto('/');
  await expect(page.locator('.button-area-left')).toBeVisible();

  const buttons = page.locator('.button-area-left .selector-button');
  await expect(buttons).toHaveCount(5);

  await buttons.nth(2).click();
  const modeDropdown = page.locator('.selector-dropdown').first();
  await expect(modeDropdown).toBeVisible();
  await expectInsideViewport(page, modeDropdown, 'mode dropdown with long descriptions');
  const longModeOption = modeDropdown.locator('.selector-option').filter({ hasText: 'Fully automated' }).first();
  const longModeDescription = longModeOption.locator('span').filter({ hasText: 'Fully automated' }).first();
  await expect(longModeDescription).toBeVisible();
  await expectContainedWithin(longModeOption, longModeDescription, 'long mode description');
  await closeOpenMenus(page);

  await buttons.nth(3).click();
  const modelDropdown = page.locator('.selector-dropdown').first();
  await expect(modelDropdown).toBeVisible();
  await expectInsideViewport(page, modelDropdown, 'model dropdown with long custom model');
  const longModelOption = modelDropdown.locator('.selector-option').filter({ hasText: LONG_MODEL.label }).first();
  const longModelLabel = longModelOption.locator('span').filter({ hasText: LONG_MODEL.label }).first();
  const longModelDescription = longModelOption.locator('span').filter({ hasText: LONG_MODEL.description }).first();
  await expect(longModelLabel).toBeVisible();
  await expect(longModelDescription).toBeVisible();
  await expectContainedWithin(longModelOption, longModelLabel, 'long model label');
  await expectContainedWithin(longModelOption, longModelDescription, 'long model description');

  expect(significantErrors(errors)).toEqual([]);
});
