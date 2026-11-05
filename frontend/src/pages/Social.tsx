import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowRight, CalendarDays, ChevronRight, Users } from 'lucide-react';
import { api } from '../api';
import { plural, relativeTime } from '../format';
import { PageHeader } from '../layout';
import { Avatar, Chip, EmptyState, ErrorBlock, Skeleton } from '../ui';

export function Social() {
  const people = useQuery({ queryKey: ['people'], queryFn: () => api.suggested(1) });
  const taste = useQuery({ queryKey: ['taste'], queryFn: api.taste });
  const feed = useQuery({ queryKey: ['feed'], queryFn: () => api.feed(10) });
  const twin = people.data?.[0];

  const plate = useMemo(() => ({
    cats: (taste.data ?? []).filter((s) => s.kind === 'category').slice(0, 3),
    metros: (taste.data ?? []).filter((s) => s.kind === 'metro').slice(0, 2),
  }), [taste.data]);

  return (
    <div className="page">
      <PageHeader title="Соцсеть" />
      <h2>Подходящие вам</h2>
      {people.isLoading && <Skeleton rows={1} />}
      {people.isError && <ErrorBlock error={people.error} retry={() => people.refetch()} />}
      {twin && (
        <Link to={`/users/${twin.userId}`} className="twin-card">
          <div className="twin-top">
            <Avatar name={twin.displayName} avatarKey={twin.avatarKey} size={42} />
            <div>
              <span className="twin-label">Двойник вкуса · {twin.similarity}%</span>
              <b>{twin.displayName} · @{twin.nickname}</b>
            </div>
            <ChevronRight className="row-arrow" aria-hidden />
          </div>
          <p>
            {twin.sharedCount > 0
              ? `${twin.sharedCount} ${plural(twin.sharedCount, 'общий лайк', 'общих лайка', 'общих лайков')}: ${twin.sharedTitles.slice(0, 2).map((t) => `«${t}»`).join(' и ')}.`
              : `Похожие интересы: ${twin.interests.slice(0, 3).join(', ')}.`}
          </p>
          <span className="twin-cta">Посмотреть профиль →</span>
        </Link>
      )}
      {people.data?.length === 0 && (
        <EmptyState icon={<Users />} title="Пока никого" text="Другие участники появятся, когда начнут отмечать события." />
      )}

      <Link to="/profile/taste" className="plate-card">
        <div className="section-head">
          <h2>Пластинка вкуса</h2>
          <span className="link-btn link-strong">Подробнее <ArrowRight aria-hidden /></span>
        </div>
        {plate.cats.length > 0 ? (
          <>
            <div className="chips">{plate.cats.map((c) => <Chip key={c.itemName}>{c.itemName}</Chip>)}</div>
            {plate.metros.length > 0 && <p className="muted">Районы: {plate.metros.map((m) => m.itemName).join(', ')}</p>}
          </>
        ) : (
          <p className="muted">Соберётся сама, когда вы отметите несколько событий.</p>
        )}
      </Link>

      <h2 className="section-title">Говорят о событиях</h2>
      {feed.isLoading && <Skeleton />}
      {feed.isError && <ErrorBlock error={feed.error} retry={() => feed.refetch()} />}
      {feed.data?.length === 0 && <p className="hint">Пока никто ничего не обсуждает.</p>}
      <div>
        {feed.data?.map((c) => (
          <article key={c.id} className="feed-item">
            <Link to={`/users/${c.authorId}`} className="feed-author">
              <Avatar name={c.displayName} avatarKey={c.avatarKey} size={28} />
              <b>{c.displayName}</b>
              <span className="muted">{relativeTime(c.createdAt)}</span>
            </Link>
            <p>{c.body}</p>
            <Link to={`/events/${c.eventId}`} className="feed-event">
              <CalendarDays aria-hidden /> {c.eventTitle} →
            </Link>
          </article>
        ))}
      </div>
    </div>
  );
}
