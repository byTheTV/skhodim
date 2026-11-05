import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Bell, Layers, ListChecks, MessageCircle, Search, ShieldCheck, Mail, UserRound, Users } from 'lucide-react';
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
      <aside className="sidebar">
        <Link to="/" className="brand">Сходим</Link>
        <nav className="side-nav" aria-label="Основная навигация">
          {TABS.map((tab) => (
            <NavLink key={tab.to} to={tab.to} end={tab.end} className="side-link">
              <tab.icon aria-hidden />
              <span>{tab.label}</span>
            </NavLink>
          ))}
          <div className="side-sep" />
          <NavLink to="/search" className="side-link"><Search aria-hidden /><span>Поиск</span></NavLink>
          {signedIn && (
            <>
              <NavLink to="/invites" className="side-link">
                <Mail aria-hidden /><span>Приглашения</span>
                {showBadges && <Badge count={badges?.pendingInvites ?? 0} />}
              </NavLink>
              <NavLink to="/chats" className="side-link">
                <MessageCircle aria-hidden /><span>Чаты</span>
                {showBadges && <Badge count={badges?.unreadChats ?? 0} />}
              </NavLink>
              <NavLink to="/notifications" className="side-link">
                <Bell aria-hidden /><span>Уведомления</span>
                {showBadges && <Badge count={badges?.unreadNotifications ?? 0} />}
              </NavLink>
            </>
          )}
          {me?.role === 'MODERATOR' && (
            <NavLink to="/admin" className="side-link"><ShieldCheck aria-hidden /><span>Модерация</span></NavLink>
          )}
        </nav>
        {signedIn && me ? (
          <Link to="/profile" className="side-me">
            <Avatar name={me.profile.displayName ?? me.profile.nickname} avatarKey={me.profile.avatarKey} size={36} />
            <span>
              <b>{me.profile.displayName ?? me.profile.nickname}</b>
              <small>@{me.profile.nickname}</small>
            </span>
          </Link>
        ) : !signedIn ? (
          <Link to="/login" className="btn btn-primary btn-block">Войти</Link>
        ) : null}
      </aside>

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

export function PageHeader({ title, back, actions }: {
  title: ReactNode;
  back?: boolean | string;
  actions?: ReactNode;
}) {
  const navigate = useNavigate();
  return (
    <header className="page-header">
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
