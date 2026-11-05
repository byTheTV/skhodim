import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarCheck, Check, Heart, Trash2, Users } from 'lucide-react';
import { api } from '../api';
import { plural } from '../format';
import { PageHeader } from '../layout';
import { EmptyState, ErrorBlock, EventRow, Skeleton, Tabs, useToast } from '../ui';

type Tab = 'going' | 'liked' | 'visited';

export function Lists() {
  const [tab, setTab] = useState<Tab>('going');
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
            action={<Link to="/" className="btn btn-primary">К свайпу</Link>}
          />
        ) : (
          <>
            <h2 className="section-title">Активные планы</h2>
            <p className="hint">Здесь доступны желающие, приглашения и чаты по этим событиям.</p>
            <div className="grid-events">
              {data.going.map((entry) => {
                const others = Math.max(0, entry.event.goingCount - 1);
                return (
                  <EventRow
                    key={entry.event.eventId}
                    event={entry.event}
                    footer={(
                      <Link to={`/events/${entry.event.eventId}/going`} className="footer-link">
                        <Check aria-hidden />
                        {others > 0
                          ? `Вы идёте, ещё ${others} ${plural(others, 'человек', 'человека', 'человек')}`
                          : 'Вы идёте, других желающих пока нет'}
                        <Users aria-hidden />
                      </Link>
                    )}
                  />
                );
              })}
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
            action={<Link to="/" className="btn btn-primary">К свайпу</Link>}
          />
        ) : (
          <>
            <h2 className="section-title">Только лайки</h2>
            <p className="hint">Понравилось на свайпе, но «Хочу пойти» ещё не нажато.</p>
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
                      <Trash2 aria-hidden /> Убрать
                    </button>
                  )}
                />
              ))}
            </div>
          </>
        )
      )}

      {data && tab === 'visited' && (
        <>
          <h2 className="section-title">Уже прошли</h2>
          <p className="hint">Откройте событие, чтобы оставить отзыв.</p>
          <div className="grid-events">
            {data.visited.map((entry) => <EventRow key={entry.event.eventId} event={entry.event} />)}
          </div>
        </>
      )}

      <div className="note note-explain">
        <b>Лайк и «Хочу пойти»</b>
        <p>Лайк значит интерес. «Хочу пойти» значит готовность идти и искать компанию.</p>
      </div>
    </div>
  );
}
