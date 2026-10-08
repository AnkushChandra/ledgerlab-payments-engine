import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useDisputes } from '../api/hooks';
import type { DisputeStatus } from '../api/types';
import { AccountLink, useAccountDirectory } from '../components/AccountName';
import {
  Card,
  EmptyState,
  ErrorAlert,
  Loading,
  Money,
  PageHeader,
  Pagination,
  SelectField,
  StatusBadge,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime } from '../lib/format';

export function DisputesPage() {
  const [status, setStatus] = useState<DisputeStatus | ''>('OPEN');
  const [page, setPage] = useState(0);
  const { data, isLoading, error } = useDisputes({ status: status || undefined, page });
  const directory = useAccountDirectory();

  return (
    <>
      <PageHeader
        title="Disputes"
        description="Open disputes freeze merchant funds until they are won or lost."
      />
      <Card>
        <div className="mb-4 max-w-xs">
          <SelectField
            label="Status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as DisputeStatus | '');
              setPage(0);
            }}
          >
            <option value="">All</option>
            <option value="OPEN">Open</option>
            <option value="WON">Won</option>
            <option value="LOST">Lost</option>
          </SelectField>
        </div>
        {isLoading ? (
          <Loading />
        ) : error ? (
          <ErrorAlert error={error} />
        ) : data && data.items.length === 0 ? (
          <EmptyState title="No disputes in this queue" />
        ) : (
          data && (
            <>
              <Table caption="Disputes">
                <thead>
                  <tr>
                    <Th>Opened</Th>
                    <Th>Payment</Th>
                    <Th>Merchant</Th>
                    <Th>Reason</Th>
                    <Th>Status</Th>
                    <Th align="right">Amount</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.items.map((d) => (
                    <tr key={d.id}>
                      <Td>{formatDateTime(d.openedAt)}</Td>
                      <Td>
                        <Link
                          to={`/disputes/${d.id}`}
                          className="font-medium text-indigo-600 hover:underline"
                        >
                          {d.payment.reference ?? d.payment.id.slice(0, 8)}
                        </Link>
                      </Td>
                      <Td>
                        <AccountLink id={d.payment.merchantAccountId} directory={directory} />
                      </Td>
                      <Td className="max-w-xs truncate">{d.reason}</Td>
                      <Td>
                        <StatusBadge status={d.status} />
                      </Td>
                      <Td align="right">
                        <Money minor={d.amountMinor} />
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                totalItems={data.totalItems}
                onChange={setPage}
              />
            </>
          )
        )}
      </Card>
    </>
  );
}
