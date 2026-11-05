import { forwardRef, useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { AnimatePresence, motion, useMotionValue, useTransform, type PanInfo } from 'framer-motion';
import { ArrowRight, Heart, RotateCcw, Search as SearchIcon, SearchX, SlidersHorizontal, Ticket, X } from 'lucide-react';
import { api, type EventSummary } from '../api';
import { useAuth } from '../auth';
import { activeFilterCount, FilterSheet, filtersToQuery, NO_FILTERS, useFilters, whenTitle } from '../filters';
import { price } from '../format';
import { PageHeader } from '../layout';
import { Button, Chip, Cover, EmptyState, ErrorBlock, EventMeta, EventTags, GoingLine, Skeleton, useToast } from '../ui';

const SWIPE_DISTANCE = 110;
const PAGE_SIZE = 20;

/** Карточка улетает в ту сторону, куда её смахнули. */
const cardVariants = {
  enter: { scale: 0.96, opacity: 0 },
  center: { scale: 1, opacity: 1 },
  exit: (direction: number) => ({
    x: direction * 520,
    rotate: direction * 18,
    opacity: 0,
    transition: { duration: 0.26 },
  }),
};

const SwipeCard = forwardRef<HTMLElement, {
  event: EventSummary;
  direction: number;
  onDecide: (kind: 'LIKE' | 'SKIP') => void;
  onOpen: () => void;
}>(function SwipeCard({ event, direction, onDecide, onOpen }, ref) {
  const x = useMotionValue(0);
  const rotate = useTransform(x, [-240, 240], [-11, 11]);
  const likeOpacity = useTransform(x, [30, SWIPE_DISTANCE], [0, 1]);
  const skipOpacity = useTransform(x, [-SWIPE_DISTANCE, -30], [1, 0]);

  const onDragEnd = (_: unknown, info: PanInfo) => {
    if (info.offset.x > SWIPE_DISTANCE || info.velocity.x > 600) onDecide('LIKE');
    else if (info.offset.x < -SWIPE_DISTANCE || info.velocity.x < -600) onDecide('SKIP');
  };

  return (
    <motion.article
      ref={ref}
      className="swipe-card"
      style={{ x, rotate }}
      drag="x"
      dragSnapToOrigin
      dragElastic={0.9}
      onDragEnd={onDragEnd}
      custom={direction}
      variants={cardVariants}
      initial="enter"
      animate="center"
      exit="exit"
      data-testid="swipe-card"
    >
      <motion.div className="stamp stamp-like" style={{ opacity: likeOpacity }} aria-hidden>Сходим!</motion.div>
      <motion.div className="stamp stamp-skip" style={{ opacity: skipOpacity }} aria-hidden>Мимо</motion.div>
      <Cover event={event} className="swipe-cover" />
      <div className="swipe-body">
        <EventTags event={event} />
        <h2>{event.title}</h2>
        <EventMeta event={event} />
        <div className="swipe-price">
          <Ticket aria-hidden />
          <b>{price(event)}</b>
          {event.goingCount > 0 && <GoingLine count={event.goingCount} />}
        </div>
        {event.description && <p className="swipe-desc">{event.description}</p>}
        <button type="button" className="link-btn link-strong" onClick={onOpen}>
          Подробнее о событии <ArrowRight aria-hidden />
        </button>
      </div>
    </motion.article>
  );
});

export function Swipe() {
  const { signedIn } = useAuth();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const [filters, setFilters] = useFilters();
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [offset, setOffset] = useState(0);
  const [deck, setDeck] = useState<EventSummary[]>([]);
  const [seen, setSeen] = useState<Set<number>>(() => new Set());
  const [last, setLast] = useState<{ event: EventSummary; kind: 'LIKE' | 'SKIP' } | null>(null);
  const [exitDir, setExitDir] = useState(0);
  const queryKey = JSON.stringify(filters);

  const feed = useQuery({
    queryKey: ['deck', signedIn, queryKey, offset],
    queryFn: () => api.events({
      ...filtersToQuery(filters),
      forYou: signedIn,
      limit: PAGE_SIZE,
      offset: signedIn ? 0 : offset,
    }),
    staleTime: 0,
    gcTime: 0,
  });

  useEffect(() => {
    setDeck([]);
    setSeen(new Set());
    setOffset(0);
    setLast(null);
  }, [queryKey, signedIn]);

  useEffect(() => {
    if (!feed.data) return;
    setDeck((current) => {
      const known = new Set(current.map((e) => e.eventId));
      const fresh = feed.data.filter((e) => !known.has(e.eventId) && !seen.has(e.eventId));
      return fresh.length ? [...current, ...fresh] : current;
    });
    // seen читается намеренно на момент прихода страницы.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feed.data]);

  const current = deck[0];
  const upNext = deck.slice(1, 4);

  const decide = useCallback((kind: 'LIKE' | 'SKIP') => {
    if (!current) return;
    if (!signedIn) {
      navigate('/login?next=/');
      return;
    }
    setExitDir(kind === 'LIKE' ? 1 : -1);
    setSeen((s) => new Set(s).add(current.eventId));
    setDeck((d) => d.slice(1));
    setLast({ event: current, kind });
    api.swipe(current.eventId, kind)
      .then(() => {
        if (kind === 'LIKE') {
          client.invalidateQueries({ queryKey: ['lists'] });
          toast('Добавлено в список');
        }
      })
      .catch((e: Error) => toast(e.message, 'error'));
    // Запасные карточки подгружаются заранее, чтобы лента не обрывалась.
    if (deck.length <= 4) setOffset((o) => o + PAGE_SIZE);
  }, [client, current, deck.length, navigate, signedIn, toast]);

  const undo = () => {
    if (!last) return;
    const { event } = last;
    setLast(null);
    setSeen((s) => {
      const next = new Set(s);
      next.delete(event.eventId);
      return next;
    });
    setDeck((d) => [event, ...d]);
    api.unreact(event.eventId)
      .then(() => client.invalidateQueries({ queryKey: ['lists'] }))
      .catch((e: Error) => toast(e.message, 'error'));
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (filtersOpen || target.closest('input, textarea, select, button, a')) return;
      if (e.key === 'ArrowRight') decide('LIKE');
      if (e.key === 'ArrowLeft') decide('SKIP');
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [decide, filtersOpen]);

  const count = activeFilterCount(filters);
  const loading = feed.isLoading && deck.length === 0;
  const empty = !loading && !feed.isError && deck.length === 0 && !feed.isFetching;

  return (
    <div className="page page-swipe">
      <PageHeader
        title={<span className="logo logo-inline">сходим<span>!</span></span>}
        sub="Санкт-Петербург"
        actions={(
          <>
            <Link to="/search" className="icon-btn" aria-label="Поиск"><SearchIcon aria-hidden /></Link>
            <button type="button" className="icon-btn" aria-label={`Фильтры${count ? `, активно: ${count}` : ''}`} onClick={() => setFiltersOpen(true)}>
              <SlidersHorizontal aria-hidden />
              {count > 0 && <span className="dot-count">{count}</span>}
            </button>
          </>
        )}
      />

      {count > 0 && (
        <div className="chips chips-row">
          {filters.when !== 'any' && <Chip tone="violet">{whenTitle(filters)}</Chip>}
          {filters.categoryIds.length > 0 && <Chip tone="violet">Категорий: {filters.categoryIds.length}</Chip>}
          {filters.isFree && <Chip tone="violet">Бесплатно</Chip>}
          {filters.metro && <Chip tone="violet">м. {filters.metro}</Chip>}
          <button type="button" className="link-btn" onClick={() => setFilters(NO_FILTERS)}>Сбросить</button>
        </div>
      )}

      <div className="swipe-layout">
        <div className="deck-column">
          {loading && <Skeleton rows={1} />}
          {feed.isError && deck.length === 0 && <ErrorBlock error={feed.error} retry={() => feed.refetch()} />}
          {empty && (
            count > 0 ? (
              <EmptyState
                icon={<SearchX />}
                title="Пока ничего не нашлось"
                text="По этим условиям событий нет. Попробуйте другие даты или уберите часть фильтров."
                action={(
                  <>
                    <Button variant="primary" block onClick={() => setFiltersOpen(true)}>Изменить фильтры</Button>
                    <Button block onClick={() => setFilters(NO_FILTERS)}>Сбросить фильтры</Button>
                  </>
                )}
              />
            ) : (
              <EmptyState
                icon={<Heart />}
                title={offset > 0 || seen.size > 0 ? 'Вы посмотрели всё' : 'Афиша пока пуста'}
                text={offset > 0 || seen.size > 0
                  ? 'Новые события появятся после следующей загрузки афиши.'
                  : 'События появятся здесь после загрузки реальной афиши модератором.'}
                action={signedIn ? <Link className="btn btn-primary" to="/list">Открыть мой список</Link> : undefined}
              />
            )
          )}

          {current && (
            <>
              <div className="deck" data-current={current.title}>
                {deck[1] && <div className="swipe-card swipe-card-under" aria-hidden />}
                <AnimatePresence custom={exitDir} initial={false} mode="popLayout">
                  <SwipeCard
                    key={current.eventId}
                    event={current}
                    direction={exitDir}
                    onDecide={decide}
                    onOpen={() => navigate(`/events/${current.eventId}`)}
                  />
                </AnimatePresence>
              </div>
              <div className="swipe-actions">
                <Button onClick={() => decide('SKIP')}><X aria-hidden /> Мимо</Button>
                <button
                  type="button"
                  className="icon-btn icon-btn-big"
                  aria-label="Вернуть предыдущую карточку"
                  disabled={!last}
                  onClick={undo}
                >
                  <RotateCcw aria-hidden />
                </button>
                <Button variant="primary" onClick={() => decide('LIKE')}><Heart aria-hidden /> Сходим</Button>
              </div>
              <p className="hint hint-center">
                <span className="only-touch">Смахните влево, если мимо, и вправо, если интересно</span>
                <span className="only-desktop">Стрелки ← и → на клавиатуре тоже работают</span>
              </p>
            </>
          )}
        </div>

        {upNext.length > 0 && (
          <aside className="up-next only-desktop-block" aria-label="Дальше в ленте">
            <h2>Дальше в ленте</h2>
            {upNext.map((e) => (
              <Link key={e.eventId} to={`/events/${e.eventId}`} className="up-next-item">
                <Cover event={e} className="up-next-cover" />
                <span>{e.title}</span>
              </Link>
            ))}
          </aside>
        )}
      </div>

      <FilterSheet open={filtersOpen} onClose={() => setFiltersOpen(false)} value={filters} onApply={setFilters} />
    </div>
  );
}
