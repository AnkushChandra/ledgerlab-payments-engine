import { Link } from 'react-router-dom';
import { useAccounts } from '../api/hooks';
import type { Account } from '../api/types';
import { shortId } from '../lib/format';

/** Loads up to 100 accounts once and caches them; enough for the demo-scale account lists. */
export function useAccountDirectory(): Map<string, Account> {
  const { data } = useAccounts({ size: 100, page: 0 });
  return new Map((data?.items ?? []).map((a) => [a.id, a]));
}

export function AccountLink({ id, directory }: { id: string; directory: Map<string, Account> }) {
  const account = directory.get(id);
  return (
    <Link to={`/accounts/${id}`} className="text-indigo-600 hover:underline">
      {account?.name ?? shortId(id)}
    </Link>
  );
}
