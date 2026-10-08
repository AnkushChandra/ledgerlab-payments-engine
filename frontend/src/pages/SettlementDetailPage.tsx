import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiDownload } from '../api/client';
import { useReconciliationResults, useSettlementBatch } from '../api/hooks';
import type { Classification } from '../api/types';
import {
  Button,
  Card,
  EmptyState,
  ErrorAlert,
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

const CLASSIFICATIONS: Classification[] = [
  'MATCHED',
  'MISSING_INTERNAL',
  'MISSING_EXTERNAL',
  'AMOUNT_MISMATCH',
  'STATUS_MISMATCH',
  'DUPLICATE_EXTERNAL',
];

export function SettlementDetailPage() {
  const { batchId = '' } = useParams();
  const batch = useSettlementBatch(batchId);
  const [classification, setClassification] = useState<Classification[]>([]);
  const [page, setPage] = useState(0);
  const results = useReconciliationResults(batchId, classification, page);
  const [downloading, setDownloading] = useState(false);

  async function download() {
    setDownloading(true);
    try {
      const { blob, fileName } = await apiDownload(`/settlement-batches/${batchId}/results.csv`);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = fileName;
      a.click();
      URL.revokeObjectURL(url);
    } finally {
      setDownloading(false);
    }
  }

  if (batch.isLoading) {
    return <Loading />;
  }
  if (batch.error || !batch.data) {
    return <ErrorAlert error={batch.error} />;
  }
  const b = batch.data;

  function toggle(c: Classification) {
    setPage(0);
    setClassification((current) => (current.includes(c) ? current.filter((x) => x !== c) : [...current, c]));
  }

  return (
    <>
      <PageHeader
        title={b.fileName}
        description={`${b.periodStart} → ${b.periodEnd} · uploaded ${formatDateTime(b.uploadedAt)}`}
        actions={
          <Button variant="secondary" busy={downloading} onClick={download}>
            Download CSV
          </Button>
        }
      />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Stat label="Records" value={b.recordCount} />
        <Stat label="Exceptions" value={b.exceptionCount} />
        <Stat label="Matched" value={b.counts.MATCHED ?? 0} />
        <Stat
          label="SHA-256"
          value={<span className="font-mono text-xs">{b.contentSha256.slice(0, 16)}…</span>}
        />
      </div>
      <div className="mt-4 flex flex-wrap gap-2">
        {CLASSIFICATIONS.map((c) => (
          <button
            key={c}
            type="button"
            onClick={() => toggle(c)}
            className={`rounded-full px-2 py-1 text-xs ring-1 ${classification.includes(c) ? 'bg-indigo-50 ring-indigo-400' : 'bg-white ring-slate-200'}`}
          >
            <StatusBadge status={c} /> {b.counts[c] ?? 0}
          </button>
        ))}
      </div>

      <Card className="mt-6" title="Results">
        {results.isLoading ? (
          <Loading />
        ) : results.error ? (
          <ErrorAlert error={results.error} />
        ) : results.data && results.data.items.length === 0 ? (
          <EmptyState title="No results match this filter" />
        ) : (
          results.data && (
            <>
              <Table caption="Reconciliation results">
                <thead>
                  <tr>
                    <Th>#</Th>
                    <Th>Classification</Th>
                    <Th>Payment</Th>
                    <Th>Processor id</Th>
                    <Th>Status</Th>
                    <Th align="right">Expected</Th>
                    <Th align="right">Reported</Th>
                    <Th>Message</Th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {results.data.items.map((r) => (
                    <tr key={r.id}>
                      <Td>{r.sequenceNumber}</Td>
                      <Td>
                        <StatusBadge status={r.classification} />
                      </Td>
                      <Td>
                        {r.paymentId ? (
                          <Link
                            to={`/payments/${r.paymentId}`}
                            className="font-mono text-indigo-600 hover:underline"
                          >
                            {r.paymentId.slice(0, 8)}
                          </Link>
                        ) : (
                          '—'
                        )}
                      </Td>
                      <Td className="font-mono text-xs">{r.processorRecordId ?? '—'}</Td>
                      <Td className="text-xs">
                        {r.expectedStatus && r.actualStatus && r.expectedStatus !== r.actualStatus
                          ? `${humanize(r.expectedStatus)} → ${humanize(r.actualStatus)}`
                          : humanize(r.expectedStatus ?? r.actualStatus ?? '—')}
                      </Td>
                      <Td align="right">
                        <Money minor={r.expectedAmountMinor} />
                      </Td>
                      <Td align="right">
                        <Money minor={r.actualAmountMinor} />
                      </Td>
                      <Td className="max-w-xs whitespace-normal text-xs text-slate-600">{r.message}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination
                page={results.data.page}
                totalPages={results.data.totalPages}
                totalItems={results.data.totalItems}
                onChange={setPage}
              />
            </>
          )
        )}
      </Card>
    </>
  );
}
