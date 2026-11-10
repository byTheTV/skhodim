import {
  createContext, useCallback, useContext, useEffect, useRef, useState,
  type ButtonHTMLAttributes, type ReactNode,
} from 'react';
import { Link } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
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
const ConfettiContext = createContext<() => void>(() => {});
/** Залп конфетти: лайк и «Хочу пойти» должны ощущаться как маленький праздник. */
export const useConfetti = () => useContext(ConfettiContext);

const CONFETTI = ['#FF2E88', '#CCFF00', '#FFD600', '#00E5FF', '#B14DFF', '#FF5A1F', '🎉', '✨', '🔥', '💜'];

function ConfettiBurst({ seed }: { seed: number }) {
  const pieces = Array.from({ length: 34 }, (_, i) => {
    const angle = ((i * 137.5 + seed * 40) % 360) * (Math.PI / 180);
    const distance = 160 + ((i * 53 + seed * 17) % 320);
    return {
      id: i,
      item: CONFETTI[(i + seed) % CONFETTI.length],
      x: Math.cos(angle) * distance,
      y: Math.sin(angle) * distance - 120,
      rotate: (i * 61) % 720,
    };
  });
  return (
    <div className="confetti" aria-hidden>
      {pieces.map((p) => (
        <motion.span
          key={p.id}
          className={p.item.startsWith('#') ? 'confetti-piece' : 'confetti-emoji'}
          style={p.item.startsWith('#') ? { background: p.item } : undefined}
          initial={{ x: 0, y: 0, opacity: 1, scale: 0.4, rotate: 0 }}
          animate={{ x: p.x, y: p.y + 260, opacity: 0, scale: 1.1, rotate: p.rotate }}
          transition={{ duration: 1.1, ease: [0.15, 0.7, 0.4, 1] }}
        >
          {p.item.startsWith('#') ? '' : p.item}
        </motion.span>
      ))}
    </div>
  );
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);
  const push = useCallback((text: string, tone: Toast['tone'] = 'ok') => {
    const id = nextId.current++;
    setToasts((list) => [...list, { id, text, tone }]);
    window.setTimeout(() => setToasts((list) => list.filter((t) => t.id !== id)), 3500);
  }, []);
  const reduceMotion = useReducedMotion();
  const [bursts, setBursts] = useState<number[]>([]);
  const fire = useCallback(() => {
    if (reduceMotion) return;
    const id = nextId.current++;
    setBursts((list) => [...list, id]);
    window.setTimeout(() => setBursts((list) => list.filter((b) => b !== id)), 1300);
  }, [reduceMotion]);
  return (
    <ToastContext.Provider value={push}>
      <ConfettiContext.Provider value={fire}>{children}</ConfettiContext.Provider>
      {bursts.map((b) => <ConfettiBurst key={b} seed={b} />)}
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

/* ---------- Аватар: первая буква имени на сочном цвете ---------- */

export const STICKER_COUNT = 8;
const AVATAR_COLORS: [string, string][] = [
  ['#CCFF00', '#14062E'],
  ['#FF2E88', '#14062E'],
  ['#00E5FF', '#14062E'],
  ['#FFD600', '#14062E'],
  ['#B14DFF', '#14062E'],
  ['#FF5A1F', '#14062E'],
  ['#00D9A3', '#14062E'],
  ['#FFB3D9', '#14062E'],
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
      style={{ width: size, height: size, background: bg, color: fg, fontSize: Math.round(size * 0.42) }}
      aria-hidden
    >
      {Array.from(name)[0]?.toUpperCase() ?? '?'}
    </span>
  );
}

/* ---------- Обложка: фото источника или кислотный плакат категории ---------- */

type CoverStyle = { bg: string; fg: string; accent: string; icon: LucideIcon };
const INK = '#14062E';
const COVER_STYLES: Record<string, CoverStyle> = {
  'Концерт': { bg: '#FF2E88', fg: INK, accent: '#FFD600', icon: Music },
  'Театр': { bg: '#FF5A1F', fg: INK, accent: '#FFD600', icon: Theater },
  'Выставка': { bg: '#00D9A3', fg: INK, accent: '#6A2BFF', icon: Palette },
  'Спорт': { bg: '#CCFF00', fg: INK, accent: '#FF2E88', icon: Dumbbell },
  'Лекция': { bg: '#FFD600', fg: INK, accent: '#2B1BFF', icon: BookOpen },
  'Фестиваль': { bg: '#B14DFF', fg: INK, accent: '#CCFF00', icon: PartyPopper },
  'Кино': { bg: '#2B1BFF', fg: '#FFFFFF', accent: '#FFD600', icon: Film },
  'Мастер-класс': { bg: '#00C2FF', fg: INK, accent: '#FF5A1F', icon: Brush },
  'Вечеринка': { bg: '#14062E', fg: '#CCFF00', accent: '#FF2E88', icon: Sparkles },
  'Стендап': { bg: '#FF9F1C', fg: INK, accent: '#B14DFF', icon: Mic },
  'Шоу': { bg: '#FF3D3D', fg: INK, accent: '#FFD600', icon: Sparkles },
  'Прогулка': { bg: '#7CF0A0', fg: INK, accent: '#2B1BFF', icon: Footprints },
  'Детям': { bg: '#FFB3D9', fg: INK, accent: '#2B1BFF', icon: Baby },
};
const COVER_DEFAULT: CoverStyle = { bg: '#6A2BFF', fg: '#FFFFFF', accent: '#CCFF00', icon: ImageIcon };

export function coverStyle(categories: string[]): CoverStyle {
  return COVER_STYLES[categories[0]] ?? COVER_DEFAULT;
}

/** Узор плаката: один из пяти, номер зависит от события, поэтому у карточки свой постоянный вид. */
function PosterPattern({ seed, color }: { seed: number; color: string }) {
  const variant = seed % 5;
  const cx = 60 + ((seed * 37) % 280);
  const cy = 60 + ((seed * 53) % 200);
  return (
    <svg className="poster" viewBox="0 0 400 600" preserveAspectRatio="xMidYMid slice" aria-hidden>
      {variant === 0 && Array.from({ length: 12 }, (_, i) => {
        const from = (i * 30 * Math.PI) / 180;
        const to = ((i * 30 + 14) * Math.PI) / 180;
        return (
          <polygon
            key={i}
            fill={color}
            points={`${cx},${cy} ${cx + Math.cos(from) * 900},${cy + Math.sin(from) * 900} ${cx + Math.cos(to) * 900},${cy + Math.sin(to) * 900}`}
          />
        );
      })}
      {variant === 1 && [0, 1, 2, 3, 4, 5].map((i) => (
        <circle key={i} cx={cx} cy={cy} r={60 + i * 70} fill="none" stroke={color} strokeWidth="26" />
      ))}
      {variant === 2 && Array.from({ length: 96 }, (_, i) => (
        (i + Math.floor(i / 8)) % 2 === 0
          ? <rect key={i} x={(i % 8) * 50} y={Math.floor(i / 8) * 50} width="50" height="50" fill={color} />
          : null
      ))}
      {variant === 3 && Array.from({ length: 54 }, (_, i) => (
        <circle key={i} cx={25 + (i % 6) * 70} cy={25 + Math.floor(i / 6) * 70} r={8 + ((i * 7 + seed) % 22)} fill={color} />
      ))}
      {variant === 4 && [0, 1, 2, 3, 4, 5, 6].map((i) => (
        <path
          key={i}
          d={`M-20 ${40 + i * 90} Q 100 ${-20 + i * 90} 200 ${40 + i * 90} T 420 ${40 + i * 90}`}
          fill="none"
          stroke={color}
          strokeWidth="24"
        />
      ))}
    </svg>
  );
}

/** Постер из кэша показывается сразу, остальные плавно проявляются, когда загрузятся. */
function markLoaded(img: HTMLImageElement | null) {
  if (img?.complete && img.naturalWidth > 0) img.classList.add('is-loaded');
}

export function Cover({ event, className, priority }: {
  event: Pick<EventSummary, 'eventId' | 'coverUrl' | 'categories' | 'title'>;
  className?: string;
  /** Задан у карточек, которые видны или вот-вот появятся: такой постер грузится сразу, а не лениво. */
  priority?: 'high' | 'low';
}) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [event.coverUrl]);
  const style = coverStyle(event.categories);
  const Icon = style.icon;
  const hasPhoto = Boolean(event.coverUrl) && !failed;
  return (
    <div
      className={`cover ${hasPhoto ? 'cover-photo' : ''} ${className ?? ''}`}
      style={hasPhoto ? { color: style.fg } : { background: style.bg, color: style.fg }}
    >
      {hasPhoto ? (
        <img
          key={event.coverUrl}
          ref={markLoaded}
          // Атрибуты до src: загрузка начинается, как только он задан. Этот React ещё не знает fetchPriority.
          {...(priority ? { fetchpriority: priority } : {})}
          loading={priority ? 'eager' : 'lazy'}
          referrerPolicy="no-referrer"
          src={event.coverUrl!}
          alt=""
          onLoad={(e) => e.currentTarget.classList.add('is-loaded')}
          onError={() => setFailed(true)}
        />
      ) : (
        <>
          <PosterPattern seed={event.eventId} color={style.accent} />
          <span className="cover-icon" style={{ background: style.bg }}><Icon aria-hidden /></span>
        </>
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
          <p>{eventDate(event.startsAt, event.endsAt)}</p>
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
