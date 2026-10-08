import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useAccounts } from '../api/hooks';
import { apiRequest } from '../api/client';
import type { Account, AccountStatus, AccountType } from '../api/types';
import { useAuth } from '../auth/AuthContext';
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
import { useQueryClient } from '@tanstack/react-query';

export function AccountsPage() {
  const { can } = useAuth();
  const [type, setType] = useState<AccountType | ''>('');
  const [status, setStatus] = useState<AccountStatus | ''>('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const { data, isLoading, error, isFetching } = useAccounts({
    type: type || undefined,
    status: status || undefined,
    q: q || undefined,
    page,
  });

  return (
    <>
      <PageHeader
        title="Accounts"
        description="Customer and merchant accounts. Each is backed by two ledger accounts."
        actions={can('OPERATIONS') && <Button onClick={() => setCreating(true)}>New account</Button>}
      />
      <Card>
        <div className="mb-4 grid gap-3 sm:grid-cols-3">
          <Field
            label="Search"
            placeholder="Name or reference"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(0);
            }}
          />
          <SelectField
            label="Type"
            value={type}
            onChange={(e) => {
              setType(e.target.value as AccountType | '');
              setPage(0);
            }}
          >
            <option value="">All types</option>
            <option value="CUSTOMER">Customer</option>
            <option value="MERCHANT">Merchant</option>
          </SelectField>
          <SelectField
            label="Status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as AccountStatus | '');
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="FROZEN">Frozen</option>
            <option value="CLOSED">Closed</option>
          </SelectField>
        </div>
        {isLoading ? (
          <Loading />
        ) : error ? (
          <ErrorAlert error={error} />
        ) : data && data.items.length === 0 ? (
          <EmptyState title="No accounts match these filters" />
        ) : (
          data && (
            <div className={isFetching ? 'opacity-60' : ''}>
              <Table caption="Accounts">
                <thead>
                  <tr>
                    <Th>Name</Th>
                    <Th>Reference</Th>
                    <Th>Type</Th>
                    <Th>Status</Th>
                    <Th align="right">Available</Th>
                    <Th align="right">Held</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.items.map((a) => (
                    <tr key={a.id}>
                      <Td>
                        <Link
                          to={`/accounts/${a.id}`}
                          className="font-medium text-indigo-600 hover:underline"
                        >
                          {a.name}
                        </Link>
                      </Td>
                      <Td className="font-mono text-slate-500">{a.reference}</Td>
                      <Td>{a.type === 'CUSTOMER' ? 'Customer' : 'Merchant'}</Td>
                      <Td>
                        <StatusBadge status={a.status} />
                      </Td>
                      <Td align="right">
                        <Money minor={a.availableMinor} />
                      </Td>
                      <Td align="right">
                        <Money minor={a.heldMinor} />
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
            </div>
          )
        )}
      </Card>
      <CreateAccountDialog open={creating} onClose={() => setCreating(false)} />
    </>
  );
}

function CreateAccountDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [type, setType] = useState<AccountType>('CUSTOMER');
  const [name, setName] = useState('');
  const [reference, setReference] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiRequest<Account>('/accounts', { method: 'POST', body: { type, name, reference } });
      await queryClient.invalidateQueries();
      setName('');
      setReference('');
      onClose();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} title="New account" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <SelectField label="Type" value={type} onChange={(e) => setType(e.target.value as AccountType)}>
          <option value="CUSTOMER">Customer</option>
          <option value="MERCHANT">Merchant</option>
        </SelectField>
        <Field label="Name" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        <Field
          label="Reference"
          required
          maxLength={64}
          pattern="[A-Za-z0-9._-]+"
          hint="Unique within the organization, e.g. CUST-1003"
          value={reference}
          onChange={(e) => setReference(e.target.value)}
        />
        <ErrorAlert error={error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={busy} disabled={!name.trim() || !reference.trim()}>
            Create account
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
