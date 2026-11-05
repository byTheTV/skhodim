import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Bell, ChevronRight, LogOut, Mail, MessageCircle, Moon, Pencil, ShieldCheck, Sparkles, Users } from 'lucide-react';
import { api, type TasteSlice } from '../api';
import { useAuth } from '../auth';
import { PageHeader, useTheme, type Theme } from '../layout';
import { Avatar, Badge, Button, Chip, EmptyState, ErrorBlock, Skeleton, STICKER_COUNT, Toggle, useToast } from '../ui';

function shares(slices: TasteSlice[], kind: TasteSlice['kind'], limit: number) {
  const list = slices.filter((s) => s.kind === kind);
  const total = list.reduce((sum, s) => sum + s.weight, 0);
  return list.slice(0, limit).map((s) => ({
    name: s.itemName,
    percent: total ? Math.round((s.weight / total) * 100) : 0,
  }));
}

const THEMES: { value: Theme; label: string }[] = [
  { value: 'auto', label: 'Как в системе' },
  { value: 'light', label: 'Светлая' },
  { value: 'dark', label: 'Тёмная' },
];

export function Profile() {
  const { me, signOut } = useAuth();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const [theme, setTheme] = useTheme();
  const taste = useQuery({ queryKey: ['taste'], queryFn: api.taste });
  const badges = useQuery({ queryKey: ['badges'], queryFn: api.badges });

  const notifications = useMutation({
    mutationFn: (enabled: boolean) => {
      const p = me!.profile;
      return api.updateProfile({
        nickname: p.nickname,
        displayName: p.displayName,
        avatarKey: p.avatarKey,
        metro: p.metro,
        bio: p.bio,
        notificationsEnabled: enabled,
      });
    },
    onSuccess: (data) => client.setQueryData(['me'], data),
    onError: (e: Error) => toast(e.message, 'error'),
  });

  if (!me) return <div className="page"><PageHeader title="Профиль" /><Skeleton rows={4} /></div>;
  const p = me.profile;
  const name = p.displayName ?? p.nickname;
  const top = shares(taste.data ?? [], 'category', 3);
  const metros = shares(taste.data ?? [], 'metro', 2);

  return (
    <div className="page">
      <PageHeader title="Профиль" />
      <section className="person-head">
        <Avatar name={name} avatarKey={p.avatarKey} size={72} />
        <div>
          <h2>{name}</h2>
          <p className="muted">@{p.nickname}</p>
          <p className="muted">{p.metro ? `м. ${p.metro}` : 'Санкт-Петербург'}</p>
        </div>
      </section>
      {p.bio && <p className="bio">{p.bio}</p>}
      <div className="chips">{me.interestCategories.map((c) => <Chip key={c.id}>{c.name}</Chip>)}</div>

      <Link to="/profile/taste" className="plate-card plate-pad">
        <div className="section-head">
          <h2>Пластинка вкуса</h2>
          <span className="link-btn link-strong">Открыть <ArrowRight aria-hidden /></span>
        </div>
        {top.length > 0 ? (
          <>
            <p className="line">{top.map((t) => `${t.name} · ${t.percent}%`).join('   ')}</p>
            {metros.length > 0 && <p className="muted">Районы: {metros.map((m) => m.name).join(', ')}</p>}
            <p className="muted">Помогает находить события и людей с близкими интересами.</p>
          </>
        ) : <p className="muted">Соберётся сама, когда вы отметите несколько событий.</p>}
      </Link>

      <nav className="menu" aria-label="Разделы профиля">
        <Link to="/invites" className="menu-row">
          <Mail aria-hidden /><span>Приглашения</span>
          <Badge count={badges.data?.pendingInvites ?? 0} /><ChevronRight className="row-arrow" aria-hidden />
        </Link>
        <Link to="/chats" className="menu-row">
          <MessageCircle aria-hidden /><span>Чаты</span>
          <Badge count={badges.data?.unreadChats ?? 0} /><ChevronRight className="row-arrow" aria-hidden />
        </Link>
        <div className="menu-row">
          <Link to="/notifications" className="menu-link">
            <Bell aria-hidden /><span>Уведомления</span>
            <Badge count={p.notificationsEnabled ? (badges.data?.unreadNotifications ?? 0) : 0} />
          </Link>
          <Toggle
            label="Показывать счётчики уведомлений"
            checked={p.notificationsEnabled}
            disabled={notifications.isPending}
            onChange={(v) => notifications.mutate(v)}
          />
        </div>
        <label className="menu-row">
          <Moon aria-hidden /><span>Тема</span>
          <select aria-label="Тема оформления" value={theme} onChange={(e) => setTheme(e.target.value as Theme)}>
            {THEMES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </label>
        <Link to="/profile/edit" className="menu-row">
          <Pencil aria-hidden /><span>Редактировать профиль</span><ChevronRight className="row-arrow" aria-hidden />
        </Link>
        {me.role === 'MODERATOR' && (
          <Link to="/admin" className="menu-row">
            <ShieldCheck aria-hidden /><span>Модерация и импорт</span><ChevronRight className="row-arrow" aria-hidden />
          </Link>
        )}
        <button
          type="button"
          className="menu-row"
          onClick={() => {
            signOut();
            navigate('/');
          }}
        >
          <LogOut aria-hidden /><span>Выйти</span><ChevronRight className="row-arrow" aria-hidden />
        </button>
      </nav>
    </div>
  );
}

export function EditProfile() {
  const { me } = useAuth();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const categories = useQuery({ queryKey: ['categories'], queryFn: api.categories, staleTime: 3_600_000 });
  const metros = useQuery({ queryKey: ['metros'], queryFn: api.metros });
  const [form, setForm] = useState({
    displayName: '', nickname: '', bio: '', metro: '', avatarKey: 'sticker:1', interests: [] as number[],
  });

  useEffect(() => {
    if (!me) return;
    const p = me.profile;
    setForm({
      displayName: p.displayName ?? '',
      nickname: p.nickname,
      bio: p.bio ?? '',
      metro: p.metro ?? '',
      avatarKey: p.avatarKey ?? 'sticker:1',
      interests: me.interests,
    });
  }, [me]);

  const valid = form.nickname.trim() && form.interests.length >= 3 && form.interests.length <= 5;
  const save = useMutation({
    mutationFn: () => api.updateProfile({
      nickname: form.nickname.trim(),
      displayName: form.displayName.trim() || null,
      avatarKey: form.avatarKey,
      metro: form.metro || null,
      bio: form.bio.trim() || null,
      categoryIds: form.interests,
    }),
    onSuccess: (data) => {
      client.setQueryData(['me'], data);
      toast('Профиль сохранён');
      navigate('/profile');
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (valid) save.mutate();
  };
  const toggleInterest = (id: number) => setForm((f) => {
    if (f.interests.includes(id)) return { ...f, interests: f.interests.filter((x) => x !== id) };
    return f.interests.length >= 5 ? f : { ...f, interests: [...f.interests, id] };
  });
  const metroOptions = useMemo(() => {
    const names = new Set((metros.data ?? []).map((m) => m.metro));
    if (form.metro) names.add(form.metro);
    return [...names].sort((a, b) => a.localeCompare(b, 'ru'));
  }, [metros.data, form.metro]);

  if (!me) return <div className="page"><PageHeader title="Редактировать профиль" back /><Skeleton rows={4} /></div>;

  return (
    <form className="page" onSubmit={submit}>
      <PageHeader title="Редактировать профиль" back="/profile" />
      <section>
        <h3 className="field-title">Цвет аватара</h3>
        <div className="sticker-grid" role="radiogroup" aria-label="Цвет аватара">
          {Array.from({ length: STICKER_COUNT }, (_, i) => `sticker:${i + 1}`).map((key) => (
            <button
              key={key}
              type="button"
              role="radio"
              aria-checked={form.avatarKey === key}
              aria-label={`Цвет ${key.split(':')[1]}`}
              className={form.avatarKey === key ? 'sticker sticker-on' : 'sticker'}
              onClick={() => setForm({ ...form, avatarKey: key })}
            >
              <Avatar name={form.displayName || form.nickname || '?'} avatarKey={key} size={44} />
            </button>
          ))}
        </div>
      </section>
      <label className="field">
        <span>Имя</span>
        <input maxLength={60} value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
      </label>
      <label className="field">
        <span>Ник</span>
        <input required maxLength={40} value={form.nickname} onChange={(e) => setForm({ ...form, nickname: e.target.value })} />
      </label>
      <label className="field">
        <span>Коротко о себе <small className="muted">{form.bio.length}/160</small></span>
        <textarea rows={3} maxLength={160} value={form.bio} onChange={(e) => setForm({ ...form, bio: e.target.value })} />
      </label>
      <label className="field">
        <span>Любимое метро</span>
        <select value={form.metro} onChange={(e) => setForm({ ...form, metro: e.target.value })}>
          <option value="">Не указано</option>
          {metroOptions.map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
      </label>
      <section>
        <h3 className="field-title">Интересы <small className="muted">выбрано {form.interests.length} из 3–5</small></h3>
        <div className="chips">
          {categories.data?.map((c) => (
            <Chip key={c.id} active={form.interests.includes(c.id)} onClick={() => toggleInterest(c.id)}>{c.name}</Chip>
          ))}
        </div>
      </section>
      <Button type="submit" variant="primary" block loading={save.isPending} disabled={!valid}>Сохранить</Button>
    </form>
  );
}

function ShareBars({ title, items }: { title: string; items: { name: string; percent: number }[] }) {
  if (!items.length) return null;
  return (
    <section className="block">
      <h2>{title}</h2>
      <div className="bars">
        {items.map((item) => (
          <div key={item.name} className="bar-row">
            <div className="bar-label"><span>{item.name}</span><b>{item.percent}%</b></div>
            <div className="bar-track" title={`${item.name}: ${item.percent}%`}>
              <div className="bar-fill" style={{ width: `${item.percent}%` }} />
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

export function Taste() {
  const { me } = useAuth();
  const taste = useQuery({ queryKey: ['taste'], queryFn: api.taste });
  const people = useQuery({ queryKey: ['people'], queryFn: () => api.suggested(1) });
  const slices = taste.data ?? [];
  const cats = shares(slices, 'category', 5);
  const metros = shares(slices, 'metro', 4);
  const places = shares(slices, 'place', 4);
  const twin = people.data?.[0];
  const name = me ? (me.profile.displayName ?? me.profile.nickname) : '';
  return (
    <div className="page">
      <PageHeader title="Пластинка вкуса" back />
      {taste.isLoading && <Skeleton rows={3} />}
      {taste.isError && <ErrorBlock error={taste.error} retry={() => taste.refetch()} />}
      {taste.data && cats.length === 0 && (
        <EmptyState
          icon={<Sparkles />}
          title="Пластинка ещё пишется"
          text="Лайкните несколько событий, и система сама соберёт ваш портрет интересов."
          action={<Link to="/" className="btn btn-primary btn-block">К свайпу</Link>}
        />
      )}
      {cats.length > 0 && (
        <>
          <div className="person-row person-plain">
            <Avatar name={name} avatarKey={me?.profile.avatarKey} size={48} />
            <div>
              <b>Ваш вкус{me ? ` · @${me.profile.nickname}` : ''}</b>
              <p className="muted">На основе ваших лайков и планов за 90 дней</p>
            </div>
          </div>
          <p className="bio">Чаще всего вы выбираете: {cats.slice(0, 3).map((c) => c.name.toLowerCase()).join(', ')}.</p>
          <ShareBars title="Что вам нравится" items={cats} />
          <hr className="divider" />
          <ShareBars title="Где любите бывать" items={metros} />
          <ShareBars title="Любимые площадки" items={places} />
          <div className="note">
            <b>От интересов к компании</b>
            <p>
              Сравниваем категории, станции и общие события.
              {twin ? ` Так находим «двойников вкуса». С ${twin.displayName} совпадение ${twin.similarity}%.` : ''}
            </p>
          </div>
          <Link to="/social" className="btn btn-secondary btn-block"><Users aria-hidden /> К подходящим людям</Link>
        </>
      )}
    </div>
  );
}
