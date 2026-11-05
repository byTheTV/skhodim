import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, session, type AuthResponse, type MyProfile } from './api';

type AuthState = {
  signedIn: boolean;
  me: MyProfile | undefined;
  loading: boolean;
  signIn: (auth: AuthResponse) => void;
  signOut: () => void;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const [signedIn, setSignedIn] = useState(() => Boolean(session.token));

  const signOut = useCallback(() => {
    session.set(null);
    setSignedIn(false);
    client.clear();
  }, [client]);

  const signIn = useCallback((auth: AuthResponse) => {
    session.set(auth.token);
    client.clear();
    setSignedIn(true);
  }, [client]);

  useEffect(() => {
    session.onExpired(signOut);
  }, [signOut]);

  const me = useQuery({ queryKey: ['me'], queryFn: api.profile, enabled: signedIn });

  const value = useMemo<AuthState>(() => ({
    signedIn,
    me: me.data,
    loading: signedIn && me.isLoading,
    signIn,
    signOut,
  }), [signedIn, me.data, me.isLoading, signIn, signOut]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth outside AuthProvider');
  return value;
}

/** Закрытый экран: гость попадает на вход и потом возвращается обратно. */
export function RequireAuth({ children, moderator }: { children: ReactNode; moderator?: boolean }) {
  const { signedIn, me, loading } = useAuth();
  const location = useLocation();
  if (!signedIn) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }
  if (moderator && !loading && me?.role !== 'MODERATOR') {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

export function nextPath(search: string): string {
  const next = new URLSearchParams(search).get('next');
  // Только внутренние адреса: иначе ссылка могла бы увести на чужой сайт после входа.
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
}
