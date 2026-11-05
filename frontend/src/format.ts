const CITY_TZ = 'Europe/Moscow';

const dayFmt = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', timeZone: CITY_TZ });
const weekdayFmt = new Intl.DateTimeFormat('ru-RU', { weekday: 'short', timeZone: CITY_TZ });
const timeFmt = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', timeZone: CITY_TZ });

/** «30 октября, пт · 19:00»: время всегда петербургское, где бы ни был пользователь. */
export function eventDate(iso?: string | null, end?: string | null): string {
  if (!iso) return 'Дата уточняется';
  const start = new Date(iso);
  const finish = end ? new Date(end) : null;
  // Выставка началась в июле и идёт до декабря: дата начала здесь только запутает.
  if (finish && start.getTime() <= Date.now() && finish.getTime() > Date.now()) {
    return `Идёт до ${dayFmt.format(finish)}`;
  }
  const base = `${dayFmt.format(start)}, ${weekdayFmt.format(start)} · ${timeFmt.format(start)}`;
  if (!finish) return base;
  if (dayFmt.format(start) !== dayFmt.format(finish)) return base;
  return `${base}–${timeFmt.format(finish)}`;
}

export function shortDate(iso?: string | null): string {
  if (!iso) return 'Дата уточняется';
  const start = new Date(iso);
  return `${dayFmt.format(start)}, ${timeFmt.format(start)}`;
}

export function clock(iso: string): string {
  return timeFmt.format(new Date(iso));
}

export function relativeTime(iso: string): string {
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return 'только что';
  if (minutes < 60) return `${minutes} мин назад`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} ч назад`;
  const days = Math.round(hours / 24);
  if (days === 1) return 'вчера';
  if (days < 7) return `${days} дн назад`;
  return dayFmt.format(new Date(iso));
}

export function plural(n: number, one: string, few: string, many: string): string {
  const a = Math.abs(n) % 100;
  const b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b > 1 && b < 5) return few;
  if (b === 1) return one;
  return many;
}

export function price(e: { isFree: boolean | null; priceFrom: number | null; priceText: string | null }): string {
  if (e.isFree) return 'Бесплатно';
  if (e.priceFrom != null && e.priceFrom > 0) {
    return `от ${new Intl.NumberFormat('ru-RU').format(e.priceFrom)} ₽`;
  }
  return e.priceText?.trim() || 'Цена на сайте';
}

export function metroLabel(metro?: string | null): string | null {
  return metro ? `м. ${metro}` : null;
}

export function place(e: { placeName: string | null; metro: string | null }): string {
  return [e.placeName, metroLabel(e.metro)].filter(Boolean).join(' · ') || 'Место уточняется';
}

/** Начало дня по Петербургу как метка UTC: нужно для фильтра «когда». */
function cityMidnight(offsetDays: number): Date {
  const now = new Date();
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: CITY_TZ, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(now);
  const midnight = new Date(`${parts}T00:00:00+03:00`);
  midnight.setUTCDate(midnight.getUTCDate() + offsetDays);
  return midnight;
}

export type WhenKey = 'any' | 'today' | 'tomorrow' | 'weekend';

export function whenRange(key: WhenKey): { from?: string; to?: string } {
  if (key === 'today') return { from: new Date().toISOString(), to: cityMidnight(1).toISOString() };
  if (key === 'tomorrow') return { from: cityMidnight(1).toISOString(), to: cityMidnight(2).toISOString() };
  if (key === 'weekend') {
    const day = new Date(cityMidnight(0).getTime() + 3 * 3600 * 1000).getUTCDay();
    const toSaturday = day === 0 ? -1 : 6 - day;
    const from = cityMidnight(toSaturday);
    const start = from.getTime() < Date.now() ? new Date() : from;
    return { from: start.toISOString(), to: cityMidnight(toSaturday + 2).toISOString() };
  }
  return {};
}

/** Свои даты из фильтра: границы дня считаются по Петербургу, «по» включает весь день. */
export function customRange(from: string | null, to: string | null): { from?: string; to?: string } {
  const range: { from?: string; to?: string } = {};
  if (from) range.from = new Date(`${from}T00:00:00+03:00`).toISOString();
  if (to) {
    const end = new Date(`${to}T00:00:00+03:00`);
    end.setUTCDate(end.getUTCDate() + 1);
    range.to = end.toISOString();
  }
  return range;
}

export function weekendLabel(): string {
  const { from, to } = whenRange('weekend');
  if (!from || !to) return '';
  const last = new Date(new Date(to).getTime() - 1000);
  return `Ближайшие выходные: ${dayFmt.format(new Date(from))} — ${dayFmt.format(last)}`;
}
