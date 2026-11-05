import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowRight, Calendar, Sparkles, Users } from 'lucide-react';
import { api } from '../api';
import { relativeTime } from '../format';
import { PageHeader } from '../layout';
import { Avatar, Chip, EmptyState, ErrorBlock, Skeleton } from '../ui';

export function Social() {
  const people = useQuery({ queryKey: ['people'], queryFn: () => api.suggested(6) });
  const taste = useQuery({ queryKey: ['taste'], queryFn: api.taste });
  const feed = useQuery({ queryKey: ['feed'], queryFn: () => api.feed(10) });
  const [twin, ...rest] = people.data ?? [];

  const plate = useMemo(() => {
    const cats = (taste.data ?? []).filter((s) => s.kind === 'category').slice(0, 3);
    const metros = (taste.data ?? []).filter((s) => s.kind === 'metro').slice(0, 2);
    return { cats, metros };
  }, [taste.data]);

  return (
    <div className="page">
      <PageHeader title="Соцсеть" />
      <div className="social-layout">
        <div>
          <h2 className="section-title">Подходящие вам</h2>
          {people.isLoading && <Skeleton rows={1} />}
          {people.isError && <ErrorBlock error={people.error} retry={() => people.refetch()} />}
          {twin && (
            <Link to={`/users/${twin.userId}`} className="twin-card">
              <div className="twin-top">
                <Avatar name={twin.displayName} avatarKey={twin.avatarKey} size={56} />
                <div>
                  <span className="twin-label"><Sparkles aria-hidden /> Двойник вкуса · {twin.similarity}%</span>
                  <b>{twin.displayName} · @{twin.nickname}</b>
                </div>
                <ArrowRight className="row-arrow" aria-hidden />
              </div>
              <p>
                {twin.sharedCount > 0
                  ? `Общих событий: ${twin.sharedCount}. ${twin.sharedTitles.slice(0, 2).map((t) => `«${t}»`).join(' и ')}.`
                  : `Похожие интересы: ${twin.interests.slice(0, 3).join(', ')}. Лайкайте события, чтобы найти общие.`}
              </p>
            </Link>
          )}
          {rest.length > 0 && (
            <div className="people">
              {rest.map((p) => (
                <Link key={p.userId} to={`/users/${p.userId}`} className="person-row">
                  <Avatar name={p.displayName} avatarKey={p.avatarKey} size={44} />
                  <div>
                    <b>{p.displayName} · @{p.nickname}</b>
                    <p className="muted">{p.interests.slice(0, 3).join(' · ')}</p>
                  </div>
                  <span className="match">{p.similarity}%</span>
                </Link>
              ))}
            </div>
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
                {plate.metros.length > 0 && (
                  <p className="muted">Чаще всего: {plate.metros.map((m) => m.itemName).join(', ')}</p>
                )}
              </>
            ) : (
              <p className="muted">Соберётся сама, когда вы отметите несколько событий.</p>
            )}
          </Link>
        </div>

        <div>
          <h2 className="section-title">Говорят о событиях</h2>
          {feed.isLoading && <Skeleton />}
          {feed.isError && <ErrorBlock error={feed.error} retry={() => feed.refetch()} />}
          {feed.data?.length === 0 && <p className="hint">Пока никто ничего не обсуждает.</p>}
          {feed.data?.map((c) => (
            <article key={c.id} className="comment">
              <Link to={`/users/${c.authorId}`} aria-label={`Профиль: ${c.displayName}`}>
                <Avatar name={c.displayName} avatarKey={c.avatarKey} size={40} />
              </Link>
              <div className="comment-body">
                <div className="comment-head">
                  <b>{c.displayName}</b>
                  <span className="muted">{relativeTime(c.createdAt)}</span>
                </div>
                <p>{c.body}</p>
                <Link to={`/events/${c.eventId}`} className="comment-event">
                  <Calendar aria-hidden /> {c.eventTitle} <ArrowRight aria-hidden />
                </Link>
              </div>
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}
