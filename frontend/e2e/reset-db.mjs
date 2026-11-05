// Готовит отдельную БД для браузерных тестов: те же скрипты, что и в рабочем развёртывании.
// Работает только с базой skhodim_e2e, рабочие и учебные базы не затрагиваются.
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const sql = join(here, '..', '..', 'sql');
const DB = 'skhodim_e2e';
const env = {
  ...process.env,
  PGPASSWORD: process.env.PGPASSWORD ?? 'postgres',
  PGCLIENTENCODING: 'UTF8',
};
const base = ['-h', process.env.PGHOST ?? 'localhost', '-U', process.env.PGUSER ?? 'postgres'];

function psql(args) {
  return execFileSync('psql', [...base, ...args], { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

const exists = psql(['-d', 'postgres', '-Atc', `SELECT 1 FROM pg_database WHERE datname = '${DB}'`]).trim();
if (!exists) {
  psql(['-d', 'postgres', '-c', `CREATE DATABASE ${DB} ENCODING 'UTF8' TEMPLATE template0`]);
}
for (const file of ['91_reset_objects', '01_schema', '02_logic', '03_queries_indexes', '08_stage4', '09_base_data']) {
  psql(['-d', DB, '-q', '-v', 'ON_ERROR_STOP=1', '-v', 'app_schema=skhodim', '-f', join(sql, `${file}.sql`)]);
}
console.log(`База ${DB} готова`);
