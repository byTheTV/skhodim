import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test';

const here = dirname(fileURLToPath(import.meta.url));
const SNAPSHOT = join(tmpdir(), `skhodim-e2e-snapshot-${process.pid}.json`);
const PASSWORD = 'Demo12345!';

// Тесты идут по порядку в одном потоке и работают с одной БД, но каждый проверяет свой сценарий:
// падение одного не должно скрывать результаты остальных.

async function token(api: APIRequestContext, email: string): Promise<string> {
  const res = await api.post('/api/v1/auth/login', { data: { email, password: PASSWORD } });
  expect(res.ok()).toBeTruthy();
  return (await res.json()).token;
}

async function loginByForm(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Пароль').fill(PASSWORD);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Основная навигация' }).first()).toBeAttached();
}

async function pageAs(browser: Browser, email: string, viewport = { width: 1280, height: 800 }) {
  const context = await browser.newContext({ viewport, locale: 'ru-RU', timezoneId: 'Europe/Moscow' });
  const page = await context.newPage();
  await loginByForm(page, email);
  return page;
}

/** Страница не должна уезжать вбок: это первое, что ломается на телефоне. */
async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}

test.beforeAll(async ({ request }) => {
  // Пока каталог пуст, события появляются только через настоящий импорт, как в рабочей системе.
  execFileSync('node', [join(here, 'make-test-snapshot.mjs'), SNAPSHOT]);
  const admin = await token(request, 'demo100@example.org');
  const res = await request.post('/api/v1/admin/sync/snapshot', {
    headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
    data: readFileSync(SNAPSHOT, 'utf8'),
  });
  expect(res.ok()).toBeTruthy();
  expect((await res.json()).processed).toBe(24);
});

test('гость видит афишу, а для действий его просят войти', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/');
  await expect(page.getByTestId('swipe-card').getByRole('heading')).toContainText('[ТЕСТ]');
  await page.getByRole('button', { name: 'Сходим' }).click();
  await expect(page).toHaveURL(/\/login\?next=/);

  await page.goto('/list');
  await expect(page).toHaveURL(/\/login\?next=%2Flist/);

  await page.goto('/events/1');
  await expect(page.getByRole('heading', { level: 2 }).first()).toContainText('[ТЕСТ]');
  await page.getByRole('switch', { name: 'Хочу пойти' }).click();
  await expect(page).toHaveURL(/\/login\?next=\/events\/1/);
  expect(errors).toEqual([]);
});

test('неверный пароль объясняется по-русски', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Email').fill('demo1@example.org');
  await page.getByLabel('Пароль').fill('wrong-password');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Неверный email или пароль');
});

test('регистрация: интересы 3–5, занятый email, вход сразу после создания', async ({ page }) => {
  await page.goto('/register');
  await page.getByLabel('Email').fill('demo1@example.org');
  await page.getByLabel(/Пароль/).fill('Pass12345!');
  await page.getByLabel('Имя').fill('Тест');
  await page.getByLabel('Ник').fill('test_user');
  await page.getByRole('button', { name: 'Дальше' }).click();

  const done = page.getByRole('button', { name: 'Готово' });
  await page.getByRole('button', { name: 'Кино' }).click();
  await page.getByRole('button', { name: 'Театр' }).click();
  await expect(done).toBeDisabled();
  await page.getByRole('button', { name: 'Концерт' }).click();
  await done.click();
  await expect(page.getByRole('alert')).toHaveText('Такой email уже зарегистрирован');

  await page.getByRole('button', { name: 'Назад' }).click();
  await page.getByLabel('Email').fill('new-user@example.org');
  await page.getByRole('button', { name: 'Дальше' }).click();
  await page.getByRole('button', { name: 'Готово' }).click();
  await expect(page.getByRole('navigation', { name: 'Основная навигация' }).first()).toBeAttached();
  await page.goto('/profile');
  await expect(page.getByRole('heading', { name: 'Тест' })).toBeVisible();
});

test('свайп: лайк, пропуск, возврат карточки, жест мышью', async ({ browser }) => {
  const page = await pageAs(browser, 'demo6@example.org');
  await page.goto('/');
  // Во время анимации на экране две карточки, поэтому текущую сообщает сама колода.
  const deck = page.locator('.deck');
  const first = await deck.getAttribute('data-current');

  await page.getByRole('button', { name: 'Сходим' }).click();
  await expect(page.getByText('Добавлено в список')).toBeVisible();
  await expect(deck).not.toHaveAttribute('data-current', first!);
  const second = await deck.getAttribute('data-current');

  await page.getByRole('button', { name: 'Мимо' }).click();
  await expect(deck).not.toHaveAttribute('data-current', second!);
  await page.getByRole('button', { name: 'Вернуть предыдущую карточку' }).click();
  await expect(deck).toHaveAttribute('data-current', second!);

  // Настоящее перетаскивание вправо: тот же лайк.
  await expect(page.getByTestId('swipe-card')).toHaveCount(1);
  const box = (await page.getByTestId('swipe-card').boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + 80);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 320, box.y + 80, { steps: 12 });
  await page.mouse.up();
  await expect(deck).not.toHaveAttribute('data-current', second!);

  await page.goto('/list');
  await page.getByRole('tab', { name: /Понравилось/ }).click();
  await expect(page.getByRole('heading', { name: first!, exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: second!, exact: true })).toBeVisible();

  await page.getByRole('button', { name: `Убрать «${first}» из понравившегося` }).click();
  await expect(page.getByRole('heading', { name: first!, exact: true })).toHaveCount(0);
  await page.context().close();
});

test('поиск и фильтры: текст, бесплатные, пустой результат, сброс', async ({ page }) => {
  await page.goto('/search');
  await page.getByLabel('Поиск событий').fill('кинопоказ');
  await expect(page.getByRole('heading', { name: /Найдено/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /Кинопоказ/ }).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: /Лекция/ })).toHaveCount(0);

  await page.getByLabel('Поиск событий').fill('нетакогособытия');
  await expect(page.getByRole('heading', { name: 'Пока ничего не нашлось' })).toBeVisible();

  await page.getByLabel('Очистить').click();
  await page.getByRole('button', { name: 'Фильтры', exact: true }).click();
  await page.getByRole('switch', { name: 'Только бесплатные' }).click();
  await page.getByRole('button', { name: 'Применить фильтры' }).click();
  await expect(page.getByRole('heading', { name: 'Вся афиша' })).toBeVisible();
  const prices = page.locator('.event-row-price');
  await expect(prices.first()).toBeVisible();
  await expect(page.locator('.event-row-price', { hasText: '₽' })).toHaveCount(0);
  for (const text of await prices.allTextContents()) {
    expect(text).toContain('Бесплатно');
  }

  // Свои даты: диапазон в прошлом не должен ничего находить.
  await page.getByRole('button', { name: 'Фильтры', exact: true }).click();
  await page.getByRole('button', { name: 'Выбрать даты' }).click();
  await page.getByLabel('С', { exact: true }).fill('2020-01-01');
  await page.getByLabel('По', { exact: true }).fill('2020-01-02');
  await page.getByRole('button', { name: 'Применить фильтры' }).click();
  await expect(page.getByRole('heading', { name: 'Пока ничего не нашлось' })).toBeVisible();

  await page.getByRole('button', { name: 'Фильтры', exact: true }).click();
  await page.getByRole('button', { name: 'Сбросить всё' }).click();
  await expect(page.locator('.event-row-price', { hasText: '₽' }).first()).toBeVisible();
});

test('приватность: список желающих закрыт, пока сам не отметишь «Хочу пойти»', async ({ browser }) => {
  const page = await pageAs(browser, 'demo7@example.org');
  await page.goto('/events/3/going');
  await expect(page.getByRole('heading', { name: 'Сначала отметьте «Хочу пойти»' })).toBeVisible();

  await page.goto('/events/3');
  await page.getByRole('switch', { name: 'Хочу пойти' }).click();
  await expect(page.getByText('Вы идёте!')).toBeVisible();
  await page.getByRole('link', { name: /Желающие/ }).click();
  await expect(page.getByRole('heading', { name: 'Вы пока первый' })).toBeVisible();
  await page.context().close();
});

test('главный сценарий: хочу пойти → приглашение → принятие → чат между двумя людьми', async ({ browser }) => {
  const nikita = await pageAs(browser, 'demo1@example.org');
  const sasha = await pageAs(browser, 'demo5@example.org', { width: 375, height: 812 });

  // Саша отмечается первым, Никита видит его в списке.
  await sasha.goto('/events/5');
  await sasha.getByRole('switch', { name: 'Хочу пойти' }).click();
  await expect(sasha.getByText('Вы идёте', { exact: true })).toBeVisible();

  await nikita.goto('/events/5');
  await nikita.getByRole('switch', { name: 'Хочу пойти' }).click();
  await nikita.getByRole('link', { name: /Желающие/ }).click();
  await nikita.getByRole('link', { name: /sasha_jazz/ }).click();
  await expect(nikita.getByRole('heading', { name: 'Вы оба хотите пойти' })).toBeVisible();
  await nikita.getByRole('button', { name: 'Предложить пойти вместе' }).click();
  await expect(nikita.getByRole('heading', { name: 'Приглашение отправлено' })).toBeVisible();
  await expect(nikita.getByRole('button', { name: 'Чат пока недоступен' })).toBeDisabled();

  // Саша получает уведомление и принимает.
  await sasha.goto('/notifications');
  await sasha.getByRole('button', { name: /Никита приглашает вас/ }).click();
  await expect(sasha.getByRole('heading', { name: 'Никита приглашает вас' })).toBeVisible();
  await sasha.getByRole('button', { name: 'Принять' }).click();
  await expect(sasha.getByRole('heading', { name: 'Вы и Никита идёте вместе' })).toBeVisible();

  // У Никиты статус обновляется сам, без перезагрузки.
  await expect(nikita.getByRole('heading', { name: 'Саша принимает приглашение' })).toBeVisible({ timeout: 10_000 });
  await nikita.getByRole('link', { name: 'Открыть чат' }).click();

  await nikita.getByLabel('Сообщение').fill('Привет! <b>Встретимся</b> у входа?');
  await nikita.getByRole('button', { name: 'Отправить' }).click();
  await expect(nikita.locator('.bubble-mine')).toContainText('<b>Встретимся</b>');

  await sasha.getByRole('link', { name: 'Открыть чат' }).click();
  await expect(sasha.locator('.bubble').last()).toContainText('<b>Встретимся</b>', { timeout: 10_000 });
  await sasha.getByLabel('Сообщение').fill('Да, давай!');
  await sasha.keyboard.press('Enter');
  await expect(nikita.locator('.bubble').last()).toContainText('Да, давай!', { timeout: 10_000 });
  await expectNoHorizontalScroll(sasha);

  await nikita.context().close();
  await sasha.context().close();
});

test('отклонённое и отозванное приглашения', async ({ browser }) => {
  const nikita = await pageAs(browser, 'demo1@example.org');
  const dasha = await pageAs(browser, 'demo6@example.org');
  const ilya = await pageAs(browser, 'demo7@example.org');
  await nikita.goto('/events/7');
  await nikita.getByRole('switch', { name: 'Хочу пойти' }).click();
  await expect(nikita.getByText('Вы идёте', { exact: true })).toBeVisible();
  for (const other of [dasha, ilya]) {
    await other.goto('/events/7');
    await other.getByRole('switch', { name: 'Хочу пойти' }).click();
    await expect(other.getByText('Вы идёте', { exact: true })).toBeVisible();
  }

  await dasha.goto('/users/1?event=7');
  await dasha.getByRole('button', { name: 'Предложить пойти вместе' }).click();
  await expect(dasha.getByRole('heading', { name: 'Приглашение отправлено' })).toBeVisible();
  await nikita.goto('/invites');
  const card = nikita.locator('.invite-card', { hasText: 'Даша приглашает вас' });
  await card.getByRole('button', { name: 'Отклонить' }).click();
  await expect(nikita.getByRole('heading', { name: 'Вы отклонили приглашение' })).toBeVisible();
  await expect(dasha.getByRole('heading', { name: 'Приглашение отклонено' })).toBeVisible({ timeout: 10_000 });

  await ilya.goto('/users/1?event=7');
  await ilya.getByRole('button', { name: 'Предложить пойти вместе' }).click();
  await ilya.getByRole('button', { name: 'Отозвать приглашение' }).click();
  await expect(ilya.getByRole('heading', { name: 'Приглашение отменено' })).toBeVisible();

  for (const p of [nikita, dasha, ilya]) await p.context().close();
});

test('комментарии: HTML не исполняется, свой можно удалить', async ({ browser }) => {
  const page = await pageAs(browser, 'demo2@example.org');
  await page.goto('/events/9/comments');
  await expect(page.getByRole('heading', { name: 'Здесь пока тихо' })).toBeVisible();
  const payload = '<img src=x onerror="window.__xss=1"> кто идёт?';
  await page.getByLabel('Ваш комментарий').fill(payload);
  await page.getByRole('button', { name: 'Отправить' }).click();
  await expect(page.getByText(payload)).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined();

  await page.getByRole('button', { name: 'Удалить' }).click();
  await expect(page.getByRole('heading', { name: 'Здесь пока тихо' })).toBeVisible();
  await page.context().close();
});

test('карточка для сторис: сервер отдаёт PNG 9:16', async ({ browser }) => {
  const page = await pageAs(browser, 'demo1@example.org');
  await page.goto('/events/5');
  await page.getByRole('button', { name: 'Карточка для сторис' }).click();
  const image = page.getByRole('img', { name: /Карточка события/ });
  await expect(image).toBeVisible();
  const size = await image.evaluate((img: HTMLImageElement) => ({ w: img.naturalWidth, h: img.naturalHeight }));
  expect(size).toEqual({ w: 540, h: 960 });
  await expect(page.getByRole('link', { name: 'Сохранить PNG' })).toBeVisible();
  await page.context().close();
});

test('профиль: подпись, интересы от 3 до 5, пластинка вкуса', async ({ browser }) => {
  const page = await pageAs(browser, 'demo1@example.org');
  await page.goto('/profile/edit');
  const bio = page.getByLabel(/Коротко о себе/);
  await bio.fill('');
  await bio.pressSequentially('х'.repeat(5));
  await expect(page.getByText('5/160')).toBeVisible();
  expect(await bio.getAttribute('maxlength')).toBe('160');
  await bio.fill('Новая подпись для теста');

  const save = page.getByRole('button', { name: 'Сохранить' });
  const chosen = page.locator('.chips .chip-active');
  // Убираем интересы, пока не останется 2: сохранять нельзя.
  while (await chosen.count() > 2) await chosen.first().click();
  await expect(save).toBeDisabled();
  await page.getByRole('button', { name: 'Лекция' }).click();
  await save.click();
  await expect(page.getByText('Новая подпись для теста')).toBeVisible();

  await page.goto('/profile/taste');
  await expect(page.getByRole('heading', { name: 'Что вам нравится' })).toBeVisible();
  await expect(page.locator('.bar-row').first()).toContainText('%');
  await page.context().close();
});

test('тема: выбор сохраняется после перезагрузки', async ({ browser }) => {
  const page = await pageAs(browser, 'demo1@example.org');
  await page.goto('/profile');
  await page.getByLabel('Тема оформления').selectOption('dark');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(bg).toBe('rgb(18, 12, 43)');
  await page.getByLabel('Тема оформления').selectOption('auto');
  await page.context().close();
});

test('модерация: жалоба доходит до модератора, скрытый комментарий исчезает', async ({ browser }) => {
  const author = await pageAs(browser, 'demo3@example.org');
  await author.goto('/events/11/comments');
  await author.getByLabel('Ваш комментарий').fill('Спорный комментарий для модерации');
  await author.getByRole('button', { name: 'Отправить' }).click();
  await expect(author.getByText('Спорный комментарий для модерации')).toBeVisible();

  const reporter = await pageAs(browser, 'demo4@example.org');
  await reporter.goto('/events/11/comments');
  await reporter.getByRole('button', { name: 'Пожаловаться' }).click();
  await reporter.getByRole('dialog').getByRole('button', { name: 'Оскорбления' }).click();
  await reporter.getByRole('dialog').getByRole('button', { name: 'Отправить' }).click();
  await expect(reporter.getByText('Жалоба отправлена модератору')).toBeVisible();

  // Обычному участнику раздел модерации закрыт.
  await reporter.goto('/admin');
  await expect(reporter).toHaveURL(/\/$/);

  const moderator = await pageAs(browser, 'demo100@example.org');
  await moderator.goto('/admin');
  const report = moderator.locator('.invite-card', { hasText: 'Спорный комментарий для модерации' });
  await expect(report).toContainText('Оскорбления');
  await report.getByRole('button', { name: 'Скрыть комментарий' }).click();
  await expect(moderator.getByText('Жалоба рассмотрена')).toBeVisible();
  await expect(report).toHaveCount(0);

  await reporter.goto('/events/11/comments');
  await expect(reporter.getByRole('heading', { name: 'Здесь пока тихо' })).toBeVisible();

  for (const p of [author, reporter, moderator]) await p.context().close();
});

test('импорт: сбой источника виден, каталог цел; выгрузка загружается повторно без дублей', async ({ browser, request }) => {
  const before = await (await request.get('/api/v1/events?limit=100')).json();
  const page = await pageAs(browser, 'demo100@example.org');
  await page.goto('/admin');
  await page.getByRole('tab', { name: 'Импорт афиши' }).click();

  await page.getByRole('button', { name: 'Синхронизировать с KudaGo' }).click();
  await expect(page.locator('.toast-error')).toContainText('Каталог не изменён');
  await expect(page.locator('.import-row').first()).toContainText('Сбой');

  await page.getByLabel('Файл выгрузки').setInputFiles(SNAPSHOT);
  await expect(page.getByRole('status').filter({ hasText: 'Загружено событий: 24' })).toBeVisible();
  const after = await (await request.get('/api/v1/events?limit=100')).json();
  expect(after.length).toBe(before.length);
  await page.context().close();
});

test('блокировка действует сразу, даже с действующим токеном', async ({ browser, request }) => {
  const target = await pageAs(browser, 'demo9@example.org');
  const reporter = await token(request, 'demo8@example.org');
  await request.post('/api/v1/reports', {
    headers: { Authorization: `Bearer ${reporter}` },
    data: { targetType: 'user', targetId: 9, reason: 'Проверка блокировки' },
  });
  const admin = await token(request, 'demo100@example.org');
  const queue = await (await request.get('/api/v1/admin/moderation', {
    headers: { Authorization: `Bearer ${admin}` },
  })).json();
  const report = queue.find((r: { reason: string }) => r.reason === 'Проверка блокировки');
  const res = await request.put('/api/v1/admin/moderation', {
    headers: { Authorization: `Bearer ${admin}` },
    data: { reportId: report.id, action: 'BAN_USER' },
  });
  expect(res.ok()).toBeTruthy();

  await target.goto('/list');
  await expect(target).toHaveURL(/\/login/);
  await target.getByLabel('Email').fill('demo9@example.org');
  await target.getByLabel('Пароль').fill(PASSWORD);
  await target.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(target.getByRole('alert')).toHaveText('Аккаунт заблокирован модератором');
  await target.context().close();
});

test('вёрстка: на телефоне и на компьютере ничего не уезжает вбок', async ({ browser }) => {
  const paths = ['/', '/search', '/social', '/list', '/events/5', '/events/5/going', '/events/5/comments',
    '/users/2', '/invites', '/chats', '/profile', '/profile/edit', '/profile/taste', '/notifications'];
  for (const viewport of [{ width: 360, height: 700 }, { width: 1280, height: 800 }]) {
    const page = await pageAs(browser, 'demo1@example.org', viewport);
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    for (const path of paths) {
      await page.goto(path);
      await page.waitForLoadState('networkidle').catch(() => undefined);
      await expectNoHorizontalScroll(page);
    }
    expect(errors).toEqual([]);
    await page.context().close();
  }
});
