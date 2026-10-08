import { useState, type FormEvent } from 'react';
import { useAccounts, useDeposits, useIdempotentMutation, useTransfers } from '../api/hooks';
import type { Transfer } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AccountLink, useAccountDirectory } from '../components/AccountName';
import { MoneyField, useIdempotencyKey, useMoneyInput } from '../components/forms';
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
  SelectField,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime } from '../lib/format';

export function MoneyMovementPage() {
  const { can } = useAuth();
  const [depositing, setDepositing] = useState(false);
  const [transferring, setTransferring] = useState(false);
  const [depositPage, setDepositPage] = useState(0);
  const [transferPage, setTransferPage] = useState(0);
  const deposits = useDeposits({ page: depositPage });
  const transfers = useTransfers({ page: transferPage });
  const directory = useAccountDirectory();

  return (
    <>
      <PageHeader
        title="Deposits & transfers"
        description="Simulated bank deposits create money. Transfers move available funds between accounts."
        actions={
          can('OPERATIONS') && (
            <>
              <Button onClick={() => setDepositing(true)}>Simulate deposit</Button>
              <Button variant="secondary" onClick={() => setTransferring(true)}>
                Transfer
              </Button>
            </>
          )
        }
      />

      <Card title="Deposits">
        {deposits.isLoading ? (
          <Loading />
        ) : deposits.error ? (
          <ErrorAlert error={deposits.error} />
        ) : deposits.data && deposits.data.items.length === 0 ? (
          <EmptyState title="No deposits yet" />
        ) : (
          deposits.data && (
            <>
              <Table caption="Deposits">
                <thead>
                  <tr>
                    <Th>When</Th>
                    <Th>Account</Th>
                    <Th align="right">Amount</Th>
                    <Th>Memo</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {deposits.data.items.map((d) => (
                    <tr key={d.id}>
                      <Td>{formatDateTime(d.createdAt)}</Td>
                      <Td>
                        <AccountLink id={d.accountId} directory={directory} />
                      </Td>
                      <Td align="right">
                        <Money minor={d.amountMinor} />
                      </Td>
                      <Td>{d.memo ?? '—'}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination
                page={deposits.data.page}
                totalPages={deposits.data.totalPages}
                totalItems={deposits.data.totalItems}
                onChange={setDepositPage}
              />
            </>
          )
        )}
      </Card>

      <Card title="Transfers" className="mt-6">
        {transfers.isLoading ? (
          <Loading />
        ) : transfers.error ? (
          <ErrorAlert error={transfers.error} />
        ) : transfers.data && transfers.data.items.length === 0 ? (
          <EmptyState title="No transfers yet" />
        ) : (
          transfers.data && (
            <>
              <Table caption="Transfers">
                <thead>
                  <tr>
                    <Th>When</Th>
                    <Th>From</Th>
                    <Th>To</Th>
                    <Th align="right">Amount</Th>
                    <Th>Memo</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {transfers.data.items.map((t) => (
                    <tr key={t.id}>
                      <Td>{formatDateTime(t.createdAt)}</Td>
                      <Td>
                        <AccountLink id={t.sourceAccountId} directory={directory} />
                      </Td>
                      <Td>
                        <AccountLink id={t.destinationAccountId} directory={directory} />
                      </Td>
                      <Td align="right">
                        <Money minor={t.amountMinor} />
                      </Td>
                      <Td>{t.memo ?? '—'}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination
                page={transfers.data.page}
                totalPages={transfers.data.totalPages}
                totalItems={transfers.data.totalItems}
                onChange={setTransferPage}
              />
            </>
          )
        )}
      </Card>

      <QuickDepositDialog open={depositing} onClose={() => setDepositing(false)} />
      <TransferDialog open={transferring} onClose={() => setTransferring(false)} />
    </>
  );
}

function QuickDepositDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const accounts = useAccounts({ status: 'ACTIVE', size: 100 });
  const [accountId, setAccountId] = useState('');
  const [memo, setMemo] = useState('');
  const money = useMoneyInput();
  const [key, rotateKey] = useIdempotencyKey();
  const deposit = useIdempotentMutation<{ accountId: string; amountMinor: number; memo?: string }, unknown>(
    '/deposits',
  );

  function submit(event: FormEvent) {
    event.preventDefault();
    if (money.minor === null) {
      return;
    }
    deposit.mutate(
      { body: { accountId, amountMinor: money.minor, memo: memo || undefined }, idempotencyKey: key },
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
        <SelectField
          label="Account"
          required
          value={accountId}
          onChange={(e) => setAccountId(e.target.value)}
        >
          <option value="">Select an account</option>
          {accounts.data?.items.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name} ({a.reference})
            </option>
          ))}
        </SelectField>
        <MoneyField label="Amount" money={money} />
        <Field label="Memo" maxLength={255} value={memo} onChange={(e) => setMemo(e.target.value)} />
        <ErrorAlert error={deposit.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={deposit.isPending} disabled={!accountId || money.minor === null}>
            Deposit
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function TransferDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const accounts = useAccounts({ status: 'ACTIVE', size: 100 });
  const [source, setSource] = useState('');
  const [destination, setDestination] = useState('');
  const [memo, setMemo] = useState('');
  const money = useMoneyInput();
  const [key, rotateKey] = useIdempotencyKey();
  const transfer = useIdempotentMutation<
    { sourceAccountId: string; destinationAccountId: string; amountMinor: number; memo?: string },
    Transfer
  >('/transfers');

  function submit(event: FormEvent) {
    event.preventDefault();
    if (money.minor === null) {
      return;
    }
    transfer.mutate(
      {
        body: {
          sourceAccountId: source,
          destinationAccountId: destination,
          amountMinor: money.minor,
          memo: memo || undefined,
        },
        idempotencyKey: key,
      },
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
    <Dialog open={open} title="Transfer funds" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <SelectField label="From" required value={source} onChange={(e) => setSource(e.target.value)}>
          <option value="">Select source</option>
          {accounts.data?.items.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name} ({a.reference})
            </option>
          ))}
        </SelectField>
        <SelectField label="To" required value={destination} onChange={(e) => setDestination(e.target.value)}>
          <option value="">Select destination</option>
          {accounts.data?.items
            .filter((a) => a.id !== source)
            .map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.reference})
              </option>
            ))}
        </SelectField>
        <MoneyField label="Amount" money={money} />
        <Field label="Memo" maxLength={255} value={memo} onChange={(e) => setMemo(e.target.value)} />
        <ErrorAlert error={transfer.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            busy={transfer.isPending}
            disabled={!source || !destination || money.minor === null}
          >
            Transfer
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
