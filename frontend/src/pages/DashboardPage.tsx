import { Link } from 'react-router-dom';
import { useDashboard, useIntegrity } from '../api/hooks';
import type { PaymentStatus } from '../api/types';
import { AccountLink, useAccountDirectory } from '../components/AccountName';
import {
  Card,
  EmptyState,
  ErrorAlert,
  Loading,
  Money,
  PageHeader,
  Stat,
  StatusBadge,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime, humanize } from '../lib/format';

export function DashboardPage() {
  const { data, isLoading, error } = useDashboard();
  const integrity = useIntegrity();
  const directory = useAccountDirectory();

  if (isLoading) {
    return <Loading />;
  }
  if (error || !data) {
    return <ErrorAlert error={error} />;
  }

  return (
    <>
      <PageHeader
        title="Overview"
        description="Balances are derived from immutable, balanced ledger entries."
      />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <Stat label="Customer available" value={<Money minor={data.customerAvailableMinor} />} />
        <Stat
          label="Customer reserved"
          value={<Money minor={data.customerReservedMinor} />}
          hint="Open authorizations"
        />
        <Stat label="Merchant available" value={<Money minor={data.merchantAvailableMinor} />} />
        <Stat
          label="Dispute holds"
          value={<Money minor={data.merchantDisputeHoldMinor} />}
          hint={`${data.openDisputes} open disputes`}
        />
        <Stat
          label="External clearing"
          value={<Money minor={data.externalClearingMinor} />}
          hint="Simulated bank deposits"
        />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card title="Payments by status">
          <ul className="space-y-2 text-sm">
            {(Object.entries(data.paymentsByStatus) as [PaymentStatus, number][])
              .filter(([, count]) => count > 0)
              .map(([status, count]) => (
                <li key={status} className="flex items-center justify-between">
                  <Link to={`/payments?status=${status}`} className="hover:underline">
                    <StatusBadge status={status} />
                  </Link>
                  <span className="font-mono">{count}</span>
                </li>
              ))}
          </ul>
        </Card>

        <Card title="Ledger integrity" className="lg:col-span-2">
          {integrity.isLoading && <Loading label="Verifying ledger…" />}
          <ErrorAlert error={integrity.error} />
          {integrity.data && (
            <div className="space-y-3 text-sm">
              <p
                className={`flex items-center gap-2 font-medium ${integrity.data.healthy ? 'text-emerald-700' : 'text-rose-700'}`}
              >
                <span aria-hidden="true">{integrity.data.healthy ? '✔' : '✖'}</span>
                {integrity.data.healthy ? 'All invariants hold' : 'Invariant violation detected'}
              </p>
              <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-slate-600 sm:grid-cols-3">
                <div>
                  <dt className="text-xs uppercase text-slate-400">Journals</dt>
                  <dd className="font-mono">{integrity.data.transactionCount}</dd>
                </div>
                <div>
                  <dt className="text-xs uppercase text-slate-400">Entries</dt>
                  <dd className="font-mono">{integrity.data.entryCount}</dd>
                </div>
                <div>
                  <dt className="text-xs uppercase text-slate-400">Unbalanced journals</dt>
                  <dd className="font-mono">{integrity.data.unbalancedTransactions}</dd>
                </div>
                <div>
                  <dt className="text-xs uppercase text-slate-400">Balance drift</dt>
                  <dd className="font-mono">{integrity.data.accountsWithBalanceDrift}</dd>
                </div>
                <div>
                  <dt className="text-xs uppercase text-slate-400">Clearing asset</dt>
                  <dd>
                    <Money minor={integrity.data.clearingBalanceMinor} />
                  </dd>
                </div>
                <div>
                  <dt className="text-xs uppercase text-slate-400">Total liabilities</dt>
                  <dd>
                    <Money minor={integrity.data.totalLiabilitiesMinor} />
                  </dd>
                </div>
              </dl>
              <p className="text-xs text-slate-400">Checked {formatDateTime(integrity.data.checkedAt)}</p>
            </div>
          )}
        </Card>
      </div>

      <Card
        title="Recent payments"
        className="mt-6"
        actions={
          <Link to="/payments" className="text-sm text-indigo-600 hover:underline">
            View all
          </Link>
        }
      >
        {data.recentPayments.length === 0 ? (
          <EmptyState title="No payments yet" />
        ) : (
          <Table caption="Recent payments">
            <thead>
              <tr>
                <Th>Reference</Th>
                <Th>Customer</Th>
                <Th>Merchant</Th>
                <Th>Status</Th>
                <Th align="right">Authorized</Th>
                <Th>Created</Th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.recentPayments.map((p) => (
                <tr key={p.id}>
                  <Td>
                    <Link to={`/payments/${p.id}`} className="font-medium text-indigo-600 hover:underline">
                      {p.reference ?? humanize('payment')}
                    </Link>
                  </Td>
                  <Td>
                    <AccountLink id={p.customerAccountId} directory={directory} />
                  </Td>
                  <Td>
                    <AccountLink id={p.merchantAccountId} directory={directory} />
                  </Td>
                  <Td>
                    <StatusBadge status={p.status} />
                  </Td>
                  <Td align="right">
                    <Money minor={p.authorizedAmountMinor} />
                  </Td>
                  <Td className="text-slate-500">{formatDateTime(p.createdAt)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </>
  );
}
