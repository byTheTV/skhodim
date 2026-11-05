import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { ArrowLeft } from 'lucide-react';
import { api } from '../api';
import { nextPath, useAuth } from '../auth';
import { Button, Chip, useToast } from '../ui';

function AuthLayout({ title, subtitle, children, footer }: {
  title: string;
  subtitle: string;
  children: ReactNode;
  footer: ReactNode;
}) {
  return (
    <div className="auth">
      <aside className="auth-art" aria-hidden>
        <span className="blob blob-a" />
        <span className="blob blob-b" />
        <span className="blob blob-c" />
        <h2>Куда сходить и <em>с кем</em></h2>
        <p>Смахивай афишу Петербурга, отмечай, куда хочешь пойти, и находи компанию на тот же вечер.</p>
      </aside>
      <main className="auth-main">
        <Link to="/" className="link-btn"><ArrowLeft aria-hidden /> К афише</Link>
        <div className="logo auth-brand">сходим<span>!</span></div>
        <h1>{title}</h1>
        <p className="muted">{subtitle}</p>
        {children}
        <p className="auth-footer">{footer}</p>
      </main>
    </div>
  );
}

const DEMO = [
  { label: 'Демо: участник', email: 'demo1@example.org' },
  { label: 'Демо: модератор', email: 'demo100@example.org' },
];
const DEMO_PASSWORD = 'Demo12345!';

export function Login() {
  const { signedIn, signIn } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const target = nextPath(location.search);
  const login = useMutation({
    mutationFn: (c: { email: string; password: string }) => api.login(c.email, c.password),
    onSuccess: (auth) => {
      signIn(auth);
      navigate(target, { replace: true });
    },
  });
  if (signedIn) return <Navigate to={target} replace />;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    login.mutate({ email: email.trim(), password });
  };
  return (
    <AuthLayout
      title="Вход"
      subtitle="Войдите, чтобы лайкать события и искать компанию."
      footer={<>Ещё нет аккаунта? <Link to={`/register${location.search}`}>Зарегистрироваться</Link></>}
    >
      <form className="stack" onSubmit={submit}>
        <label className="field">
          <span>Email</span>
          <input type="email" required autoComplete="email" placeholder="name@example.org" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          <span>Пароль</span>
          <input type="password" required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {login.isError && <p className="form-error" role="alert">{(login.error as Error).message}</p>}
        <Button type="submit" variant="primary" block loading={login.isPending}>Войти</Button>
      </form>
      <div className="demo-box">
        <p className="muted small">Учебный проект: можно войти без регистрации</p>
        <div className="stack-row">
          {DEMO.map((d) => (
            <Button key={d.email} onClick={() => login.mutate({ email: d.email, password: DEMO_PASSWORD })}>{d.label}</Button>
          ))}
        </div>
      </div>
    </AuthLayout>
  );
}

export function Register() {
  const { signedIn, signIn } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const toast = useToast();
  const [step, setStep] = useState<1 | 2>(1);
  const [form, setForm] = useState({ email: '', password: '', nickname: '', displayName: '' });
  const [interests, setInterests] = useState<number[]>([]);
  const categories = useQuery({ queryKey: ['categories'], queryFn: api.categories, staleTime: 3_600_000 });
  const target = nextPath(location.search);
  const register = useMutation({
    mutationFn: () => api.register({
      email: form.email.trim(),
      password: form.password,
      nickname: form.nickname.trim(),
      displayName: form.displayName.trim(),
      categoryIds: interests,
    }),
    onSuccess: (auth) => {
      signIn(auth);
      toast('Добро пожаловать!');
      navigate(target, { replace: true });
    },
  });
  if (signedIn) return <Navigate to={target} replace />;

  const toggle = (id: number) => setInterests((list) => {
    if (list.includes(id)) return list.filter((x) => x !== id);
    return list.length >= 5 ? list : [...list, id];
  });

  if (step === 1) {
    return (
      <AuthLayout
        title="Регистрация"
        subtitle="Шаг 1 из 2. Без анкеты: только то, что нужно для компании на вечер."
        footer={<>Уже есть аккаунт? <Link to={`/login${location.search}`}>Войти</Link></>}
      >
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            setStep(2);
          }}
        >
          <label className="field">
            <span>Email</span>
            <input type="email" required autoComplete="email" placeholder="name@example.org" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          </label>
          <label className="field">
            <span>Пароль <small className="muted">от 8 символов</small></span>
            <input type="password" required minLength={8} autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
          </label>
          <label className="field">
            <span>Имя</span>
            <input maxLength={60} placeholder="Никита" autoComplete="given-name" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
          </label>
          <label className="field">
            <span>Ник</span>
            <input required maxLength={40} placeholder="nikita_spb" autoComplete="username" value={form.nickname} onChange={(e) => setForm({ ...form, nickname: e.target.value })} />
          </label>
          <Button type="submit" variant="primary" block>Дальше</Button>
        </form>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Что вам интересно?"
      subtitle="Шаг 2 из 2. Выберите от 3 до 5 тем, чтобы афиша сразу стала вашей."
      footer={<button type="button" className="link-btn" onClick={() => setStep(1)}><ArrowLeft aria-hidden /> Назад</button>}
    >
      <div className="chips chips-big">
        {categories.data?.map((c) => (
          <Chip key={c.id} active={interests.includes(c.id)} onClick={() => toggle(c.id)}>{c.name}</Chip>
        ))}
      </div>
      <p className="muted small">Выбрано: {interests.length}</p>
      {register.isError && <p className="form-error" role="alert">{(register.error as Error).message}</p>}
      <Button
        variant="primary"
        block
        loading={register.isPending}
        disabled={interests.length < 3 || interests.length > 5}
        onClick={() => register.mutate()}
      >
        Готово
      </Button>
    </AuthLayout>
  );
}
