import { useCallback, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type EventQuery } from './api';
import { weekendLabel, whenRange, type WhenKey } from './format';
import { Button, Chip, Sheet, Toggle } from './ui';

export type Filters = {
  when: WhenKey;
  categoryIds: number[];
  isFree: boolean;
  metro: string | null;
};

export const NO_FILTERS: Filters = { when: 'any', categoryIds: [], isFree: false, metro: null };

const STORAGE_KEY = 'skhodim-filters';

function loadFilters(): Filters {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? { ...NO_FILTERS, ...JSON.parse(raw) } : NO_FILTERS;
  } catch {
    return NO_FILTERS;
  }
}

/** Фильтры живут до закрытия вкладки и общие для свайпа и поиска. */
export function useFilters(): [Filters, (next: Filters) => void] {
  const [filters, setFilters] = useState<Filters>(loadFilters);
  const update = useCallback((next: Filters) => {
    setFilters(next);
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // Без хранилища фильтры просто не переживут перезагрузку.
    }
  }, []);
  return [filters, update];
}

export function filtersToQuery(filters: Filters): EventQuery {
  return {
    ...whenRange(filters.when),
    categoryIds: filters.categoryIds.length ? filters.categoryIds : undefined,
    isFree: filters.isFree || undefined,
    metro: filters.metro ?? undefined,
  };
}

export function activeFilterCount(filters: Filters): number {
  return (filters.when !== 'any' ? 1 : 0) + filters.categoryIds.length + (filters.isFree ? 1 : 0) + (filters.metro ? 1 : 0);
}

const WHEN: { value: WhenKey; label: string }[] = [
  { value: 'any', label: 'Любая дата' },
  { value: 'today', label: 'Сегодня' },
  { value: 'tomorrow', label: 'Завтра' },
  { value: 'weekend', label: 'На выходных' },
];

export function whenTitle(key: WhenKey): string {
  return WHEN.find((w) => w.value === key)?.label ?? '';
}

export function FilterSheet({ open, onClose, value, onApply }: {
  open: boolean;
  onClose: () => void;
  value: Filters;
  onApply: (next: Filters) => void;
}) {
  const categories = useQuery({ queryKey: ['categories'], queryFn: api.categories, staleTime: 3_600_000 });
  const metros = useQuery({ queryKey: ['metros'], queryFn: api.metros, enabled: open });
  const [draft, setDraft] = useState(value);
  const [lastOpen, setLastOpen] = useState(open);
  if (open !== lastOpen) {
    setLastOpen(open);
    if (open) setDraft(value);
  }
  const hint = useMemo(() => weekendLabel(), []);

  const toggleCategory = (id: number) =>
    setDraft((d) => ({
      ...d,
      categoryIds: d.categoryIds.includes(id) ? d.categoryIds.filter((c) => c !== id) : [...d.categoryIds, id],
    }));

  return (
    <Sheet open={open} onClose={onClose} title="Фильтры">
      <section className="filter-group">
        <h3>Когда</h3>
        <div className="chips">
          {WHEN.map((w) => (
            <Chip key={w.value} active={draft.when === w.value} onClick={() => setDraft({ ...draft, when: w.value })}>
              {w.label}
            </Chip>
          ))}
        </div>
        <p className="hint">{hint}</p>
      </section>
      <section className="filter-group">
        <h3>Что интересно</h3>
        <div className="chips">
          <Chip active={draft.categoryIds.length === 0} onClick={() => setDraft({ ...draft, categoryIds: [] })}>
            Все категории
          </Chip>
          {categories.data?.map((c) => (
            <Chip key={c.id} active={draft.categoryIds.includes(c.id)} onClick={() => toggleCategory(c.id)}>{c.name}</Chip>
          ))}
        </div>
      </section>
      <section className="filter-group">
        <label className="field">
          <h3>Метро</h3>
          <select
            value={draft.metro ?? ''}
            onChange={(e) => setDraft({ ...draft, metro: e.target.value || null })}
          >
            <option value="">Любая станция</option>
            {metros.data?.map((m) => <option key={m.metro} value={m.metro}>{m.metro}</option>)}
          </select>
        </label>
      </section>
      <section className="filter-group filter-row">
        <div>
          <h3>Только бесплатные</h3>
          <p className="hint">Без входного билета</p>
        </div>
        <Toggle
          label="Только бесплатные"
          checked={draft.isFree}
          onChange={(isFree) => setDraft({ ...draft, isFree })}
        />
      </section>
      <div className="sheet-actions">
        <Button variant="primary" block onClick={() => { onApply(draft); onClose(); }}>Применить фильтры</Button>
        <Button block onClick={() => { onApply(NO_FILTERS); onClose(); }}>Сбросить всё</Button>
      </div>
    </Sheet>
  );
}
