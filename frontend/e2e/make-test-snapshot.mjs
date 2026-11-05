// Тестовая выгрузка в формате KudaGo: только для автотестов и локальной проверки интерфейса.
// Все названия помечены [ТЕСТ], а ссылки ведут на example.org, чтобы это не приняли за настоящую афишу.
import { writeFileSync } from 'node:fs';

const CATEGORIES = ['cinema', 'exhibition', 'concert', 'theater', 'education', 'festival', 'stand-up', 'tour'];
const TITLES = [
  'Кинопоказ', 'Выставка', 'Концерт', 'Спектакль', 'Лекция', 'Фестиваль', 'Стендап', 'Прогулка',
];
const METROS = ['Приморская', 'Горьковская', 'Василеостровская', 'Невский проспект', 'Площадь Восстания', 'Адмиралтейская'];

const now = Math.floor(Date.now() / 1000);
const DAY = 86400;

const results = Array.from({ length: 24 }, (_, i) => {
  const kind = i % CATEGORIES.length;
  const start = now + DAY * (1 + i) + 3600 * (i % 5);
  const free = i % 4 === 0;
  return {
    id: 7000 + i,
    title: `[ТЕСТ] ${TITLES[kind]} №${i + 1}`,
    description: `<p>Тестовое описание номер ${i + 1}. Это не настоящее событие: запись нужна только для проверки интерфейса.</p>`,
    body_text: `<p>Тестовое описание номер ${i + 1}. Это не настоящее событие: запись нужна только для проверки интерфейса. После показа запланировано обсуждение, поэтому текст специально длиннее обычного и проверяет, как страница сворачивает описание и показывает кнопку.</p>`,
    dates: i % 6 === 0
      ? [{ start, end: start + 7200 }, { start: start + DAY * 2, end: start + DAY * 2 + 7200 }]
      : [{ start, end: start + 7200 }],
    place: {
      id: 800 + (i % METROS.length),
      title: `Тестовая площадка ${1 + (i % METROS.length)}`,
      address: `Санкт-Петербург, тестовая улица, ${1 + (i % METROS.length)}`,
      subway: METROS[i % METROS.length],
      coords: { lat: 59.93 + (i % METROS.length) * 0.005, lon: 30.31 + (i % METROS.length) * 0.006 },
    },
    categories: [CATEGORIES[kind]],
    tags: ['тест'],
    price: free ? '' : `от ${300 + i * 50} рублей`,
    is_free: free,
    age_restriction: i % 3 === 0 ? '18+' : '12+',
    images: [],
    site_url: `https://example.org/test-event/${i + 1}`,
  };
});

const target = process.argv[2];
if (!target) {
  throw new Error('Укажите путь к файлу: node make-test-snapshot.mjs <file>');
}
writeFileSync(target, JSON.stringify({ results }, null, 2));
console.log(`Записано событий: ${results.length} -> ${target}`);
