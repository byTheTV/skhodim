import { defineConfig } from '@playwright/test';

const PORT = 18090;
const DB = process.env.E2E_DB_URL
  ?? 'jdbc:postgresql://localhost:5432/skhodim_e2e?currentSchema=skhodim,public';

// В CI браузер ставит Playwright, локально хватает установленного Chrome.
const channel = process.env.CI ? undefined : 'chrome';

export default defineConfig({
  testDir: './e2e',
  // Скриншоты для отчёта снимаются отдельно: npm run screens
  testIgnore: process.env.SCREENS ? ['**/app.spec.ts'] : ['**/screens.spec.ts'],
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 8_000 },
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    channel,
    locale: 'ru-RU',
    timezoneId: 'Europe/Moscow',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: `java -jar ../target/skhodim.jar --spring.profiles.active=local --server.port=${PORT} `
      + `"--spring.datasource.url=${DB}" --app.kudago.base-url=http://127.0.0.1:1/unreachable`,
    url: `http://localhost:${PORT}/api/v1/health`,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { SKHODIM_DB_PASSWORD: process.env.PGPASSWORD ?? 'postgres' },
  },
});
