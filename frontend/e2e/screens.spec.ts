// Скриншоты для отчёта. Данные тестовые и помечены [ТЕСТ], пока в каталоге нет реальной афиши.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect, type Browser } from '@playwright/test';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, '..', '..', 'docs', 'screens');
const SNAPSHOT = join(tmpdir(), `skhodim-screens-${process.pid}.json`);
const PASSWORD = 'Demo12345!';

async function open(browser: Browser, width: number, height: number, email: string, dark = false) {
  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: 2,
    locale: 'ru-RU',
    timezoneId: 'Europe/Moscow',
    colorScheme: dark ? 'dark' : 'light',
  });
  const page = await context.newPage();
  const res = await page.request.post('/api/v1/auth/login', { data: { email, password: PASSWORD } });
  const { token } = await res.json();
  await page.goto('/login');
  await page.evaluate((t) => localStorage.setItem('skhodim-token', t), token);
  return page;
}

test('снять экраны', async ({ browser, request }) => {
  test.setTimeout(180_000);
  execFileSync('node', [join(here, 'make-test-snapshot.mjs'), SNAPSHOT]);
  const login = await request.post('/api/v1/auth/login', { data: { email: 'demo100@example.org', password: PASSWORD } });
  const admin = (await login.json()).token;
  const res = await request.post('/api/v1/admin/sync/snapshot', {
    headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
    data: readFileSync(SNAPSHOT, 'utf8'),
  });
  expect(res.ok()).toBeTruthy();
  execFileSync('psql', [
    '-h', process.env.PGHOST ?? 'localhost', '-U', process.env.PGUSER ?? 'postgres', '-d', 'skhodim_e2e',
    '-q', '-v', 'ON_ERROR_STOP=1', '-v', 'app_schema=skhodim', '-f', join(here, '..', '..', 'sql', '10_demo_activity.sql'),
  ], { env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD ?? 'postgres', PGCLIENTENCODING: 'UTF8' }, stdio: 'ignore' });

  const mobile = await open(browser, 390, 844, 'demo1@example.org');
  const shots: [string, string][] = [
    ['swipe', '/'],
    ['event', '/events/1'],
    ['going', '/events/1/going'],
    ['person', '/users/5?event=1&session=1'],
    ['invites', '/invites'],
    ['invite-sent', '/invites/4'],
    ['chat', '/chats/1'],
    ['lists', '/list'],
    ['social', '/social'],
    ['profile', '/profile'],
    ['taste', '/profile/taste'],
    ['notifications', '/notifications'],
  ];
  for (const [name, path] of shots) {
    await mobile.goto(path);
    await mobile.waitForLoadState('networkidle').catch(() => undefined);
    await mobile.waitForTimeout(400);
    await mobile.screenshot({ path: join(OUT, `m-${name}.png`) });
  }
  await mobile.goto('/');
  await mobile.getByRole('button', { name: /^Фильтры/ }).click();
  await mobile.waitForTimeout(400);
  await mobile.screenshot({ path: join(OUT, 'm-filters.png') });

  const desktop = await open(browser, 1280, 800, 'demo1@example.org');
  for (const [name, path] of [['swipe', '/'], ['event', '/events/1'], ['chat', '/chats/1']] as const) {
    await desktop.goto(path);
    await desktop.waitForLoadState('networkidle').catch(() => undefined);
    await desktop.waitForTimeout(400);
    await desktop.screenshot({ path: join(OUT, `d-${name}.png`) });
  }

  const dark = await open(browser, 1280, 800, 'demo1@example.org', true);
  await dark.goto('/social');
  await dark.waitForLoadState('networkidle').catch(() => undefined);
  await dark.waitForTimeout(400);
  await dark.screenshot({ path: join(OUT, 'd-social-dark.png') });

  const moderator = await open(browser, 1280, 800, 'demo100@example.org');
  await moderator.goto('/admin');
  await moderator.getByRole('tab', { name: 'Импорт афиши' }).click();
  await moderator.waitForLoadState('networkidle').catch(() => undefined);
  await moderator.waitForTimeout(400);
  await moderator.screenshot({ path: join(OUT, 'd-admin.png') });
});
