import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search as SearchIcon, SearchX, SlidersHorizontal, X } from 'lucide-react';
import { api } from '../api';
import { activeFilterCount, FilterSheet, filtersToQuery, NO_FILTERS, useFilters, whenTitle } from '../filters';
import { plural } from '../format';
import { PageHeader } from '../layout';
import { Button, Chip, EmptyState, ErrorBlock, EventRow, Skeleton, useDebounced } from '../ui';

export function Search() {
  const [text, setText] = useState('');
  const query = useDebounced(text.trim());
  const [filters, setFilters] = useFilters();
  const [open, setOpen] = useState(false);
  const count = activeFilterCount(filters);

  const results = useQuery({
    queryKey: ['search', query, filters],
    queryFn: () => api.events({ ...filtersToQuery(filters), query: query || undefined, limit: 50 }),
  });
  const found = results.data?.length ?? 0;

  return (
    <div className="page">
      <PageHeader title="Поиск" back="/" />
      <div className="search-bar">
        <label className="search-input">
          <SearchIcon aria-hidden />
          <input
            type="search"
            placeholder="Название, место или тема"
            aria-label="Поиск событий"
            value={text}
            autoFocus
            onChange={(e) => setText(e.target.value)}
          />
          {text && (
            <button type="button" aria-label="Очистить" onClick={() => setText('')}><X aria-hidden /></button>
          )}
        </label>
        <button type="button" className="icon-btn" aria-label="Фильтры" onClick={() => setOpen(true)}>
          <SlidersHorizontal aria-hidden />
          {count > 0 && <span className="dot-count">{count}</span>}
        </button>
      </div>

      <div className="chips chips-row">
        <Chip active={filters.when === 'any'} onClick={() => setOpen(true)}>{whenTitle(filters)}</Chip>
        <Chip active={filters.categoryIds.length > 0} onClick={() => setOpen(true)}>
          {filters.categoryIds.length ? `Категорий: ${filters.categoryIds.length}` : 'Все категории'}
        </Chip>
        <Chip active={filters.isFree} onClick={() => setFilters({ ...filters, isFree: !filters.isFree })}>Бесплатно</Chip>
        {filters.metro && <Chip active onClick={() => setFilters({ ...filters, metro: null })}>м. {filters.metro} ✕</Chip>}
      </div>

      {results.isLoading && <Skeleton />}
      {results.isError && <ErrorBlock error={results.error} retry={() => results.refetch()} />}

      {results.data && found > 0 && (
        <>
          <h2 className="section-title">
            {query
              ? `Найдено ${found} ${plural(found, 'событие', 'события', 'событий')}`
              : 'Вся афиша'}
          </h2>
          {query && <p className="hint">Санкт-Петербург, по запросу «{query}»</p>}
          <div className="grid-events">
            {results.data.map((e) => <EventRow key={e.eventId} event={e} />)}
          </div>
        </>
      )}

      {results.data && found === 0 && (
        <EmptyState
          icon={<SearchX />}
          title="Пока ничего не нашлось"
          text={query || count
            ? 'Попробуйте название, место или другую тему, либо смягчите фильтры.'
            : 'Афиша пока пуста: события появятся после загрузки реальных данных.'}
          action={count > 0 ? <Button variant="primary" onClick={() => setFilters(NO_FILTERS)}>Сбросить фильтры</Button> : undefined}
        />
      )}

      <FilterSheet open={open} onClose={() => setOpen(false)} value={filters} onApply={setFilters} />
    </div>
  );
}
