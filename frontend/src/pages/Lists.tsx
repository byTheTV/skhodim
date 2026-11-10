import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarCheck, Check, ChevronRight, Heart, Share2 } from 'lucide-react';
import { api, type EventSummary } from '../api';
import { plural } from '../format';
import { PageHeader } from '../layout';
import { EmptyState, ErrorBlock, EventRow, Skeleton, Tabs, useToast } from '../ui';
import { ShareSheet } from './Event';

type Tab = 'going' | 'liked' | 'visited';

export function Lists() {
  const [tab, setTab] = useState<Tab>('going');
  const [sharing, setSharing] = useState<EventSummary | null>(null);
  const client = useQueryClient();
  const toast = useToast();
  const lists = useQuery({ queryKey: ['lists'], queryFn: api.lists });
  const remove = useMutation({
    mutationFn: (id: number) => api.unreact(id),
    onSuccess: () => client.invalidateQueries({ queryKey: ['lists'] }),
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const data = lists.data;
  const items: { value: Tab; label: string }[] = [
    { value: 'going', label: `Хочу пойти · ${data?.going.length ?? 0}` },
    { value: 'liked', label: `Понравилось · ${data?.liked.length ?? 0}` },
  ];
  if (data?.visited.length) items.push({ value: 'visited', label: `Прошли · ${data.visited.length}` });

  return (
    <div className="page">
      <PageHeader title="Список" />
      <Tabs value={tab} onChange={setTab} items={items} />
      {lists.isLoading && <Skeleton />}
      {lists.isError && <ErrorBlock error={lists.error} retry={() => lists.refetch()} />}

      {data && tab === 'going' && (
        data.going.length === 0 ? (
          <EmptyState
            icon={<CalendarCheck />}
            title="Планов пока нет"
            text="Откройте событие и включите «Хочу пойти»: так вы увидите, кто ещё собирается."
            action={<Link to="/" className="btn btn-primary btn-block">К свайпу</Link>}
          />
        ) : (
          <>
            <div>
              <h2 className="section-title">Активные планы</h2>
              <p className="hint">События с включённым «Хочу пойти». Здесь доступны желающие, приглашения и чат по этим планам.</p>
            </div>
            <div className="grid-events">
              {data.going.map((entry) => {
                const others = Math.max(0, entry.event.goingCount - 1);
                return (
                  <EventRow
                    key={entry.event.eventId}
                    event={entry.event}
                    footer={(
                      <>
                        <Link to={`/events/${entry.event.eventId}/going`} className="footer-link">
                          <Check aria-hidden />
                          <span>
                            {others > 0
                              ? `Вы идёте · ещё ${others} ${plural(others, 'человек', 'человека', 'человек')}`
                              : 'Вы идёте · других желающих пока нет'}
                          </span>
                          <ChevronRight className="footer-chevron" aria-hidden />
                        </Link>
                        <button
                          type="button"
                          className="footer-icon"
                          aria-label={`Карточка для сторис: ${entry.event.title}`}
                          onClick={() => setSharing(entry.event)}
                        >
                          <Share2 aria-hidden />
                        </button>
                      </>
                    )}
                  />
                );
              })}
            </div>
            <div className="note">
              <b>Как переключать</b>
              <p>Вкладка «Хочу пойти» открывается первой. «Понравилось» содержит только лайки со свайпа, без планов похода.</p>
            </div>
          </>
        )
      )}

      {data && tab === 'liked' && (
        data.liked.length === 0 ? (
          <EmptyState
            icon={<Heart />}
            title="Лайков пока нет"
            text="Смахивайте события вправо, и они появятся здесь."
            action={<Link to="/" className="btn btn-primary btn-block">К свайпу</Link>}
          />
        ) : (
          <>
            <div>
              <h2 className="section-title">Только лайки</h2>
              <p className="hint">Лайкнули на свайпе, но «Хочу пойти» ещё не нажали. Включите в карточке события, и оно перейдёт в первый раздел.</p>
            </div>
            <div className="grid-events">
              {data.liked.map((entry) => (
                <EventRow
                  key={entry.event.eventId}
                  event={entry.event}
                  footer={(
                    <button
                      type="button"
                      className="link-btn"
                      aria-label={`Убрать «${entry.event.title}» из понравившегося`}
                      onClick={() => remove.mutate(entry.event.eventId)}
                    >
                      <Heart aria-hidden /> Убрать
                    </button>
                  )}
                />
              ))}
            </div>
            <div className="note">
              <b>Лайк и «Хочу пойти»</b>
              <p>Обе категории находятся в разделе «Список». Лайк значит интерес, «Хочу пойти» значит готовность идти и искать компанию.</p>
            </div>
          </>
        )
      )}

      {data && tab === 'visited' && (
        <>
          <div>
            <h2 className="section-title">Уже прошли</h2>
            <p className="hint">Откройте событие, чтобы оставить отзыв.</p>
          </div>
          <div className="grid-events">
            {data.visited.map((entry) => <EventRow key={entry.event.eventId} event={entry.event} />)}
          </div>
        </>
      )}

      {sharing && (
        <ShareSheet eventId={sharing.eventId} title={sharing.title} open onClose={() => setSharing(null)} />
      )}
    </div>
  );
}
