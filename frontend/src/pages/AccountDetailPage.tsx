import { useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { useAccount, useAccountEntries, useIdempotentMutation, usePatch } from '../api/hooks';
import type { Account, AccountStatus, Deposit } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { LedgerTransactionDialog, MoneyField, useIdempotencyKey, useMoneyInput } from '../components/forms';
import {
  Button,
  Card,
  Dialog,
  EmptyState,
  ErrorAlert,
  Field,
  Loading,
  Money,
  PageHeader,
  Pagination,
  Stat,
  StatusBadge,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime, humanize } from '../lib/format';

export function AccountDetailPage() {
  const { accountId = '' } = useParams();
  const { can } = useAuth();
  const [page, setPage] = useState(0);
  const [journal, setJournal] = useState<string | null>(null);
  const [depositing, setDepositing] = useState(false);
  const account = useAccount(accountId);
  const entries = useAccountEntries(accountId, page);
  const changeStatus = usePatch<{ status: AccountStatus }, Account>(`/accounts/${accountId}`);

  if (account.isLoading) {
    return <Loading />;
  }
  if (account.error || !account.data) {
    return <ErrorAlert error={account.error} />;
  }
  const a = account.data;
  const heldLabel = a.type === 'CUSTOMER' ? 'Reserved by authorizations' : 'Held by disputes';

  return (
    <>
      <PageHeader
        title={a.name}
        description={
          <span className="flex items-center gap-2">
            <span className="font-mono">{a.reference}</span> ·{' '}
            {a.type === 'CUSTOMER' ? 'Customer' : 'Merchant'} · <StatusBadge status={a.status} />
          </span>
        }
        actions={
          <>
            {can('OPERATIONS') && (
              <Button onClick={() => setDepositing(true)} disabled={a.status !== 'ACTIVE'}>
                Simulate deposit
              </Button>
            )}
            {can('ADMIN') && a.status === 'ACTIVE' && (
              <Button
                variant="secondary"
                busy={changeStatus.isPending}
                onClick={() => changeStatus.mutate({ status: 'FROZEN' })}
              >
                Freeze
              </Button>
            )}
            {can('ADMIN') && a.status === 'FROZEN' && (
              <Button
                variant="secondary"
                busy={changeStatus.isPending}
                onClick={() => changeStatus.mutate({ status: 'ACTIVE' })}
              >
                Unfreeze
              </Button>
            )}
            {can('ADMIN') && a.status !== 'CLOSED' && (
              <Button
                variant="danger"
                busy={changeStatus.isPending}
                onClick={() => changeStatus.mutate({ status: 'CLOSED' })}
              >
                Close
              </Button>
            )}
          </>
        }
      />
      <ErrorAlert error={changeStatus.error} title="Status change failed" />

      <div className="grid gap-4 sm:grid-cols-2">
        <Stat label="Available" value={<Money minor={a.availableMinor} />} />
        <Stat label={heldLabel} value={<Money minor={a.heldMinor} />} />
      </div>

      <Card title="Ledger entries" className="mt-6">
        {entries.isLoading ? (
          <Loading />
        ) : entries.error ? (
          <ErrorAlert error={entries.error} />
        ) : entries.data && entries.data.items.length === 0 ? (
          <EmptyState title="No ledger activity yet" />
        ) : (
          entries.data && (
            <>
              <Table caption="Ledger entries">
                <thead>
                  <tr>
                    <Th>Posted</Th>
                    <Th>Description</Th>
                    <Th>Ledger account</Th>
                    <Th align="right">Change</Th>
                    <Th align="right">Balance after</Th>
                    <Th />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {entries.data.items.map((e) => (
                    <tr key={e.id}>
                      <Td className="text-slate-500">{formatDateTime(e.createdAt)}</Td>
                      <Td>
                        <p>{e.description}</p>
                        <p className="text-xs text-slate-500">{humanize(e.transactionType)}</p>
                      </Td>
                      <Td>{humanize(e.purpose)}</Td>
                      <Td align="right" className={e.effectMinor >= 0 ? 'text-emerald-700' : 'text-rose-700'}>
                        <Money minor={e.effectMinor} />
                      </Td>
                      <Td align="right">
                        <Money minor={e.balanceAfterMinor} />
                      </Td>
                      <Td>
                        <Button variant="ghost" onClick={() => setJournal(e.ledgerTransactionId)}>
                          Journal
                        </Button>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination
                page={entries.data.page}
                totalPages={entries.data.totalPages}
                totalItems={entries.data.totalItems}
                onChange={setPage}
              />
            </>
          )
        )}
      </Card>

      <LedgerTransactionDialog transactionId={journal} onClose={() => setJournal(null)} />
      <DepositDialog accountId={a.id} open={depositing} onClose={() => setDepositing(false)} />
    </>
  );
}

export function DepositDialog({
  accountId,
  open,
  onClose,
}: {
  accountId: string;
  open: boolean;
  onClose: () => void;
}) {
  const money = useMoneyInput();
  const [memo, setMemo] = useState('');
  const [key, rotateKey] = useIdempotencyKey();
  const deposit = useIdempotentMutation<{ accountId: string; amountMinor: number; memo: string }, Deposit>(
    '/deposits',
  );

  function submit(event: FormEvent) {
    event.preventDefault();
    if (money.minor === null) {
      return;
    }
    deposit.mutate(
      { body: { accountId, amountMinor: money.minor, memo }, idempotencyKey: key },
      {
        onSuccess: () => {
          rotateKey();
          money.reset();
          setMemo('');
          onClose();
        },
      },
    );
  }

  return (
    <Dialog open={open} title="Simulate bank deposit" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-sm text-slate-500">
          Credits available funds against the external clearing account. This is the only operation that
          creates money.
        </p>
        <MoneyField label="Amount" money={money} />
        <Field label="Memo" maxLength={255} value={memo} onChange={(e) => setMemo(e.target.value)} />
        <ErrorAlert error={deposit.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={deposit.isPending} disabled={money.minor === null}>
            Deposit
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
