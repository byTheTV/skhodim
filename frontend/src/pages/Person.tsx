import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Heart, MessageCircle, Sparkles } from 'lucide-react';
import { api } from '../api';
import { PageHeader } from '../layout';
import { Avatar, Button, Chip, ErrorBlock, EventRow, ReportButton, Skeleton, useToast } from '../ui';
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
      <section className="person-head">
        <Avatar name={profile.displayName} avatarKey={profile.avatarKey} size={84} />
        <div>
          <h2>{profile.displayName}</h2>
          <p className="muted">@{profile.nickname}{profile.metro ? ` · м. ${profile.metro}` : ''}</p>
        </div>
      </section>
      {profile.bio && <p className="bio">{profile.bio}</p>}
      <div className="chips">{profile.interests.map((i) => <Chip key={i}>{i}</Chip>)}</div>

      {profile.similarity != null && (
        <section className="match-card">
          <span className="twin-label"><Sparkles aria-hidden /> Двойник вкуса · {profile.similarity}%</span>
          {sharedLikes.length > 0 ? (
            <>
              <p>Общие лайки:</p>
              <ul className="heart-list">
                {sharedLikes.map((e) => (
                  <li key={e.eventId}><Heart aria-hidden /><Link to={`/events/${e.eventId}`}>{e.title}</Link></li>
                ))}
              </ul>
            </>
          ) : <p className="muted">Общих лайков пока нет, совпадают интересы.</p>}
        </section>
      )}

      {recentLikes.length > 0 && (
        <section>
          <h2 className="section-title">Недавние лайки</h2>
          <div className="grid-events">
            {recentLikes.slice(0, 4).map((e) => <EventRow key={e.eventId} event={e} compact />)}
          </div>
        </section>
      )}

      {plan ? (
        <section className="invite-panel">
          <h2 className="section-title">Вы оба хотите пойти</h2>
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
              <p className="hint hint-center">
                <MessageCircle aria-hidden /> Чат откроется, только если {profile.displayName} примет приглашение
              </p>
            </>
          )}
        </section>
      ) : (
        <p className="note">
          Общих планов пока нет. Пригласить можно, когда вы оба отметите «Хочу пойти» на одно событие.
        </p>
      )}

      <ReportButton targetType="user" targetId={id} label="Пожаловаться на профиль" />
    </div>
  );
}
