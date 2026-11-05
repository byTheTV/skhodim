import {
  createContext, useCallback, useContext, useEffect, useRef, useState,
  type ButtonHTMLAttributes, type ReactNode,
} from 'react';
import { Link } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useMutation } from '@tanstack/react-query';
import {
  AlertTriangle, ArrowLeft, Baby, BookOpen, Brush, ChevronRight, Dumbbell, Film, Flag, Footprints, Image as ImageIcon,
  Loader2, Mic, Music, Palette, PartyPopper, Sparkles, Theater, X, type LucideIcon,
} from 'lucide-react';
import { api, type EventSummary } from './api';
import { eventDate, place, price } from './format';

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
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 12 }}
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
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
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

export function Chip({ children, active, onClick }: {
  children: ReactNode;
  active?: boolean;
  onClick?: () => void;
}) {
  const cls = `chip ${active ? 'chip-active' : ''}`;
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

/* ---------- Аватар: первая буква имени на мягком цвете ---------- */

export const STICKER_COUNT = 8;
const AVATAR_COLORS: [string, string][] = [
  ['#e4dffe', '#3f25c4'],
  ['#ffe2da', '#a8320f'],
  ['#dff3d8', '#2c6b1c'],
  ['#ffe0ec', '#a81f58'],
  ['#fff0c7', '#7a5600'],
  ['#d7eefb', '#125f8c'],
  ['#d6f3ee', '#0f6b5e'],
  ['#ebe4d8', '#5f4c2c'],
];

export function Avatar({ name, avatarKey, size = 44 }: { name: string; avatarKey?: string | null; size?: number }) {
  const match = avatarKey?.match(/^sticker:(\d+)$/);
  let index = match ? Number(match[1]) - 1 : -1;
  if (index < 0 || index >= AVATAR_COLORS.length) {
    index = Array.from(name).reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % AVATAR_COLORS.length;
  }
  const [bg, fg] = AVATAR_COLORS[index];
  return (
    <span
      className="avatar"
      style={{ width: size, height: size, background: bg, color: fg, fontSize: Math.round(size * 0.4) }}
      aria-hidden
    >
      {Array.from(name)[0]?.toUpperCase() ?? '?'}
    </span>
  );
}

/* ---------- Обложка: фото источника или спокойная заглушка со значком категории ---------- */

const COVER_STYLES: Record<string, { bg: string; fg: string; icon: LucideIcon }> = {
  'Концерт': { bg: '#e4dffe', fg: '#3f25c4', icon: Music },
  'Театр': { bg: '#ffe2da', fg: '#a8320f', icon: Theater },
  'Выставка': { bg: '#d6f3ee', fg: '#0f6b5e', icon: Palette },
  'Спорт': { bg: '#dff3d8', fg: '#2c6b1c', icon: Dumbbell },
  'Лекция': { bg: '#fff0c7', fg: '#7a5600', icon: BookOpen },
  'Фестиваль': { bg: '#ffe0ec', fg: '#a81f58', icon: PartyPopper },
  'Кино': { bg: '#d7eefb', fg: '#125f8c', icon: Film },
  'Мастер-класс': { bg: '#ebe4d8', fg: '#5f4c2c', icon: Brush },
  'Вечеринка': { bg: '#f3dcfb', fg: '#7a1f9c', icon: Sparkles },
  'Стендап': { bg: '#ffe9d1', fg: '#94500a', icon: Mic },
  'Прогулка': { bg: '#dcf2e4', fg: '#1a6b3c', icon: Footprints },
  'Детям': { bg: '#fdf0d0', fg: '#86600a', icon: Baby },
};
const COVER_DEFAULT = { bg: '#ece9f8', fg: '#4326d1', icon: ImageIcon };

export function Cover({ event, className }: {
  event: Pick<EventSummary, 'eventId' | 'coverUrl' | 'categories' | 'title'>;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [event.coverUrl]);
  const style = COVER_STYLES[event.categories[0]] ?? COVER_DEFAULT;
  const Icon = style.icon;
  return (
    <div className={`cover ${className ?? ''}`} style={{ background: style.bg, color: style.fg }}>
      {event.coverUrl && !failed ? (
        <img src={event.coverUrl} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} />
      ) : (
        <Icon aria-hidden />
      )}
    </div>
  );
}

/* ---------- Событие ---------- */

export function EventTags({ event, limit = 2 }: { event: EventSummary; limit?: number }) {
  return (
    <div className="chips">
      {event.categories.slice(0, limit).map((c) => <Chip key={c}>{c}</Chip>)}
      {event.ageLimit != null && event.ageLimit > 0 && <Chip>{event.ageLimit}+</Chip>}
    </div>
  );
}

/** Плоская строка списка из макета: обложка 72×88, четыре строки текста, шеврон и разделитель. */
export function EventRow({ event, footer, compact }: { event: EventSummary; footer?: ReactNode; compact?: boolean }) {
  const age = event.ageLimit != null && event.ageLimit > 0 ? ` · ${event.ageLimit}+` : '';
  return (
    <article className={`event-row ${compact ? 'event-row-compact' : ''}`}>
      <Link to={`/events/${event.eventId}`} className="event-row-link">
        <Cover event={event} className="event-row-cover" />
        <div className="event-row-body">
          <h3>{event.title}</h3>
          <p>{eventDate(event.startsAt)}</p>
          <p className="muted">{place(event)}</p>
          {!compact && <p className="event-row-price">{price(event)}{age}</p>}
        </div>
        <ChevronRight className="row-arrow" aria-hidden />
      </Link>
      {footer && <div className="event-row-footer">{footer}</div>}
    </article>
  );
}

/* ---------- Лист или отдельный экран ---------- */

export function Sheet({ open, onClose, title, children, page }: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** На телефоне занимает весь экран с шапкой «назад», как отдельная страница макета. */
  page?: boolean;
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
          className={`sheet-backdrop ${page ? 'sheet-backdrop-page' : ''}`}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.12 }}
          onClick={onClose}
        >
          <div
            ref={panel}
            className={`sheet ${page ? 'sheet-page' : ''}`}
            role="dialog"
            aria-modal="true"
            aria-label={title}
            tabIndex={-1}
            onClick={(e) => e.stopPropagation()}
          >
            {page ? (
              <div className="sheet-head sheet-head-page">
                <button type="button" className="back-btn" aria-label="Закрыть" onClick={onClose}><ArrowLeft aria-hidden /></button>
                <h1>{title}</h1>
              </div>
            ) : (
              <div className="sheet-head">
                <h2>{title}</h2>
                <button type="button" className="back-btn" aria-label="Закрыть" onClick={onClose}><X aria-hidden /></button>
              </div>
            )}
            <div className="sheet-body">{children}</div>
          </div>
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
