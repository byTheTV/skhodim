import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, ChevronRight, ExternalLink, Heart, MessageCircle, Send, Share2, Star, Users } from 'lucide-react';
import { api, type EventSummary, type Feedback } from '../api';
import { useAuth } from '../auth';
import { eventDate, metroLabel, plural, price, relativeTime } from '../format';
import { PageHeader } from '../layout';
import {
  Avatar, Button, Cover, EmptyState, ErrorBlock, EventTags, ReportButton, Sheet, Skeleton, Toggle, useToast,
} from '../ui';

/** Серая карточка контекста из макета: обложка 44×48 и три строки. */
export function EventMini({ event }: { event: Pick<EventSummary, 'eventId' | 'title' | 'coverUrl' | 'categories' | 'startsAt' | 'placeName'> }) {
  return (
    <Link to={`/events/${event.eventId}`} className="event-mini">
      <Cover event={event} className="event-mini-cover" />
      <div>
        <b>{event.title}</b>
        <p>{eventDate(event.startsAt)}</p>
        {event.placeName && <p className="muted">{event.placeName}</p>}
      </div>
    </Link>
  );
}

export function ShareSheet({ eventId, title, open, onClose }: { eventId: number; title: string; open: boolean; onClose: () => void }) {
  const toast = useToast();
  const card = useQuery({
    queryKey: ['share-card', eventId],
    queryFn: () => api.shareCard(eventId),
    enabled: open,
    staleTime: 0,
    gcTime: 0,
  });
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!card.data) return undefined;
    const objectUrl = URL.createObjectURL(card.data);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [card.data]);

  const share = async () => {
    if (!card.data) return;
    const file = new File([card.data], `skhodim-${eventId}.png`, { type: 'image/png' });
    try {
      if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title, text: 'Иду. Кто с нами?' });
        return;
      }
    } catch (e) {
      if ((e as Error).name === 'AbortError') return;
    }
    toast('Этот браузер не умеет делиться файлами. Сохраните картинку.');
  };

  return (
    <Sheet open={open} onClose={onClose} title="Карточка для сторис">
      {card.isLoading && <Skeleton rows={1} />}
      {card.isError && <ErrorBlock error={card.error} retry={() => card.refetch()} />}
      {url && <img className="share-preview" src={url} alt={`Карточка события «${title}» для сторис`} />}
      {url && (
        <div className="sheet-actions">
          <a className="btn btn-primary btn-block" href={url} download={`skhodim-${eventId}.png`}>Сохранить PNG</a>
          <Button block onClick={share}><Share2 aria-hidden /> Поделиться</Button>
        </div>
      )}
    </Sheet>
  );
}

function Stars({ rating }: { rating: number }) {
  return (
    <div className="stars" aria-label={`Оценка ${rating} из 5`}>
      {[1, 2, 3, 4, 5].map((n) => <Star key={n} className={n <= rating ? 'star-on' : ''} aria-hidden />)}
    </div>
  );
}

function CommentItem({ item, eventId, onReply }: { item: Feedback; eventId: number; onReply?: (name: string) => void }) {
  const { me } = useAuth();
  const client = useQueryClient();
  const toast = useToast();
  const remove = useMutation({
    mutationFn: () => api.deleteComment(eventId, item.id),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['comments', eventId] });
      client.invalidateQueries({ queryKey: ['event', eventId] });
      toast('Комментарий удалён');
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const mine = me?.userId === item.authorId;
  return (
    <div className="comment">
      <Link to={`/users/${item.authorId}`} aria-label={`Профиль: ${item.displayName}`}>
        <Avatar name={item.displayName} avatarKey={item.avatarKey} size={36} />
      </Link>
      <div className="comment-body">
        <div className="comment-head">
          <Link to={`/users/${item.authorId}`}><b>{item.displayName}</b></Link>
          <span className="muted">{relativeTime(item.createdAt)}</span>
        </div>
        {item.rating != null && <Stars rating={item.rating} />}
        <p>{item.body}</p>
        <div className="comment-actions">
          {onReply && !mine && me && (
            <button type="button" className="link-btn" onClick={() => onReply(item.displayName)}>Ответить</button>
          )}
          {mine && <button type="button" className="link-btn" onClick={() => remove.mutate()}>Удалить</button>}
          {!mine && me && <ReportButton targetType="feedback" targetId={item.id} />}
        </div>
      </div>
    </div>
  );
}

function ReviewForm({ eventId, sessionId }: { eventId: number; sessionId: number }) {
  const client = useQueryClient();
  const toast = useToast();
  const [rating, setRating] = useState(5);
  const [body, setBody] = useState('');
  const send = useMutation({
    mutationFn: () => api.addReview(eventId, sessionId, body.trim(), rating),
    onSuccess: () => {
      toast('Спасибо за отзыв');
      setBody('');
      client.invalidateQueries({ queryKey: ['event', eventId] });
      client.invalidateQueries({ queryKey: ['reviews', eventId] });
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });
  return (
    <form
      className="review-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (body.trim()) send.mutate();
      }}
    >
      <h3>Как всё прошло?</h3>
      <div className="stars stars-input" role="radiogroup" aria-label="Оценка">
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} type="button" role="radio" aria-checked={rating === n} aria-label={`${n} из 5`} onClick={() => setRating(n)}>
            <Star className={n <= rating ? 'star-on' : ''} aria-hidden />
          </button>
        ))}
      </div>
      <textarea
        rows={3}
        maxLength={2000}
        placeholder="Расскажите, что понравилось"
        aria-label="Текст отзыва"
        value={body}
        onChange={(e) => setBody(e.target.value)}
      />
      <Button type="submit" variant="primary" block loading={send.isPending} disabled={!body.trim()}>Оставить отзыв</Button>
    </form>
  );
}

export function EventPage() {
  const id = Number(useParams().id);
  const { signedIn } = useAuth();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const [shareOpen, setShareOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [sessionChoice, setSessionChoice] = useState<number | null>(null);

  const details = useQuery({ queryKey: ['event', id], queryFn: () => api.event(id), enabled: Number.isFinite(id) });
  const comments = useQuery({ queryKey: ['comments', id], queryFn: () => api.comments(id), enabled: Number.isFinite(id) });
  const reviews = useQuery({ queryKey: ['reviews', id], queryFn: () => api.reviews(id), enabled: Number.isFinite(id) });

  const refresh = () => {
    client.invalidateQueries({ queryKey: ['event', id] });
    client.invalidateQueries({ queryKey: ['lists'] });
  };

  const requireSignIn = () => {
    if (!signedIn) {
      navigate(`/login?next=/events/${id}`);
      return true;
    }
    return false;
  };

  const like = useMutation({
    mutationFn: (liked: boolean) => (liked ? api.unreact(id) : api.swipe(id, 'LIKE')),
    onSuccess: refresh,
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const going = useMutation({
    mutationFn: ({ on, session }: { on: boolean; session: number | null }) =>
      (on ? api.goingOn(id, session) : api.goingOff(id, session)),
    onSuccess: (_, vars) => {
      refresh();
      client.invalidateQueries({ queryKey: ['going', id] });
      toast(vars.on ? 'Вы идёте! Теперь видно других желающих' : '«Хочу пойти» снято');
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });

  const upcoming = useMemo(() => {
    const now = Date.now();
    return (details.data?.sessions ?? []).filter(
      (s) => !s.cancelledAt && new Date(s.endsAt ?? s.startsAt).getTime() > now,
    );
  }, [details.data]);

  if (details.isLoading) return <div className="page"><PageHeader title="Событие" back /><Skeleton rows={4} /></div>;
  if (details.isError || !details.data) {
    return <div className="page"><PageHeader title="Событие" back /><ErrorBlock error={details.error} /></div>;
  }

  const { summary: event, state, tags, sourceUrl } = details.data;
  const liked = state.reaction === 'LIKE';
  const isGoing = state.goingSessionId != null;
  const session = sessionChoice ?? state.goingSessionId ?? upcoming[0]?.id ?? null;
  const shownSession = upcoming.find((s) => s.id === session);
  const others = Math.max(0, event.goingCount - (isGoing ? 1 : 0));
  const longText = (event.description?.length ?? 0) > 220;
  const mapUrl = event.latitude != null && event.longitude != null
    ? `https://yandex.ru/maps/?pt=${event.longitude},${event.latitude}&z=16&l=map`
    : null;
  const lastComment = comments.data?.[comments.data.length - 1];

  return (
    <div className="page page-event">
      <PageHeader
        title="Событие"
        back
        actions={(
          <>
            {isGoing && (
              <button type="button" className="icon-btn" aria-label="Карточка для сторис" title="Карточка для сторис" onClick={() => setShareOpen(true)}>
                <Share2 aria-hidden />
              </button>
            )}
            <button
              type="button"
              className={`icon-btn ${liked ? 'icon-btn-liked' : ''}`}
              aria-label={liked ? 'Убрать из понравившегося' : 'Добавить в понравившееся'}
              aria-pressed={liked}
              onClick={() => { if (!requireSignIn()) like.mutate(liked); }}
            >
              <Heart aria-hidden />
            </button>
          </>
        )}
      />
      <div className="event-layout">
        <div className="event-main">
          <Cover event={event} className="event-cover" />
          <h2 className="event-title">{event.title}</h2>
          <div className="meta">
            <p className="line-strong">{eventDate(shownSession?.startsAt ?? event.startsAt, shownSession?.endsAt ?? event.endsAt)}</p>
            <p className="line">{[event.placeName, event.address].filter(Boolean).join(' · ') || 'Место уточняется'}</p>
            {(event.metro || mapUrl) && (
              <p className="line muted">
                {metroLabel(event.metro)}
                {event.metro && mapUrl && ' · '}
                {mapUrl && <a href={mapUrl} target="_blank" rel="noreferrer">На карте</a>}
              </p>
            )}
          </div>
          <EventTags event={event} limit={4} />
          {tags.length > 0 && <p className="tags-line">{tags.map((t) => `#${t.name}`).join(' ')}</p>}
          {event.description && (
            <div className={`event-desc ${expanded || !longText ? '' : 'event-desc-clamp'}`}>
              <p>{event.description}</p>
            </div>
          )}
          {longText && (
            <button type="button" className="link-btn link-strong" onClick={() => setExpanded(!expanded)}>
              {expanded ? 'Свернуть' : 'Читать полностью'}
            </button>
          )}
        </div>

        <div className="event-side">
          <div className="buy-card">
            <div>
              <b className="buy-price">{price(event)}</b>
              {!event.isFree && <small>за билет</small>}
            </div>
            <a className="btn btn-secondary" href={event.ticketUrl ?? sourceUrl} target="_blank" rel="noreferrer">
              <ExternalLink aria-hidden /> Билеты
            </a>
          </div>

          {upcoming.length > 1 && (
            <label className="field">
              <span>Сеанс</span>
              <select value={session ?? ''} disabled={isGoing} onChange={(e) => setSessionChoice(Number(e.target.value))}>
                {upcoming.map((s) => <option key={s.id} value={s.id}>{eventDate(s.startsAt)}</option>)}
              </select>
            </label>
          )}

          {upcoming.length > 0 ? (
            <div className={`going-card ${isGoing ? 'going-card-on' : ''}`}>
              <div>
                <b>{isGoing ? 'Вы идёте' : 'Хочу пойти'}</b>
                {isGoing && <small>«Хочу пойти» включено</small>}
              </div>
              <Toggle
                label="Хочу пойти"
                checked={isGoing}
                disabled={going.isPending}
                onChange={(on) => { if (!requireSignIn()) going.mutate({ on, session }); }}
              />
            </div>
          ) : (
            <p className="note">Это событие уже прошло</p>
          )}

          {isGoing && (
            <Link to={`/events/${id}/going`} className="btn btn-secondary btn-block btn-compact">
              <Users aria-hidden /> Желающие · {others}
            </Link>
          )}

          {state.reviewSessionId != null && !state.hasReview && (
            <ReviewForm eventId={id} sessionId={state.reviewSessionId} />
          )}
        </div>
      </div>

      <hr className="divider" />

      {reviews.data && reviews.data.length > 0 && (
        <section className="event-section">
          <h2>Отзывы · {reviews.data.length}</h2>
          {reviews.data.map((r) => <CommentItem key={r.id} item={r} eventId={id} />)}
        </section>
      )}

      <section className="event-section">
        <div className="section-head">
          <h2>Комментарии · {comments.data?.length ?? 0}</h2>
          <Link to={`/events/${id}/comments`} className="link-btn link-strong">
            Все комментарии <ArrowRight aria-hidden />
          </Link>
        </div>
        {lastComment ? (
          <div className="comment comment-compact">
            <Avatar name={lastComment.displayName} avatarKey={lastComment.avatarKey} size={28} />
            <div className="comment-body">
              <b>{lastComment.displayName} · {relativeTime(lastComment.createdAt)}</b>
              <p>{lastComment.body}</p>
            </div>
          </div>
        ) : (
          <p className="hint">Пока никто не писал. Начните обсуждение первым.</p>
        )}
      </section>

      {signedIn && <ReportButton targetType="event" targetId={id} label="Пожаловаться на событие" />}
      {signedIn && <ShareSheet eventId={id} title={event.title} open={shareOpen} onClose={() => setShareOpen(false)} />}
    </div>
  );
}

export function Comments() {
  const id = Number(useParams().id);
  const { signedIn } = useAuth();
  const client = useQueryClient();
  const toast = useToast();
  const [text, setText] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const details = useQuery({ queryKey: ['event', id], queryFn: () => api.event(id) });
  const comments = useQuery({ queryKey: ['comments', id], queryFn: () => api.comments(id) });
  const send = useMutation({
    mutationFn: () => api.addComment(id, text.trim()),
    onSuccess: () => {
      setText('');
      client.invalidateQueries({ queryKey: ['comments', id] });
      client.invalidateQueries({ queryKey: ['event', id] });
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const reply = (name: string) => {
    setText(`${name}, `);
    input.current?.focus();
  };
  return (
    <div className="page">
      <PageHeader title="Комментарии" back />
      {details.data && <EventMini event={details.data.summary} />}
      <h2 className="section-title">Обсуждение · {comments.data?.length ?? 0}</h2>
      {comments.isLoading && <Skeleton />}
      {comments.isError && <ErrorBlock error={comments.error} retry={() => comments.refetch()} />}
      {comments.data && comments.data.length > 0 && (
        <div>{comments.data.map((c) => <CommentItem key={c.id} item={c} eventId={id} onReply={reply} />)}</div>
      )}
      {comments.data?.length === 0 && (
        <EmptyState icon={<MessageCircle />} title="Здесь пока тихо" text="Спросите, кто идёт, или посоветуйте, где встретиться." />
      )}
      {signedIn ? (
        <form
          className="composer"
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim()) send.mutate();
          }}
        >
          <input
            ref={input}
            placeholder="Ваш комментарий..."
            aria-label="Ваш комментарий"
            maxLength={2000}
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
          <button type="submit" aria-label="Отправить" disabled={!text.trim() || send.isPending}><Send aria-hidden /></button>
        </form>
      ) : (
        <Link to={`/login?next=/events/${id}/comments`} className="btn btn-primary btn-block">Войти, чтобы написать</Link>
      )}
    </div>
  );
}

function requestLabel(status: string, outgoing: boolean | null): string {
  switch (status) {
    case 'PENDING': return outgoing ? 'Приглашение отправлено' : 'Приглашает вас';
    case 'ACCEPTED': return 'Вы договорились';
    case 'REJECTED': return 'Приглашение отклонено';
    default: return 'Приглашение отменено';
  }
}

export function Going() {
  const id = Number(useParams().id);
  const details = useQuery({ queryKey: ['event', id], queryFn: () => api.event(id) });
  const people = useQuery({ queryKey: ['going', id], queryFn: () => api.goingPeople(id) });
  const isGoing = details.data?.state.goingSessionId != null;
  const list = people.data ?? [];
  const event = details.data?.summary;
  return (
    <div className="page">
      <PageHeader title={`Желающие · ${list.length}`} back />
      {event && <EventMini event={event} />}
      {details.data && !isGoing && (
        <EmptyState
          icon={<Users />}
          title="Сначала отметьте «Хочу пойти»"
          text="Список желающих виден только тем, кто сам идёт на это событие."
          action={<Link to={`/events/${id}`} className="btn btn-primary btn-block">Вернуться к событию</Link>}
        />
      )}
      {people.isLoading && <Skeleton />}
      {people.isError && <ErrorBlock error={people.error} retry={() => people.refetch()} />}
      {isGoing && people.data && list.length === 0 && (
        <>
          <div className="note">
            <b><Check aria-hidden style={{ width: 16, height: 16, verticalAlign: -3, marginRight: 6 }} />Вы идёте</b>
            <p>«Хочу пойти» включено. Других людей: 0.</p>
          </div>
          <EmptyState
            icon={<Users />}
            title="Вы пока первый"
            text="На это событие, кроме вас, никто не собирается. Загляните позже: компания может появиться."
            action={(
              <>
                <Link to={`/events/${id}`} className="btn btn-primary btn-block">Вернуться к событию</Link>
                <Link to="/" className="btn btn-secondary btn-block">Найти другое событие</Link>
              </>
            )}
          />
        </>
      )}
      {isGoing && list.length > 0 && (
        <>
          <p className="hint">Вы идёте. Ещё {list.length} {plural(list.length, 'человек хочет', 'человека хотят', 'человек хотят')} пойти:</p>
          <div className="people">
            {list.map((p) => (
              <Link key={p.userId} to={`/users/${p.userId}?event=${id}&session=${p.sessionId}`} className="person-row">
                <Avatar name={p.displayName} avatarKey={p.avatarKey} size={38} />
                <div>
                  <b>@{p.nickname}</b>
                  <p className="muted">{p.interests.slice(0, 3).join(' · ')}</p>
                  {p.recentLike && <p className="muted small">Недавний лайк: {p.recentLike}</p>}
                  {p.requestStatus && <p className="status-line"><Check aria-hidden /> {requestLabel(p.requestStatus, p.requestOutgoing)}</p>}
                  {!p.sameSession && <p className="muted small">Идёт на другой сеанс</p>}
                </div>
                <ChevronRight className="row-arrow" aria-hidden />
              </Link>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
