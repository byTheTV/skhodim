import { useRef, useState, type ChangeEvent } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, CloudDownload, Copy, ShieldCheck, Upload, XCircle } from 'lucide-react';
import { api, type DuplicateSide, type ImportResult, type ModerationItem } from '../api';
import { relativeTime, shortDate } from '../format';
import { PageHeader } from '../layout';
import { Button, Cover, EmptyState, ErrorBlock, Skeleton, Tabs, useToast } from '../ui';

const TARGET_LABEL: Record<ModerationItem['targetType'], string> = {
  event: 'Событие',
  user: 'Пользователь',
  feedback: 'Комментарий',
};
const TARGET_ACTION: Record<ModerationItem['targetType'], { action: string; label: string }> = {
  event: { action: 'HIDE_EVENT', label: 'Скрыть событие' },
  user: { action: 'BAN_USER', label: 'Заблокировать' },
  feedback: { action: 'HIDE_FEEDBACK', label: 'Скрыть комментарий' },
};

function Moderation() {
  const client = useQueryClient();
  const toast = useToast();
  const queue = useQuery({ queryKey: ['moderation'], queryFn: api.moderation });
  const act = useMutation({
    mutationFn: ({ id, action }: { id: number; action: string }) => api.moderate(id, action),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['moderation'] });
      toast('Жалоба рассмотрена');
    },
    onError: (e: Error) => toast(e.message, 'error'),
  });
  if (queue.isLoading) return <Skeleton />;
  if (queue.isError) return <ErrorBlock error={queue.error} retry={() => queue.refetch()} />;
  if (!queue.data?.length) {
    return <EmptyState icon={<ShieldCheck />} title="Очередь пуста" text="Новых жалоб нет." />;
  }
  return (
    <div className="invite-grid">
      {queue.data.map((r) => {
        const main = TARGET_ACTION[r.targetType];
        return (
          <article key={r.id} className="invite-card">
            <div className="comment-head">
              <b>{TARGET_LABEL[r.targetType]}</b>
              <span className="muted">{relativeTime(r.createdAt)}</span>
            </div>
            <p>
              {r.targetEventId
                ? <Link to={`/events/${r.targetEventId}`}>{r.targetLabel}</Link>
                : r.targetType === 'user'
                  ? <Link to={`/users/${r.targetId}`}>{r.targetLabel}</Link>
                  : r.targetLabel ?? 'Цель не найдена'}
            </p>
            <p className="note">{r.reason}</p>
            <p className="muted small">Автор жалобы: {r.authorName}</p>
            <div className="invite-actions">
              <Button variant="danger" block disabled={r.targetActive === false || act.isPending} onClick={() => act.mutate({ id: r.id, action: main.action })}>
                {main.label}
              </Button>
              <Button block disabled={act.isPending} onClick={() => act.mutate({ id: r.id, action: 'RESOLVE' })}>Закрыть без мер</Button>
            </div>
          </article>
        );
      })}
    </div>
  );
}

function Imports() {
  const client = useQueryClient();
  const toast = useToast();
  const file = useRef<HTMLInputElement>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const history = useQuery({ queryKey: ['imports'], queryFn: api.imports });
  const done = (r: ImportResult) => {
    setResult(r);
    client.invalidateQueries();
  };
  const kassir = useMutation({
    mutationFn: api.syncKassir,
    onSuccess: done,
    onError: (e: Error) => {
      toast(e.message, 'error');
      setResult(null);
      client.invalidateQueries({ queryKey: ['imports'] });
    },
  });
  const afisha = useMutation({
    mutationFn: api.syncAfisha,
    onSuccess: done,
    onError: (e: Error) => {
      toast(e.message, 'error');
      setResult(null);
      client.invalidateQueries({ queryKey: ['imports'] });
    },
  });
  const upload = useMutation({
    mutationFn: api.uploadSnapshot,
    onSuccess: done,
    onError: (e: Error) => {
      toast(e.message, 'error');
      client.invalidateQueries({ queryKey: ['imports'] });
    },
  });
  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const chosen = e.target.files?.[0];
    if (!chosen) return;
    upload.mutate(await chosen.text());
    e.target.value = '';
  };
  return (
    <div className="stack">
      <div className="note">
        <b>Откуда берутся события</b>
        <p>Настоящая афиша Петербурга с Кассир.ру и Афиши.ру: обновляется сама каждый день в 6:00 или по кнопке, это занимает несколько минут. Сохранённую выгрузку событий можно загрузить файлом. Повторная загрузка обновляет события и не создаёт дублей.</p>
      </div>
      <div className="stack-row">
        <Button variant="primary" loading={kassir.isPending} onClick={() => kassir.mutate()}>
          <CloudDownload aria-hidden /> Обновить афишу с Кассир.ру
        </Button>
        <Button variant="primary" loading={afisha.isPending} onClick={() => afisha.mutate()}>
          <CloudDownload aria-hidden /> Обновить афишу с Афиши.ру
        </Button>
        <Button loading={upload.isPending} onClick={() => file.current?.click()}>
          <Upload aria-hidden /> Загрузить выгрузку JSON
        </Button>
        <input ref={file} type="file" accept="application/json,.json" hidden aria-label="Файл выгрузки" onChange={onFile} />
      </div>
      {result && (
        <p className="status-line" role="status">
          <CheckCircle2 aria-hidden /> Загружено событий: {result.processed}, ошибок: {result.errors}
          {result.merged ? `, склеено дублей: ${result.merged}` : ''}
          {result.duplicates ? `, на проверку: ${result.duplicates}` : ''}
        </p>
      )}
      <h2 className="section-title">История запусков</h2>
      {history.isLoading && <Skeleton />}
      {history.isError && <ErrorBlock error={history.error} retry={() => history.refetch()} />}
      {history.data?.length === 0 && <p className="hint">Импорт ещё не запускался.</p>}
      <div className="people">
        {history.data?.map((run) => (
          <div key={run.id} className="import-row">
            {run.status === 'SUCCESS' ? <CheckCircle2 className="ok" aria-hidden /> : <XCircle className="bad" aria-hidden />}
            <div>
              <b>{run.status === 'SUCCESS' ? 'Успешно' : run.status === 'PARTIAL' ? 'Частично' : run.status === 'RUNNING' ? 'Идёт' : 'Сбой'}</b>
              <p className="muted small">
                {run.sourceCode} · {relativeTime(run.startedAt)} · событий: {run.processedCount}, ошибок: {run.errorCount}
              </p>
              {run.lastError && <p className="muted small">{run.lastError}</p>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function Side({ side, onKeep, busy }: { side: DuplicateSide; onKeep: () => void; busy: boolean }) {
  return (
    <div className="dup-side">
      <Link to={`/events/${side.eventId}`} className="dup-event">
        <Cover event={{ eventId: side.eventId, coverUrl: side.coverUrl, categories: [], title: side.title }} className="dup-cover" />
        <span>
          <b>{side.title}</b>
          <small>{side.source} · {side.place ?? 'площадка неизвестна'}</small>
          <small>{side.startsAt ? shortDate(side.startsAt) : 'сеансов нет'}</small>
        </span>
      </Link>
      <Button block disabled={busy} onClick={onKeep}>Оставить это</Button>
    </div>
  );
}

/** Пары, которые автоматически склеить не решились: модератор оставляет одно событие или помечает их разными. */
function Duplicates() {
  const client = useQueryClient();
  const toast = useToast();
  const pairs = useQuery({ queryKey: ['duplicates'], queryFn: api.duplicates });
  const settle = (message: string) => {
    client.invalidateQueries();
    toast(message);
  };
  const merge = useMutation({
    mutationFn: ({ id, keep }: { id: number; keep: number }) => api.mergeDuplicate(id, keep),
    onSuccess: () => settle('События склеены'),
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const distinct = useMutation({
    mutationFn: api.distinctDuplicate,
    onSuccess: () => settle('Пара больше не будет предлагаться'),
    onError: (e: Error) => toast(e.message, 'error'),
  });
  const busy = merge.isPending || distinct.isPending;
  if (pairs.isLoading) return <Skeleton />;
  if (pairs.isError) return <ErrorBlock error={pairs.error} retry={() => pairs.refetch()} />;
  return (
    <div className="stack">
      <div className="note">
        <b>Как склеиваются дубли</b>
        <p>После каждого импорта одинаковые события из разных источников склеиваются сами: то же время, та же площадка, то же название. Здесь пары, где совпадение неполное. При склейке лайки, планы, комментарии и жалобы переходят к оставшемуся событию.</p>
      </div>
      {!pairs.data?.length && <EmptyState icon={<Copy />} title="Сомнительных пар нет" text="Всё, что похоже на дубль наверняка, уже склеено." />}
      {pairs.data?.map((pair) => (
        <article key={pair.id} className="dup-pair">
          <p className="muted small">{pair.reason}</p>
          <div className="dup-sides">
            <Side side={pair.a} busy={busy} onKeep={() => merge.mutate({ id: pair.id, keep: pair.a.eventId })} />
            <Side side={pair.b} busy={busy} onKeep={() => merge.mutate({ id: pair.id, keep: pair.b.eventId })} />
          </div>
          <Button variant="ghost" block disabled={busy} onClick={() => distinct.mutate(pair.id)}>Это разные события</Button>
        </article>
      ))}
    </div>
  );
}

export function Admin() {
  const [tab, setTab] = useState<'reports' | 'duplicates' | 'imports'>('reports');
  return (
    <div className="page">
      <PageHeader title="Модерация" back="/profile" section />
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'reports', label: 'Жалобы' },
          { value: 'duplicates', label: 'Дубли' },
          { value: 'imports', label: 'Импорт афиши' },
        ]}
      />
      {tab === 'reports' && <Moderation />}
      {tab === 'duplicates' && <Duplicates />}
      {tab === 'imports' && <Imports />}
    </div>
  );
}
