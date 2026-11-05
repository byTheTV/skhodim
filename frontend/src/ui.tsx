import {
  createContext, useCallback, useContext, useEffect, useRef, useState,
  type ButtonHTMLAttributes, type ReactNode,
} from 'react';
import { Link } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useMutation } from '@tanstack/react-query';
import { AlertTriangle, Calendar, ChevronRight, Flag, Loader2, MapPin, Users, X } from 'lucide-react';
import { api, type EventSummary } from './api';
import { eventDate, place, plural, price } from './format';

/* ---------- Уведомления внизу экрана ---------- */

type Toast = { id: number; text: string; tone: 'ok' | 'error' };
const ToastContext = createContext<(text: string, tone?: Toast['tone']) => void>(() => {});
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);
  const push = useCallback((text: string, tone: Toast['tone'] = 'ok') => {
    const id = nextId.current++;
    setToasts((list) => [...list, { id, text, tone }]);
    window.setTimeout(() => setToasts((list) => list.filter((t) => t.id !== id)), 3500);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        <AnimatePresence>
          {toasts.map((t) => (
            <motion.div
              key={t.id}
              className={`toast toast-${t.tone}`}
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 16 }}
            >
              {t.text}
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </ToastContext.Provider>
  );
}

/* ---------- Базовые элементы ---------- */

export function Button({ variant = 'secondary', loading, block, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'accent' | 'danger';
  loading?: boolean;
  block?: boolean;
}) {
  return (
    <button
      type="button"
      {...rest}
      disabled={rest.disabled || loading}
      className={`btn btn-${variant} ${block ? 'btn-block' : ''} ${rest.className ?? ''}`}
    >
      {loading ? <Loader2 className="spin" aria-hidden /> : null}
      {children}
    </button>
  );
}

export function Badge({ count }: { count: number }) {
  if (!count) return null;
  return <span className="badge" aria-label={`Новых: ${count}`}>{count > 99 ? '99+' : count}</span>;
}

export function Chip({ children, active, onClick, tone }: {
  children: ReactNode;
  active?: boolean;
  onClick?: () => void;
  tone?: 'lime' | 'coral' | 'violet';
}) {
  const cls = `chip ${active ? 'chip-active' : ''} ${tone ? `chip-${tone}` : ''}`;
  if (onClick) {
    return <button type="button" className={cls} aria-pressed={Boolean(active)} onClick={onClick}>{children}</button>;
  }
  return <span className={cls}>{children}</span>;
}

export function Toggle({ checked, onChange, label, disabled }: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={`toggle ${checked ? 'toggle-on' : ''}`}
      onClick={() => onChange(!checked)}
    >
      <span />
    </button>
  );
}

export function Tabs<T extends string>({ value, onChange, items }: {
  value: T;
  onChange: (value: T) => void;
  items: { value: T; label: string }[];
}) {
  return (
    <div className="tabs" role="tablist">
      {items.map((item) => (
        <button
          key={item.value}
          type="button"
          role="tab"
          aria-selected={value === item.value}
          className={`tab-pill ${value === item.value ? 'tab-pill-active' : ''}`}
          onClick={() => onChange(item.value)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}

export function EmptyState({ icon, title, text, action }: {
  icon: ReactNode;
  title: string;
  text?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-icon">{icon}</div>
      <h2>{title}</h2>
      {text && <p>{text}</p>}
      {action && <div className="empty-action">{action}</div>}
    </div>
  );
}

export function ErrorBlock({ error, retry }: { error: unknown; retry?: () => void }) {
  const message = error instanceof Error ? error.message : 'Что-то пошло не так';
  return (
    <div className="error-block" role="alert">
      <AlertTriangle aria-hidden />
      <p>{message}</p>
      {retry && <Button onClick={retry}>Повторить</Button>}
    </div>
  );
}

export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="skeletons" aria-busy="true" aria-label="Загрузка">
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" />)}
    </div>
  );
}

/* ---------- Аватар: стикер вместо загрузки фото ---------- */

export const STICKER_COUNT = 8;
const STICKERS: [string, string][] = [
  ['#6B3DF5', '#C6F135'],
  ['#FF5A36', '#FFF4E0'],
  ['#C6F135', '#1B1240'],
  ['#FF4D8D', '#FFF4E0'],
  ['#FFC83D', '#1B1240'],
  ['#1B1240', '#C6F135'],
  ['#00B8A9', '#FFF4E0'],
  ['#B69CFF', '#1B1240'],
];

export function Avatar({ name, avatarKey, size = 44 }: { name: string; avatarKey?: string | null; size?: number }) {
  const match = avatarKey?.match(/^sticker:(\d+)$/);
  let index = match ? Number(match[1]) - 1 : -1;
  if (index < 0 || index >= STICKERS.length) {
    index = Array.from(name).reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % STICKERS.length;
  }
  const [bg, fg] = STICKERS[index];
  return (
    <span
      className="avatar"
      style={{ width: size, height: size, background: bg, color: fg, fontSize: size * 0.42 }}
      aria-hidden
    >
      {Array.from(name)[0]?.toUpperCase() ?? '?'}
    </span>
  );
}

/* ---------- Обложка: своё изображение источника или нарисованный постер ---------- */

const POSTER_COLORS: Record<string, [string, string, string]> = {
  'Концерт': ['#6B3DF5', '#FF4D8D', '#C6F135'],
  'Театр': ['#FF5A36', '#1B1240', '#FFC83D'],
  'Выставка': ['#00B8A9', '#FFC83D', '#1B1240'],
  'Спорт': ['#C6F135', '#1B1240', '#FF5A36'],
  'Лекция': ['#FFC83D', '#6B3DF5', '#FFF4E0'],
  'Фестиваль': ['#FF4D8D', '#FFC83D', '#6B3DF5'],
  'Кино': ['#1B1240', '#FF5A36', '#C6F135'],
  'Мастер-класс': ['#B69CFF', '#FF5A36', '#1B1240'],
  'Вечеринка': ['#FF4D8D', '#1B1240', '#C6F135'],
  'Стендап': ['#FFC83D', '#FF5A36', '#1B1240'],
  'Прогулка': ['#00B8A9', '#C6F135', '#1B1240'],
  'Детям': ['#C6F135', '#FF4D8D', '#6B3DF5'],
};
const DEFAULT_POSTER: [string, string, string] = ['#6B3DF5', '#FF5A36', '#C6F135'];

function Poster({ seed, category }: { seed: number; category?: string }) {
  const [bg, a, b] = (category && POSTER_COLORS[category]) || DEFAULT_POSTER;
  const variant = seed % 4;
  const x = 40 + (seed * 37) % 240;
  const y = 30 + (seed * 53) % 140;
  return (
    <svg className="poster" viewBox="0 0 320 240" preserveAspectRatio="xMidYMid slice" aria-hidden>
      <rect width="320" height="240" fill={bg} />
      {variant === 0 && (<>
        <circle cx={x} cy={y} r="96" fill={a} />
        <circle cx={320 - x} cy={240 - y} r="56" fill={b} />
      </>)}
      {variant === 1 && (<>
        <rect x={x - 90} y="-30" width="90" height="320" fill={a} transform={`rotate(18 ${x} 120)`} />
        <circle cx={320 - x / 2} cy={y} r="48" fill={b} />
      </>)}
      {variant === 2 && (<>
        <path d={`M0 ${y + 60} Q 160 ${y - 80} 320 ${y + 40} V240 H0 Z`} fill={a} />
        <circle cx={x} cy={y - 10} r="34" fill={b} />
      </>)}
      {variant === 3 && (<>
        {[0, 1, 2, 3].map((i) => (
          <circle key={i} cx={x} cy={y} r={120 - i * 28} fill="none" stroke={i % 2 ? b : a} strokeWidth="12" />
        ))}
      </>)}
    </svg>
  );
}

export function Cover({ event, className }: {
  event: Pick<EventSummary, 'eventId' | 'coverUrl' | 'categories' | 'title'>;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [event.coverUrl]);
  return (
    <div className={`cover ${className ?? ''}`}>
      {event.coverUrl && !failed ? (
        <img src={event.coverUrl} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} />
      ) : (
        <Poster seed={event.eventId} category={event.categories[0]} />
      )}
    </div>
  );
}

/* ---------- Строка события ---------- */

export function EventTags({ event, limit = 2 }: { event: EventSummary; limit?: number }) {
  return (
    <div className="chips">
      {event.categories.slice(0, limit).map((c) => <Chip key={c}>{c}</Chip>)}
      {event.ageLimit != null && event.ageLimit > 0 && <Chip tone="coral">{event.ageLimit}+</Chip>}
      {event.isFree && <Chip tone="lime">Бесплатно</Chip>}
    </div>
  );
}

export function EventMeta({ event }: { event: EventSummary }) {
  return (
    <ul className="meta">
      <li><Calendar aria-hidden />{eventDate(event.startsAt, event.endsAt)}</li>
      <li><MapPin aria-hidden />{place(event)}</li>
    </ul>
  );
}

export function EventRow({ event, footer, compact }: { event: EventSummary; footer?: ReactNode; compact?: boolean }) {
  return (
    <article className={`event-row ${compact ? 'event-row-compact' : ''}`}>
      <Link to={`/events/${event.eventId}`} className="event-row-link">
        <Cover event={event} className="event-row-cover" />
        <div className="event-row-body">
          <h3>{event.title}</h3>
          <p>{eventDate(event.startsAt)}</p>
          <p className="muted">{place(event)}</p>
          {!compact && (
            <p className="event-row-price">
              {price(event)}
              {event.ageLimit != null && event.ageLimit > 0 ? ` · ${event.ageLimit}+` : ''}
            </p>
          )}
        </div>
        <ChevronRight className="row-arrow" aria-hidden />
      </Link>
      {footer && <div className="event-row-footer">{footer}</div>}
    </article>
  );
}

export function GoingLine({ count }: { count: number }) {
  return (
    <span className="going-line">
      <Users aria-hidden />
      {count} {plural(count, 'человек идёт', 'человека идут', 'человек идут')}
    </span>
  );
}

/* ---------- Модальное окно ---------- */

export function Sheet({ open, onClose, title, children }: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
      previous?.focus?.();
    };
  }, [open, onClose]);
  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="sheet-backdrop"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onClose}
        >
          <motion.div
            ref={panel}
            className="sheet"
            role="dialog"
            aria-modal="true"
            aria-label={title}
            tabIndex={-1}
            initial={{ y: 40, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: 40, opacity: 0 }}
            transition={{ type: 'spring', stiffness: 380, damping: 34 }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sheet-head">
              <h2>{title}</h2>
              <button type="button" className="icon-btn" aria-label="Закрыть" onClick={onClose}><X aria-hidden /></button>
            </div>
            <div className="sheet-body">{children}</div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/* ---------- Жалоба ---------- */

const REPORT_REASONS = ['Спам или реклама', 'Оскорбления', 'Недостоверная информация', 'Другое'];

export function ReportButton({ targetType, targetId, label = 'Пожаловаться' }: {
  targetType: 'event' | 'user' | 'feedback';
  targetId: number;
  label?: string;
}) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState(REPORT_REASONS[0]);
  const [details, setDetails] = useState('');
  const send = useMutation({
    mutationFn: () => api.report(targetType, targetId, details.trim() ? `${reason}: ${details.trim()}` : reason),
    onSuccess: () => {
      toast('Жалоба отправлена модератору');
      setOpen(false);
      setDetails('');
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });
  return (
    <>
      <button type="button" className="link-btn" onClick={() => setOpen(true)}>
        <Flag aria-hidden />{label}
      </button>
      <Sheet open={open} onClose={() => setOpen(false)} title="Жалоба модератору">
        <div className="chips">
          {REPORT_REASONS.map((r) => <Chip key={r} active={reason === r} onClick={() => setReason(r)}>{r}</Chip>)}
        </div>
        <label className="field">
          <span>Подробности, если нужно</span>
          <textarea rows={3} maxLength={800} value={details} onChange={(e) => setDetails(e.target.value)} />
        </label>
        <Button variant="primary" block loading={send.isPending} onClick={() => send.mutate()}>Отправить</Button>
      </Sheet>
    </>
  );
}

export function useDebounced<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(id);
  }, [value, delay]);
  return debounced;
}
