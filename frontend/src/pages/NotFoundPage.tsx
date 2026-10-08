import { Link } from 'react-router-dom';
import { EmptyState } from '../components/ui';

export function NotFoundPage() {
  return (
    <EmptyState title="Page not found">
      <Link to="/" className="text-indigo-600 hover:underline">
        Back to overview
      </Link>
    </EmptyState>
  );
}
