import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAccounts, useIdempotentMutation, usePayments } from '../api/hooks';
import type { Payment, PaymentStatus } from '../api/types';
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
  StatusBadge,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime, humanize } from '../lib/format';

const STATUSES: PaymentStatus[] = [
  'AUTHORIZED',
  'PARTIALLY_CAPTURED',
  'CAPTURED',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
  'VOIDED',
  'DISPUTED',
  'RESOLVED',
  'FAILED',
];

export function PaymentsPage() {
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const status = (params.get('status') as PaymentStatus | null) ?? undefined;
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? 0);
  const sort = params.get('sort') ?? 'createdAt,desc';
  const [authorizing, setAuthorizing] = useState(false);
  const directory = useAccountDirectory();
  const { data, isLoading, error, isFetching } = usePayments({
    status: status ? [status] : undefined,
    q: q || undefined,
    page,
    sort,
  });

  function update(next: Record<string, string | undefined>) {
    const merged = new URLSearchParams(params);
    Object.entries(next).forEach(([k, v]) => (v ? merged.set(k, v) : merged.delete(k)));
    if (!('page' in next)) {
      merged.delete('page');
    }
    setParams(merged, { replace: true });
  }

  return (
    <>
      <PageHeader
        title="Payments"
        description="Authorizations, captures, voids and refunds."
        actions={can('OPERATIONS') && <Button onClick={() => setAuthorizing(true)}>New payment</Button>}
      />
      <Card>
        <div className="mb-4 grid gap-3 sm:grid-cols-3">
          <Field
            label="Search"
            placeholder="Reference or payment id"
            value={q}
            onChange={(e) => update({ q: e.target.value })}
          />
          <SelectField
            label="Status"
            value={status ?? ''}
            onChange={(e) => update({ status: e.target.value || undefined })}
          >
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanize(s)}
              </option>
            ))}
          </SelectField>
          <SelectField label="Sort" value={sort} onChange={(e) => update({ sort: e.target.value })}>
            <option value="createdAt,desc">Newest first</option>
            <option value="createdAt,asc">Oldest first</option>
            <option value="authorizedAmountMinor,desc">Largest amount</option>
            <option value="authorizedAmountMinor,asc">Smallest amount</option>
          </SelectField>
        </div>
        {isLoading ? (
          <Loading />
        ) : error ? (
          <ErrorAlert error={error} />
        ) : data && data.items.length === 0 ? (
          <EmptyState title="No payments match these filters" />
        ) : (
          data && (
            <div className={isFetching ? 'opacity-60' : ''}>
              <Table caption="Payments">
                <thead>
                  <tr>
                    <Th>Reference</Th>
                    <Th>Customer</Th>
                    <Th>Merchant</Th>
                    <Th>Status</Th>
                    <Th align="right">Authorized</Th>
                    <Th align="right">Captured</Th>
                    <Th align="right">Refunded</Th>
                    <Th>Created</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.items.map((p) => (
                    <tr key={p.id}>
                      <Td>
                        <Link
                          to={`/payments/${p.id}`}
                          className="font-medium text-indigo-600 hover:underline"
                        >
                          {p.reference ?? p.id.slice(0, 8)}
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
                      <Td align="right">
                        <Money minor={p.capturedAmountMinor} />
                      </Td>
                      <Td align="right">
                        <Money minor={p.refundedAmountMinor} />
                      </Td>
                      <Td className="text-slate-500">{formatDateTime(p.createdAt)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                totalItems={data.totalItems}
                onChange={(next) => update({ page: String(next) })}
              />
            </div>
          )
        )}
      </Card>
      <AuthorizeDialog open={authorizing} onClose={() => setAuthorizing(false)} />
    </>
  );
}

function AuthorizeDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const customers = useAccounts({ type: 'CUSTOMER', status: 'ACTIVE', size: 100 });
  const merchants = useAccounts({ type: 'MERCHANT', status: 'ACTIVE', size: 100 });
  const [customerId, setCustomerId] = useState('');
  const [merchantId, setMerchantId] = useState('');
  const [reference, setReference] = useState('');
  const [description, setDescription] = useState('');
  const money = useMoneyInput();
  const [key, rotateKey] = useIdempotencyKey();
  const authorize = useIdempotentMutation<Record<string, unknown>, Payment>('/payments');
  const customer = customers.data?.items.find((c) => c.id === customerId);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (money.minor === null) {
      return;
    }
    authorize.mutate(
      {
        body: {
          customerAccountId: customerId,
          merchantAccountId: merchantId,
          amountMinor: money.minor,
          reference: reference || undefined,
          description: description || undefined,
        },
        idempotencyKey: key,
      },
      {
        onSuccess: (payment) => {
          rotateKey();
          onClose();
          navigate(`/payments/${payment.id}`);
        },
      },
    );
  }

  return (
    <Dialog open={open} title="Authorize payment" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <SelectField
          label="Customer"
          required
          value={customerId}
          onChange={(e) => setCustomerId(e.target.value)}
        >
          <option value="">Select a customer</option>
          {customers.data?.items.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name} ({c.reference})
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Merchant"
          required
          value={merchantId}
          onChange={(e) => setMerchantId(e.target.value)}
        >
          <option value="">Select a merchant</option>
          {merchants.data?.items.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name} ({m.reference})
            </option>
          ))}
        </SelectField>
        <MoneyField
          label="Amount"
          money={money}
          hint={
            customer
              ? `Customer has ${new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(customer.availableMinor / 100)} available`
              : undefined
          }
        />
        <Field
          label="Reference"
          maxLength={64}
          placeholder="ORDER-1042"
          value={reference}
          onChange={(e) => setReference(e.target.value)}
        />
        <Field
          label="Description"
          maxLength={255}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
        <ErrorAlert error={authorize.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            busy={authorize.isPending}
            disabled={!customerId || !merchantId || money.minor === null}
          >
            Authorize
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
