// Запуск Playwright в режиме скриншотов без лишних зависимостей для кроссплатформенных переменных.
import { spawnSync } from 'node:child_process';

const result = spawnSync('npx', ['playwright', 'test'], {
  stdio: 'inherit',
  shell: true,
  env: { ...process.env, SCREENS: '1' },
});
process.exit(result.status ?? 1);
