import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { AnimatePresence, motion, useMotionValue, useTransform, type PanInfo } from 'framer-motion';
import { ArrowRight, Heart, MapPin, Search as SearchIcon, SearchX, SlidersHorizontal, X } from 'lucide-react';
import { api, type EventSummary } from '../api';
import { useAuth } from '../auth';
import { activeFilterCount, FilterSheet, filtersToQuery, NO_FILTERS, useFilters, whenTitle } from '../filters';
import { eventDate, place, price } from '../format';
import { PageHeader } from '../layout';
import { Button, Chip, Cover, coverStyle, EmptyState, ErrorBlock, EventTags, Skeleton, useConfetti, useToast } from '../ui';

const SWIPE_DISTANCE = 110;
const PAGE_SIZE = 20;
/** Сколько карточек лежит в стопке: верхняя, видимая под ней и ещё одна, у которой постер уже готов. */
const STACK_SIZE = 3;
/** Постеры следующих за стопкой карточек загружаются заранее. */
const PRELOAD_SIZE = 5;

const SPRING = { type: 'spring' as const, stiffness: 380, damping: 26 };

/**
 * Карточка живёт в стопке одним и тем же элементом и только меняет позу, поэтому постер при смене карточки
 * не создаётся заново и не мелькает. Улетающая идёт поверх всех, а новая уже лежит под ней непрозрачной.
 */
const cardVariants = {
  under: { x: 0, opacity: 1, scale: 0.96, y: 8, rotateZ: 3, transition: SPRING },
  center: { x: 0, opacity: 1, scale: 1, y: 0, rotateZ: 0, transition: SPRING },
  returning: (direction: number) => ({ x: direction * 760, opacity: 0 }),
  exit: (direction: number) => ({
    x: direction * 760,
    rotate: direction * 26,
    opacity: 0,
    zIndex: STACK_SIZE + 1,
    transition: { duration: 0.32, zIndex: { duration: 0 }, opacity: { delay: 0.14, duration: 0.18 } },
  }),
  gone: { opacity: 0, transition: { duration: 0 } },
};

function SwipeCard({ event, depth, direction, onDecide, onOpen }: {
  event: EventSummary;
  depth: number;
  direction: number;
  onDecide: (kind: 'LIKE' | 'SKIP') => void;
  onOpen: () => void;
}) {
  const active = depth === 0;
  const x = useMotionValue(0);
  const rotate = useTransform(x, [-300, 300], [-16, 16]);
  const likeOpacity = useTransform(x, [30, SWIPE_DISTANCE], [0, 1]);
  const skipOpacity = useTransform(x, [-SWIPE_DISTANCE, -30], [1, 0]);
  const style = coverStyle(event.categories);
  const hasPhoto = Boolean(event.coverUrl);

  const onDragEnd = (_: unknown, info: PanInfo) => {
    if (info.offset.x > SWIPE_DISTANCE || info.velocity.x > 600) onDecide('LIKE');
    else if (info.offset.x < -SWIPE_DISTANCE || info.velocity.x < -600) onDecide('SKIP');
  };

  return (
    <motion.article
      className={`swipe-card ${active ? 'is-active' : ''} ${hasPhoto ? 'swipe-card-photo' : ''}`}
      aria-hidden={active ? undefined : true}
      style={{
        x,
        rotate,
        zIndex: STACK_SIZE - depth,
        '--card-bg': hasPhoto ? '#14062E' : style.bg,
        '--card-fg': hasPhoto ? '#FFFFFF' : style.fg,
      } as unknown as CSSProperties}
      drag={active ? 'x' : false}
      dragSnapToOrigin
      dragElastic={0.9}
      onDragEnd={onDragEnd}
      custom={direction}
      variants={cardVariants}
      initial={active ? 'returning' : 'under'}
      animate={active ? 'center' : 'under'}
      exit={active ? 'exit' : 'gone'}
      data-testid={active ? 'swipe-card' : undefined}
    >
      <Cover event={event} className="swipe-cover" priority={active ? 'high' : 'low'} />
      {active && (
        <div className="swipe-face">
          <div className="swipe-scrim" aria-hidden />
          <motion.div className="stamp stamp-like" style={{ opacity: likeOpacity }} aria-hidden>ЛАЙК!</motion.div>
          <motion.div className="stamp stamp-skip" style={{ opacity: skipOpacity }} aria-hidden>МИМО</motion.div>
          <div className="price-sticker">{price(event)}</div>
          <div className="swipe-body">
            <EventTags event={event} />
            <h2>{event.title}</h2>
            <p className="line-strong">{eventDate(event.startsAt, event.endsAt)}</p>
            <p className="line">{place(event)}</p>
            {event.description && event.description !== event.title && <p className="swipe-desc">{event.description}</p>}
            <button type="button" className="link-btn" onClick={onOpen}>
              Подробнее о событии <ArrowRight aria-hidden />
            </button>
          </div>
        </div>
      )}
    </motion.article>
  );
}

export function Swipe() {
  const { signedIn } = useAuth();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const confetti = useConfetti();
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
  const upNext = deck.slice(1, 7);

  // Запас качается по одному и с низким приоритетом: постер на экране не должен делить канал со всей очередью.
  const preloaded = useRef(new Set<string>());
  const preloading = useRef(false);
  const queue = useRef<string[]>([]);
  const preloadNext = useCallback(() => {
    if (preloading.current) return;
    const url = queue.current.find((u) => !preloaded.current.has(u));
    if (!url) return;
    preloaded.current.add(url);
    preloading.current = true;
    const image = new Image();
    image.referrerPolicy = 'no-referrer';
    image.setAttribute('fetchpriority', 'low');
    image.onload = image.onerror = () => {
      preloading.current = false;
      preloadNext();
    };
    image.src = url;
  }, []);
  useEffect(() => {
    queue.current = deck.slice(STACK_SIZE, STACK_SIZE + PRELOAD_SIZE).flatMap((e) => e.coverUrl ?? []);
    preloadNext();
  }, [deck, preloadNext]);

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
    if (kind === 'LIKE') confetti();
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
  }, [client, confetti, current, deck.length, navigate, signedIn, toast]);

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

  const actions = (
    <>
      <Link to="/search" className="icon-btn" aria-label="Поиск"><SearchIcon aria-hidden /></Link>
      <button type="button" className="icon-btn" aria-label={`Фильтры${count ? `, активно: ${count}` : ''}`} onClick={() => setFiltersOpen(true)}>
        <SlidersHorizontal aria-hidden />
        {count > 0 && <span className="dot-count">{count}</span>}
      </button>
    </>
  );
  const cityRow = (
    <div className="city-row">
      <MapPin aria-hidden />
      <span>Санкт-Петербург</span>
      <Link to="/list" className="to-list">
        <Heart aria-hidden />
        <span className="chip">В Список</span>
      </Link>
    </div>
  );
  const filterChips = count > 0 && (
    <div className="chips">
      {filters.when !== 'any' && <Chip>{whenTitle(filters)}</Chip>}
      {filters.categoryIds.length > 0 && <Chip>Категорий: {filters.categoryIds.length}</Chip>}
      {filters.isFree && <Chip>Бесплатно</Chip>}
      {filters.metro && <Chip>м. {filters.metro}</Chip>}
    </div>
  );

  return (
    <div className="page page-swipe">
      <div className="swipe-center">
        <div className="swipe-intro-mobile">
          <PageHeader title={<span className="logo">сходим<span>!</span></span>} actions={actions} />
          {cityRow}
          {filterChips}
        </div>

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
                title={offset > 0 || seen.size > 0 ? 'Вы посмотрели всё!' : 'Афиша пока пуста'}
                text={offset > 0 || seen.size > 0
                  ? 'Новые события появятся после следующего обновления афиши.'
                  : 'События появятся здесь после загрузки реальной афиши.'}
                action={signedIn ? <Link className="btn btn-primary btn-block" to="/list">Открыть мой список</Link> : undefined}
              />
            )
          )}

          {current && (
            <>
              <div className="deck" data-current={current.title}>
                <AnimatePresence custom={exitDir} initial={false}>
                  {deck.slice(0, STACK_SIZE).map((event, depth) => (
                    <SwipeCard
                      key={event.eventId}
                      event={event}
                      depth={depth}
                      direction={exitDir}
                      onDecide={decide}
                      onOpen={() => navigate(`/events/${event.eventId}`)}
                    />
                  ))}
                </AnimatePresence>
              </div>
              <div className="swipe-actions">
                <Button onClick={() => decide('SKIP')}><X aria-hidden /> Пропуск</Button>
                <Button variant="primary" onClick={() => decide('LIKE')}><Heart aria-hidden /> Лайк</Button>
              </div>
              <div className="swipe-foot">
                <p className="hint hint-center only-touch">Смахните влево — пропуск, вправо — лайк</p>
                {last && (
                  <button type="button" className="link-btn link-strong" aria-label="Вернуть предыдущую карточку" onClick={undo}>
                    Вернуть
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      <aside className="swipe-side">
        <h1>Куда <em>сходим</em> сегодня?</h1>
        <div className="swipe-tools">
          <span className="city"><MapPin aria-hidden /> Санкт-Петербург</span>
          <Button onClick={() => setFiltersOpen(true)} aria-label={`Фильтры${count ? `, активно: ${count}` : ''}`}>
            <SlidersHorizontal aria-hidden /> Фильтры{count > 0 && <span className="dot-count">{count}</span>}
          </Button>
        </div>
        {filterChips}
        {upNext.length > 0 && <h2 className="section-title">Дальше в ленте</h2>}
        <div className="next-grid">
          {upNext.map((e) => (
            <Link key={e.eventId} to={`/events/${e.eventId}`} className="next-item">
              <Cover event={e} className="next-cover" />
              <span className="next-title">{e.title}</span>
              <span className="next-meta">{eventDate(e.startsAt, e.endsAt)}</span>
            </Link>
          ))}
        </div>
        <p className="hint keys"><kbd>←</kbd> пропуск <kbd>→</kbd> лайк, или тащите карточку мышкой</p>
      </aside>

      <FilterSheet open={filtersOpen} onClose={() => setFiltersOpen(false)} value={filters} onApply={setFilters} />
    </div>
  );
}
