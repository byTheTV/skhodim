import { Link } from 'react-router-dom';
import { Compass } from 'lucide-react';
import { EmptyState } from '../ui';

export function NotFound() {
  return (
    <EmptyState
      icon={<Compass />}
      title="Здесь ничего нет"
      text="Такой страницы не существует. Возможно, ссылка устарела."
      action={<Link className="btn btn-primary" to="/">На главную</Link>}
    />
  );
}
