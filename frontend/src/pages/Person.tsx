import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Heart } from 'lucide-react';
import { api } from '../api';
import { plural, shortDate } from '../format';
import { PageHeader } from '../layout';
import { Avatar, Button, Chip, ErrorBlock, ReportButton, Skeleton, useToast } from '../ui';
import { EventMini } from './Event';

export function Person() {
  const id = Number(useParams().id);
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const page = useQuery({ queryKey: ['user', id], queryFn: () => api.user(id) });
  const requests = useQuery({ queryKey: ['requests'], queryFn: api.requests });

  const invite = useMutation({
    mutationFn: (sessionId: number) => api.sendRequest(id, sessionId),
    onSuccess: ({ requestId }) => {
      client.invalidateQueries({ queryKey: ['requests'] });
      navigate(`/invites/${requestId}`);
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });

  if (page.isLoading) return <div className="page"><PageHeader title="Профиль участника" back /><Skeleton rows={4} /></div>;
  if (page.isError || !page.data) {
    return <div className="page"><PageHeader title="Профиль участника" back /><ErrorBlock error={page.error} /></div>;
  }

  const { profile, recentLikes, sharedLikes, sharedPlans } = page.data;
  // Если пришли со списка желающих, это событие показывается первым.
  const preferredSession = Number(params.get('session'));
  const plans = [...sharedPlans].sort((a, b) =>
    Number(b.sessionId === preferredSession) - Number(a.sessionId === preferredSession));
  const plan = plans[0];
  const all = requests.data ? [...requests.data.incoming, ...requests.data.outgoing] : [];
  const existing = plan ? all.find((r) => r.peerId === id && r.sessionId === plan.sessionId) : undefined;

  return (
    <div className="page">
      <PageHeader title="Профиль участника" back />
      <div className="person-grid">
      <div className="grid-col">
      <section className="person-head">
        <Avatar name={profile.displayName} avatarKey={profile.avatarKey} size={72} />
        <div>
          <h2>{profile.displayName}</h2>
          <p className="muted">@{profile.nickname} · {profile.metro ? `м. ${profile.metro}` : 'Санкт-Петербург'}</p>
        </div>
      </section>
      {profile.bio && <p className="bio">{profile.bio}</p>}
      <div className="chips">{profile.interests.map((i) => <Chip key={i}>{i}</Chip>)}</div>

      {profile.similarity != null && (
        <section className="block panel-box">
          <h2>Двойник вкуса · {profile.similarity}%</h2>
          {sharedLikes.length > 0 ? (
            <>
              <p className="line">{sharedLikes.length} {plural(sharedLikes.length, 'общий лайк', 'общих лайка', 'общих лайков')} с вами</p>
              <ul className="heart-list">
                {sharedLikes.map((e) => (
                  <li key={e.eventId}><Heart aria-hidden /><Link to={`/events/${e.eventId}`}>{e.title}</Link></li>
                ))}
              </ul>
            </>
          ) : <p className="line muted">Общих лайков пока нет, совпадают интересы.</p>}
        </section>
      )}

      {recentLikes.length > 0 && (
        <section className="block panel-box">
          <h2>Недавние лайки</h2>
          <ul className="heart-list">
            {recentLikes.slice(0, 3).map((e) => (
              <li key={e.eventId}>
                <Link to={`/events/${e.eventId}`}>«{e.title}» · {shortDate(e.startsAt)} →</Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      </div>
      <div className="grid-col">
      {plan ? (
        <section className="invite-panel">
          <h2>Вы оба хотите пойти</h2>
          <EventMini event={plan.event} />
          {existing ? (
            <Link to={`/invites/${existing.requestId}`} className="btn btn-secondary btn-block">
              Посмотреть приглашение <ArrowRight aria-hidden />
            </Link>
          ) : (
            <>
              <Button variant="primary" block loading={invite.isPending} onClick={() => invite.mutate(plan.sessionId)}>
                Предложить пойти вместе
              </Button>
              <p className="hint hint-center">Чат откроется, только если {profile.displayName} примет приглашение</p>
            </>
          )}
        </section>
      ) : (
        <p className="note">Общих планов пока нет. Пригласить можно, когда вы оба отметите «Хочу пойти» на одно событие.</p>
      )}

      <ReportButton targetType="user" targetId={id} label="Пожаловаться на профиль" />
      </div>
      </div>
    </div>
  );
}
