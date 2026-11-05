import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MessageCircle, Send } from 'lucide-react';
import { api } from '../api';
import { useAuth } from '../auth';
import { clock, relativeTime } from '../format';
import { PageHeader } from '../layout';
import { Avatar, Badge, EmptyState, ErrorBlock, ReportButton, Skeleton, useToast } from '../ui';
import { EventMini } from './Event';

function ChatList({ activeId }: { activeId?: number }) {
  const { me } = useAuth();
  const chats = useQuery({ queryKey: ['chats'], queryFn: api.chats, refetchInterval: 8000 });
  const requests = useQuery({ queryKey: ['requests'], queryFn: api.requests });
  const waiting = requests.data?.outgoing.filter((r) => r.status === 'PENDING') ?? [];

  return (
    <div className="chat-list">
      <PageHeader title="Чаты" back="/profile" sub="Переписки после принятых приглашений" />
      {chats.isLoading && <Skeleton />}
      {chats.isError && <ErrorBlock error={chats.error} retry={() => chats.refetch()} />}
      {chats.data?.length === 0 && (
        <EmptyState
          icon={<MessageCircle />}
          title="Чатов пока нет"
          text="Чат откроется, когда кто-то примет ваше приглашение пойти вместе."
          action={<Link to="/invites" className="btn btn-primary">К приглашениям</Link>}
        />
      )}
      {chats.data?.map((c) => (
        <Link
          key={c.conversationId}
          to={`/chats/${c.conversationId}`}
          className={`chat-row ${activeId === c.conversationId ? 'chat-row-active' : ''}`}
        >
          <Avatar name={c.peerDisplayName} avatarKey={c.peerAvatarKey} size={48} />
          <div className="chat-row-body">
            <div className="comment-head">
              <b>{c.peerDisplayName}</b>
              <span className="muted">{relativeTime(c.lastAt)}</span>
            </div>
            <p className="muted small">{c.eventTitle}</p>
            <p className="chat-preview">
              {c.lastBody
                ? `${c.lastAuthorId === me?.userId ? 'Вы: ' : ''}${c.lastBody}`
                : 'Приглашение принято. Напишите первым.'}
            </p>
          </div>
          <Badge count={c.unreadCount} />
        </Link>
      ))}
      {waiting.length > 0 && (
        <div className="note">
          <b>Ожидают ответа</b>
          {waiting.map((w) => (
            <p key={w.requestId}>
              <Link to={`/invites/${w.requestId}`}>{w.peerDisplayName}</Link>: приглашение отправлено, ответа пока нет.
            </p>
          ))}
        </div>
      )}
    </div>
  );
}

function Conversation({ id }: { id: number }) {
  const { me } = useAuth();
  const client = useQueryClient();
  const toast = useToast();
  const [text, setText] = useState('');
  const bottom = useRef<HTMLDivElement>(null);
  const chat = useQuery({ queryKey: ['chat', id], queryFn: () => api.chat(id) });
  const messages = useQuery({ queryKey: ['messages', id], queryFn: () => api.messages(id), refetchInterval: 4000 });
  const lastId = messages.data?.[messages.data.length - 1]?.id;

  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'end' });
    if (lastId) {
      api.readChat(id)
        .then(() => {
          client.invalidateQueries({ queryKey: ['badges'] });
          client.invalidateQueries({ queryKey: ['chats'] });
        })
        .catch(() => {
          // Отметка прочтения повторится при следующем сообщении.
        });
    }
  }, [lastId, id, client]);

  const send = useMutation({
    mutationFn: (body: string) => api.sendMessage(id, body),
    onSuccess: () => {
      setText('');
      client.invalidateQueries({ queryKey: ['messages', id] });
      client.invalidateQueries({ queryKey: ['chats'] });
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });

  if (chat.isError) return <ErrorBlock error={chat.error} />;
  const c = chat.data;

  return (
    <div className="conversation">
      <PageHeader
        title={c ? c.peerDisplayName : 'Чат'}
        back="/chats"
        actions={c && (
          <Link to={`/users/${c.peerId}`} aria-label={`Профиль: ${c.peerDisplayName}`}>
            <Avatar name={c.peerDisplayName} avatarKey={c.peerAvatarKey} size={40} />
          </Link>
        )}
      />
      {c && (
        <EventMini
          event={{
            eventId: c.eventId,
            title: c.eventTitle,
            coverUrl: c.coverUrl,
            categories: [],
            startsAt: c.startsAt,
            placeName: c.placeName,
          }}
        />
      )}
      <div className="messages" aria-live="polite">
        {messages.isLoading && <Skeleton />}
        {messages.isError && <ErrorBlock error={messages.error} />}
        {messages.data?.length === 0 && <p className="hint hint-center">Приглашение принято. Напишите первым.</p>}
        {messages.data?.map((m) => (
          <div key={m.id} className={`bubble ${m.authorId === me?.userId ? 'bubble-mine' : ''}`}>
            <p>{m.body}</p>
            <time dateTime={m.createdAt}>{clock(m.createdAt)}</time>
          </div>
        ))}
        <div ref={bottom} />
      </div>
      {c && <div className="chat-report"><ReportButton targetType="user" targetId={c.peerId} label="Пожаловаться на собеседника" /></div>}
      <form
        className="composer composer-chat"
        onSubmit={(e) => {
          e.preventDefault();
          if (text.trim() && !send.isPending) send.mutate(text.trim());
        }}
      >
        <input
          placeholder="Сообщение..."
          aria-label="Сообщение"
          maxLength={2000}
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        <button type="submit" className="icon-btn icon-btn-send" aria-label="Отправить" disabled={!text.trim() || send.isPending}>
          <Send aria-hidden />
        </button>
      </form>
    </div>
  );
}

export function Chats() {
  const param = useParams().id;
  const id = param ? Number(param) : undefined;
  return (
    <div className={`page page-chats ${id ? 'page-chats-open' : ''}`}>
      <div className="chats-left"><ChatList activeId={id} /></div>
      <div className="chats-right">
        {id ? <Conversation key={id} id={id} /> : (
          <EmptyState icon={<MessageCircle />} title="Выберите чат" text="Здесь появится переписка." />
        )}
      </div>
    </div>
  );
}
