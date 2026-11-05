import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, Check, CheckCheck, MessageCircle, Sparkles, X } from 'lucide-react';
import { api, type NotificationView } from '../api';
import { relativeTime } from '../format';
import { PageHeader } from '../layout';
import { EmptyState, ErrorBlock, Skeleton, useToast } from '../ui';

function describe(n: NotificationView): { icon: JSX.Element; title: string; to: string } {
  const who = n.actorName ?? 'Участник';
  const event = n.eventTitle ? `«${n.eventTitle}»` : 'событие';
  switch (n.kind) {
    case 'REQUEST':
      return { icon: <Sparkles aria-hidden />, title: `${who} приглашает вас на ${event}`, to: `/invites/${n.requestId}` };
    case 'ACCEPTED':
      return { icon: <Check aria-hidden />, title: `${who} принимает приглашение на ${event}`, to: `/invites/${n.requestId}` };
    case 'REJECTED':
      return { icon: <X aria-hidden />, title: `${who} не сможет пойти на ${event}`, to: `/invites/${n.requestId}` };
    default:
      return { icon: <MessageCircle aria-hidden />, title: `Сообщение от: ${who}`, to: `/chats/${n.conversationId}` };
  }
}

export function Notifications() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const list = useQuery({ queryKey: ['notifications'], queryFn: api.notifications, refetchInterval: 15_000 });
  const refresh = () => {
    client.invalidateQueries({ queryKey: ['notifications'] });
    client.invalidateQueries({ queryKey: ['badges'] });
  };
  const readAll = useMutation({
    mutationFn: api.readAllNotifications,
    onSuccess: refresh,
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const hasUnread = list.data?.some((n) => !n.readAt);
  return (
    <div className="page page-narrow">
      <PageHeader
        title="Уведомления"
        back="/profile"
        section
        actions={hasUnread ? (
          <button type="button" className="icon-btn" aria-label="Прочитать все" title="Прочитать все" onClick={() => readAll.mutate()}>
            <CheckCheck aria-hidden />
          </button>
        ) : undefined}
      />
      {list.isLoading && <Skeleton />}
      {list.isError && <ErrorBlock error={list.error} retry={() => list.refetch()} />}
      {list.data?.length === 0 && (
        <EmptyState icon={<Bell />} title="Всё спокойно" text="Здесь появятся приглашения, ответы на них и новые сообщения." />
      )}
      <div className="people">
        {list.data?.map((n) => {
          const info = describe(n);
          return (
            <button
              key={n.id}
              type="button"
              className={`notification ${n.readAt ? '' : 'notification-new'}`}
              onClick={() => {
                if (!n.readAt) api.readNotification(n.id).then(refresh).catch(() => undefined);
                navigate(info.to);
              }}
            >
              <span className={`notification-icon notification-${n.kind.toLowerCase()}`}>{info.icon}</span>
              <span className="notification-text">
                <b>{info.title}</b>
                {n.preview && <span className="muted">{n.preview}</span>}
                <small className="muted">{relativeTime(n.createdAt)}</small>
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
