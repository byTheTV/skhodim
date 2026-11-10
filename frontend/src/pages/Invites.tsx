import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Clock3, LockKeyhole, Mail, MessageCircle, X } from 'lucide-react';
import { api, type RequestView } from '../api';
import { relativeTime, shortDate } from '../format';
import { PageHeader } from '../layout';
import { Avatar, Button, EmptyState, ErrorBlock, Skeleton, Tabs, useToast } from '../ui';
import { EventMini } from './Event';

function useResolve() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ id, accept }: { id: number; accept: boolean }) => api.resolveRequest(id, accept),
    onSuccess: (result, { id, accept }) => {
      client.invalidateQueries({ queryKey: ['requests'] });
      client.invalidateQueries({ queryKey: ['request', id] });
      client.invalidateQueries({ queryKey: ['badges'] });
      client.invalidateQueries({ queryKey: ['chats'] });
      if (accept && !result.conversationId) {
        // resolve_request отменяет заявку, если сеанс или намерение уже неактуальны.
        toast('Это приглашение уже неактуально', 'error');
        return;
      }
      navigate(`/invites/${id}`);
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });
}

function InviteCard({ item }: { item: RequestView }) {
  const resolve = useResolve();
  return (
    <article className="invite-card">
      <Link to={`/invites/${item.requestId}`} className="invite-card-link">
        <b>{item.outgoing ? `Вы пригласили: ${item.peerDisplayName}` : `${item.peerDisplayName} приглашает вас`}</b>
        <span>{item.eventTitle}</span>
        <span className="muted">{shortDate(item.startsAt)}</span>
      </Link>
      {!item.outgoing && item.status === 'PENDING' && (
        <div className="invite-actions">
          <Button variant="primary" block onClick={() => resolve.mutate({ id: item.requestId, accept: true })}>Принять</Button>
          <Button block onClick={() => resolve.mutate({ id: item.requestId, accept: false })}>Отклонить</Button>
        </div>
      )}
      {item.outgoing && item.status === 'PENDING' && <p className="status-line"><Clock3 aria-hidden /> Ждём ответа</p>}
    </article>
  );
}

const STATUS_TITLE: Record<RequestView['status'], string> = {
  PENDING: 'Ждём',
  ACCEPTED: 'Принято',
  REJECTED: 'Отклонено',
  CANCELLED: 'Отменено',
};

export function Invites() {
  const [tab, setTab] = useState<'incoming' | 'outgoing'>('incoming');
  const requests = useQuery({ queryKey: ['requests'], queryFn: api.requests, refetchInterval: 20_000 });
  const incoming = requests.data?.incoming ?? [];
  const outgoing = requests.data?.outgoing ?? [];
  const pendingIn = incoming.filter((r) => r.status === 'PENDING');
  const pendingOut = outgoing.filter((r) => r.status === 'PENDING');
  const shown = tab === 'incoming' ? pendingIn : pendingOut;
  const history = (tab === 'incoming' ? incoming : outgoing).filter((r) => r.status !== 'PENDING');
  return (
    <div className="page">
      <PageHeader title="Приглашения" back section />
      <p className="page-sub">Входящие: примите или отклоните. Исходящие: дождитесь ответа. Отсюда же открывается чат после принятия.</p>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'incoming', label: `Входящие · ${pendingIn.length}` },
          { value: 'outgoing', label: `Исходящие · ${pendingOut.length}` },
        ]}
      />
      {requests.isLoading && <Skeleton />}
      {requests.isError && <ErrorBlock error={requests.error} retry={() => requests.refetch()} />}
      {requests.data && shown.length === 0 && (
        <EmptyState
          icon={<Mail />}
          title={tab === 'incoming' ? 'Новых приглашений нет' : 'Ответов никто не ждёт'}
          text="Отметьте «Хочу пойти» на событии и пригласите кого-нибудь из желающих."
          action={<Link to="/list" className="btn btn-primary btn-block">Открыть мой список</Link>}
        />
      )}
      <div className="invite-grid">{shown.map((r) => <InviteCard key={r.requestId} item={r} />)}</div>
      {history.length > 0 && (
        <section>
          <h2 className="section-title">История</h2>
          <div className="people">
            {history.map((r) => (
              <Link key={r.requestId} to={`/invites/${r.requestId}`} className="person-row">
                <Avatar name={r.peerDisplayName} avatarKey={r.peerAvatarKey} size={38} />
                <div>
                  <b>{r.peerDisplayName}</b>
                  <p className="muted">{r.eventTitle}</p>
                </div>
                <span className={`status-pill status-${r.status.toLowerCase()}`}>{STATUS_TITLE[r.status]}</span>
              </Link>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

export function InviteStatus() {
  const id = Number(useParams().id);
  const client = useQueryClient();
  const toast = useToast();
  const resolve = useResolve();
  const request = useQuery({
    queryKey: ['request', id],
    queryFn: () => api.request(id),
    refetchInterval: (q) => (q.state.data?.status === 'PENDING' ? 5000 : false),
  });
  const cancel = useMutation({
    mutationFn: () => api.cancelRequest(id),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['request', id] });
      client.invalidateQueries({ queryKey: ['requests'] });
      toast('Приглашение отозвано');
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });

  if (request.isLoading) return <div className="page"><PageHeader title="Приглашение" back /><Skeleton rows={3} /></div>;
  if (request.isError || !request.data) {
    return <div className="page"><PageHeader title="Приглашение" back /><ErrorBlock error={request.error} /></div>;
  }

  const r = request.data;
  const name = r.peerDisplayName;
  const pending = r.status === 'PENDING';
  const accepted = r.status === 'ACCEPTED';
  const heading = accepted
    ? (r.outgoing ? `${name} принимает приглашение` : `Вы и ${name} идёте вместе`)
    : r.status === 'REJECTED'
      ? (r.outgoing ? 'Приглашение отклонено' : 'Вы отклонили приглашение')
      : r.status === 'CANCELLED'
        ? 'Приглашение отменено'
        : (r.outgoing ? 'Приглашение отправлено' : `${name} приглашает вас`);
  const text = accepted
    ? 'Теперь можно написать друг другу и договориться о встрече перед сеансом.'
    : r.status === 'REJECTED'
      ? 'Ничего страшного: среди желающих могут найтись другие.'
      : r.status === 'CANCELLED'
        ? 'Оно отозвано или стало неактуальным, например, если сеанс уже прошёл.'
        : (r.outgoing
          ? `Осталось дождаться ответа. Мы сообщим, когда ${name} ответит.`
          : 'Если вы примете приглашение, откроется чат на двоих.');

  return (
    <div className="page page-narrow">
      <PageHeader title="Приглашение" back />
      <div className={`status-hero status-hero-${r.status.toLowerCase()}`}>
        <span className="status-icon">
          {accepted ? <Check aria-hidden /> : pending ? <Clock3 aria-hidden /> : <X aria-hidden />}
        </span>
        <h2>{heading}</h2>
        <p>{text}</p>
      </div>
      <Link to={`/users/${r.peerId}`} className="person-row person-plain">
        <Avatar name={name} avatarKey={r.peerAvatarKey} size={48} />
        <div>
          <b>{name} · @{r.peerNickname}</b>
          <p className="muted">
            {accepted && r.resolvedAt ? `Принято ${relativeTime(r.resolvedAt)}` : `Отправлено ${relativeTime(r.createdAt)}`}
          </p>
        </div>
      </Link>
      <h2 className="section-title">{accepted ? 'Вы идёте вместе на' : 'Пойти вместе на'}</h2>
      <EventMini
        event={{
          eventId: r.eventId,
          title: r.eventTitle,
          coverUrl: r.coverUrl,
          categories: [],
          startsAt: r.startsAt,
          placeName: r.placeName,
        }}
      />

      {pending && r.outgoing && (
        <div className="note note-boxed">
          <b>Ожидает принятия</b>
          <p>Личные сообщения доступны только после взаимного согласия.</p>
        </div>
      )}
      {accepted && (
        <div className="note note-boxed">
          <b>Приглашение принято, чат доступен</b>
          <p>Начните с простого приветствия. Уточните время и удобное место встречи.</p>
        </div>
      )}

      <div className="stack">
        {accepted && r.conversationId && (
          <Link to={`/chats/${r.conversationId}`} className="btn btn-primary btn-block">
            <MessageCircle aria-hidden /> Открыть чат
          </Link>
        )}
        {pending && r.outgoing && (
          <>
            <button type="button" className="btn btn-secondary btn-block" disabled><LockKeyhole aria-hidden /> Чат пока недоступен</button>
            <Button block loading={cancel.isPending} onClick={() => cancel.mutate()}>Отозвать приглашение</Button>
          </>
        )}
        {pending && !r.outgoing && (
          <>
            <Button variant="primary" block loading={resolve.isPending} onClick={() => resolve.mutate({ id, accept: true })}>Принять</Button>
            <Button block onClick={() => resolve.mutate({ id, accept: false })}>Отклонить</Button>
          </>
        )}
        <Link to={accepted ? `/events/${r.eventId}` : `/events/${r.eventId}/going`} className="btn btn-secondary btn-block">
          {accepted ? 'Вернуться к событию' : 'Вернуться к желающим'}
        </Link>
      </div>
    </div>
  );
}
