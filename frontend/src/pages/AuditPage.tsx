import { useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuditEvents } from '../api/hooks';
import { useAuth } from '../auth/AuthContext';
import {
  Card,
  EmptyState,
  ErrorAlert,
  Field,
  Loading,
  PageHeader,
  Pagination,
  SelectField,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime, humanize } from '../lib/format';

const ACTIONS = [
  '',
  'LOGIN_SUCCEEDED',
  'LOGIN_FAILED',
  'ACCOUNT_CREATED',
  'DEPOSIT_CREATED',
  'TRANSFER_CREATED',
  'PAYMENT_AUTHORIZED',
  'PAYMENT_CAPTURED',
  'PAYMENT_VOIDED',
  'PAYMENT_REFUNDED',
  'DISPUTE_OPENED',
  'DISPUTE_RESOLVED',
  'SETTLEMENT_IMPORTED',
  'RECONCILIATION_COMPLETED',
];

export function AuditPage() {
  const { can } = useAuth();
  const [action, setAction] = useState('');
  const [targetType, setTargetType] = useState('');
  const [targetId, setTargetId] = useState('');
  const [page, setPage] = useState(0);
  const { data, isLoading, error } = useAuditEvents({
    action: action || undefined,
    targetType: targetType || undefined,
    targetId: targetId || undefined,
    page,
  });

  if (!can('OPERATIONS')) {
    return <Navigate to="/" replace />;
  }

  return (
    <>
      <PageHeader
        title="Audit trail"
        description="Append-only security and business events for this organization."
      />
      <Card>
        <div className="mb-4 grid gap-3 sm:grid-cols-3">
          <SelectField
            label="Action"
            value={action}
            onChange={(e) => {
              setAction(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All actions</option>
            {ACTIONS.filter(Boolean).map((a) => (
              <option key={a} value={a}>
                {humanize(a)}
              </option>
            ))}
          </SelectField>
          <Field
            label="Target type"
            placeholder="PAYMENT"
            value={targetType}
            onChange={(e) => {
              setTargetType(e.target.value);
              setPage(0);
            }}
          />
          <Field
            label="Target id"
            placeholder="UUID"
            value={targetId}
            onChange={(e) => {
              setTargetId(e.target.value);
              setPage(0);
            }}
          />
        </div>
        {isLoading ? (
          <Loading />
        ) : error ? (
          <ErrorAlert error={error} />
        ) : data && data.items.length === 0 ? (
          <EmptyState title="No audit events match these filters" />
        ) : (
          data && (
            <>
              <Table caption="Audit events">
                <thead>
                  <tr>
                    <Th>When</Th>
                    <Th>Action</Th>
                    <Th>Actor</Th>
                    <Th>Target</Th>
                    <Th>Correlation</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.items.map((e) => (
                    <tr key={e.id}>
                      <Td>{formatDateTime(e.occurredAt)}</Td>
                      <Td>{humanize(e.action)}</Td>
                      <Td className="text-xs">{e.actorEmail ?? '—'}</Td>
                      <Td className="font-mono text-xs">
                        {e.targetType}
                        {e.targetId ? ` ${e.targetId.slice(0, 8)}` : ''}
                      </Td>
                      <Td className="font-mono text-xs text-slate-400">{e.correlationId ?? '—'}</Td>
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
