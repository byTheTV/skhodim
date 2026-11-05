import { forwardRef, useCallback, useEffect, useState, type CSSProperties } from 'react';
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

/** Карточка улетает в ту сторону, куда её смахнули, с разворотом. */
const cardVariants = {
  enter: { opacity: 0, scale: 0.9, y: 16 },
  center: { opacity: 1, scale: 1, y: 0, transition: { type: 'spring' as const, stiffness: 380, damping: 26 } },
  exit: (direction: number) => ({
    x: direction * 760,
    rotate: direction * 26,
    opacity: 0,
    transition: { duration: 0.32 },
  }),
};

const SwipeCard = forwardRef<HTMLElement, {
  event: EventSummary;
  direction: number;
  onDecide: (kind: 'LIKE' | 'SKIP') => void;
  onOpen: () => void;
}>(function SwipeCard({ event, direction, onDecide, onOpen }, ref) {
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
      ref={ref}
      className={`swipe-card ${hasPhoto ? 'swipe-card-photo' : ''}`}
      style={{
        x,
        rotate,
        '--card-bg': hasPhoto ? '#14062E' : style.bg,
        '--card-fg': hasPhoto ? '#FFFFFF' : style.fg,
      } as unknown as CSSProperties}
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
      <Cover event={event} className="swipe-cover" />
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
    </motion.article>
  );
});

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
  const under = deck[1];
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
      <aside className="swipe-headline">
        <h1>Куда <em>сходим</em> сегодня?</h1>
        {cityRow}
        <div className="chips">{actions}</div>
        {filterChips}
        <p className="hint">Стрелка влево — пропуск, вправо — лайк. Или тащите карточку мышкой.</p>
      </aside>

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
                {under && (
                  <div className="swipe-under" aria-hidden>
                    <Cover event={under} />
                  </div>
                )}
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

      <aside className="swipe-next" aria-label="Дальше в ленте">
        {upNext.length > 0 && <h2>Дальше в ленте</h2>}
        {upNext.map((e) => (
          <Link key={e.eventId} to={`/events/${e.eventId}`} className="next-item">
            <Cover event={e} className="next-cover" />
            <span>{e.title}</span>
          </Link>
        ))}
      </aside>

      <FilterSheet open={filtersOpen} onClose={() => setFiltersOpen(false)} value={filters} onApply={setFilters} />
    </div>
  );
}
