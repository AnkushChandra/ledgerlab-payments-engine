import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { apiRequest } from '../api/client';
import { useSettlementBatches } from '../api/hooks';
import type { SettlementBatch } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useIdempotencyKey } from '../components/forms';
import {
  Button,
  Card,
  Dialog,
  EmptyState,
  ErrorAlert,
  Field,
  Loading,
  PageHeader,
  Pagination,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime } from '../lib/format';

export function SettlementsPage() {
  const { can } = useAuth();
  const [page, setPage] = useState(0);
  const [uploading, setUploading] = useState(false);
  const { data, isLoading, error } = useSettlementBatches(page);

  return (
    <>
      <PageHeader
        title="Settlement reconciliation"
        description="Upload a processor CSV and match it against captured payments in a date range."
        actions={
          can('OPERATIONS') && <Button onClick={() => setUploading(true)}>Upload settlement file</Button>
        }
      />
      <Card>
        {isLoading ? (
          <Loading />
        ) : error ? (
          <ErrorAlert error={error} />
        ) : data && data.items.length === 0 ? (
          <EmptyState title="No settlement batches yet">
            Sample files live in <span className="font-mono">samples/</span>.
          </EmptyState>
        ) : (
          data && (
            <>
              <Table caption="Settlement batches">
                <thead>
                  <tr>
                    <Th>Uploaded</Th>
                    <Th>File</Th>
                    <Th>Period</Th>
                    <Th align="right">Records</Th>
                    <Th align="right">Exceptions</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.items.map((b) => (
                    <tr key={b.id}>
                      <Td>{formatDateTime(b.uploadedAt)}</Td>
                      <Td>
                        <Link
                          to={`/settlements/${b.id}`}
                          className="font-medium text-indigo-600 hover:underline"
                        >
                          {b.fileName}
                        </Link>
                      </Td>
                      <Td>
                        {b.periodStart} → {b.periodEnd}
                      </Td>
                      <Td align="right">{b.recordCount}</Td>
                      <Td align="right">{b.exceptionCount}</Td>
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
      <UploadDialog open={uploading} onClose={() => setUploading(false)} />
    </>
  );
}

function UploadDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [periodStart, setPeriodStart] = useState('2026-09-01');
  const [periodEnd, setPeriodEnd] = useState('2026-09-03');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [key, rotateKey] = useIdempotencyKey();

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!file) {
      return;
    }
    setBusy(true);
    setError(null);
    const body = new FormData();
    body.append('file', file);
    body.append('periodStart', periodStart);
    body.append('periodEnd', periodEnd);
    try {
      await apiRequest<SettlementBatch>(
        `/settlement-batches?periodStart=${periodStart}&periodEnd=${periodEnd}`,
        {
          method: 'POST',
          body,
          idempotencyKey: key,
        },
      );
      await queryClient.invalidateQueries();
      rotateKey();
      setFile(null);
      onClose();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} title="Upload settlement CSV" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-sm text-slate-500">
          Header must be{' '}
          <span className="font-mono text-xs">
            processor_record_id,payment_id,status,amount_minor,currency,settled_at
          </span>
          . The whole file is rejected if any row is invalid.
        </p>
        <div className="space-y-1">
          <label htmlFor="settlement-file" className="block text-sm font-medium text-slate-700">
            CSV file
          </label>
          <input
            id="settlement-file"
            type="file"
            accept=".csv,text/csv"
            required
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className="block w-full text-sm"
          />
        </div>
        <Field
          label="Period start"
          type="date"
          required
          value={periodStart}
          onChange={(e) => setPeriodStart(e.target.value)}
        />
        <Field
          label="Period end"
          type="date"
          required
          value={periodEnd}
          onChange={(e) => setPeriodEnd(e.target.value)}
        />
        <ErrorAlert error={error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={busy} disabled={!file}>
            Reconcile
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
