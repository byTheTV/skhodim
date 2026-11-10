import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Bell, Layers, ListChecks, MessageCircle, Search, ShieldCheck, Mail, Ticket, UserRound, Users } from 'lucide-react';
import { api } from './api';
import { useAuth } from './auth';
import { Avatar, Badge } from './ui';

function useBadges() {
  const { signedIn } = useAuth();
  return useQuery({
    queryKey: ['badges'],
    queryFn: api.badges,
    enabled: signedIn,
    refetchInterval: 20_000,
  }).data;
}

const TABS = [
  { to: '/', label: 'Свайп', icon: Layers, end: true },
  { to: '/social', label: 'Соцсеть', icon: Users },
  { to: '/list', label: 'Список', icon: ListChecks },
  { to: '/profile', label: 'Профиль', icon: UserRound },
];

/** На компьютере разделы живут в шапке, профиль открывается по аватару. */
const TOP_TABS = [
  { to: '/', label: 'Свайп', icon: Layers, end: true },
  { to: '/search', label: 'Афиша', icon: Ticket },
  { to: '/social', label: 'Соцсеть', icon: Users },
  { to: '/list', label: 'Список', icon: ListChecks },
];

function TopSearch() {
  const navigate = useNavigate();
  const [text, setText] = useState('');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const query = text.trim();
    navigate(query ? `/search?q=${encodeURIComponent(query)}` : '/search');
    setText('');
  };
  return (
    <form className="top-search" role="search" onSubmit={submit}>
      <Search aria-hidden />
      <input
        type="search"
        placeholder="Концерт, спектакль, площадка"
        aria-label="Найти событие"
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
    </form>
  );
}

export function Shell() {
  const { signedIn, me } = useAuth();
  const badges = useBadges();
  const location = useLocation();
  const inbox = (badges?.pendingInvites ?? 0) + (badges?.unreadChats ?? 0);
  const showBadges = me?.profile.notificationsEnabled !== false;
  const isChat = /^\/chats\/\d+/.test(location.pathname);

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [location.pathname]);

  return (
    <div className={`shell ${isChat ? 'shell-chat' : ''}`}>
      <a className="skip-link" href="#main">К содержимому</a>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="logo" aria-label="Сходим, на главную">сходим<span>!</span></Link>
          <nav className="top-nav" aria-label="Основная навигация">
            {TOP_TABS.map((tab) => (
              <NavLink key={tab.to} to={tab.to} end={tab.end} className="top-link">
                <tab.icon aria-hidden />
                <span>{tab.label}</span>
              </NavLink>
            ))}
          </nav>
          {location.pathname !== '/search' && <TopSearch />}
          {signedIn && (
            <div className="top-icons">
              <NavLink to="/invites" className="top-icon" aria-label="Приглашения" title="Приглашения">
                <Mail aria-hidden />
                {showBadges && <Badge count={badges?.pendingInvites ?? 0} />}
              </NavLink>
              <NavLink to="/chats" className="top-icon" aria-label="Чаты" title="Чаты">
                <MessageCircle aria-hidden />
                {showBadges && <Badge count={badges?.unreadChats ?? 0} />}
              </NavLink>
              <NavLink to="/notifications" className="top-icon" aria-label="Уведомления" title="Уведомления">
                <Bell aria-hidden />
                {showBadges && <Badge count={badges?.unreadNotifications ?? 0} />}
              </NavLink>
              {me?.role === 'MODERATOR' && (
                <NavLink to="/admin" className="top-icon" aria-label="Модерация" title="Модерация">
                  <ShieldCheck aria-hidden />
                </NavLink>
              )}
            </div>
          )}
          {signedIn && me ? (
            <NavLink to="/profile" className="top-me" aria-label="Профиль" title={me.profile.displayName ?? me.profile.nickname}>
              <Avatar name={me.profile.displayName ?? me.profile.nickname} avatarKey={me.profile.avatarKey} size={40} />
            </NavLink>
          ) : !signedIn ? (
            <Link to="/login" className="btn btn-primary top-login">Войти</Link>
          ) : null}
        </div>
      </header>

      <main id="main" className="main">
        <Outlet />
      </main>

      <nav className="tabbar" aria-label="Основная навигация">
        {TABS.map((tab) => (
          <NavLink key={tab.to} to={tab.to} end={tab.end} className="tab">
            <span className="tab-icon">
              <tab.icon aria-hidden />
              {tab.to === '/profile' && showBadges && <Badge count={inbox} />}
            </span>
            <span>{tab.label}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  );
}

export function PageHeader({ title, back, actions, section }: {
  title: ReactNode;
  back?: boolean | string;
  actions?: ReactNode;
  /** Раздел из шапки: на компьютере кнопка «Назад» ему не нужна. */
  section?: boolean;
}) {
  const navigate = useNavigate();
  return (
    <header className={`page-header ${section ? 'is-section' : ''}`}>
      {back && (
        <button
          type="button"
          className="back-btn"
          aria-label="Назад"
          onClick={() => {
            if (typeof back === 'string') navigate(back);
            else if (window.history.length > 1) navigate(-1);
            else navigate('/');
          }}
        >
          <ArrowLeft aria-hidden />
        </button>
      )}
      <div className="page-title">
        <h1>{title}</h1>
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

const THEME_KEY = 'skhodim-theme';
export type Theme = 'auto' | 'light' | 'dark';

export function useTheme(): [Theme, (t: Theme) => void] {
  const [theme, setThemeState] = useState<Theme>(() => {
    try {
      const saved = localStorage.getItem(THEME_KEY);
      return saved === 'light' || saved === 'dark' ? saved : 'auto';
    } catch {
      return 'auto';
    }
  });
  const setTheme = (next: Theme) => {
    setThemeState(next);
    try {
      if (next === 'auto') localStorage.removeItem(THEME_KEY);
      else localStorage.setItem(THEME_KEY, next);
    } catch {
      // Без хранилища тема действует только до перезагрузки.
    }
    if (next === 'auto') delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = next;
  };
  return [theme, setTheme];
}
