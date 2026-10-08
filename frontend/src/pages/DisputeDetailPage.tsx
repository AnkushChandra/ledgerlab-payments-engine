import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useDispute, useIdempotentMutation } from '../api/hooks';
import type { Dispute } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AccountLink, useAccountDirectory } from '../components/AccountName';
import { useIdempotencyKey } from '../components/forms';
import {
  Button,
  Card,
  Dialog,
  ErrorAlert,
  Field,
  Loading,
  Money,
  PageHeader,
  SelectField,
  Stat,
  StatusBadge,
} from '../components/ui';
import { formatDateTime } from '../lib/format';

export function DisputeDetailPage() {
  const { disputeId = '' } = useParams();
  const { can } = useAuth();
  const dispute = useDispute(disputeId);
  const directory = useAccountDirectory();
  const [resolving, setResolving] = useState(false);

  if (dispute.isLoading) {
    return <Loading />;
  }
  if (dispute.error || !dispute.data) {
    return <ErrorAlert error={dispute.error} />;
  }
  const d = dispute.data;

  return (
    <>
      <PageHeader
        title="Dispute"
        description={<StatusBadge status={d.status} />}
        actions={
          can('OPERATIONS') &&
          d.status === 'OPEN' && <Button onClick={() => setResolving(true)}>Resolve</Button>
        }
      />
      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Amount held" value={<Money minor={d.amountMinor} />} />
        <Stat label="Opened" value={formatDateTime(d.openedAt)} />
        <Stat label="Resolved" value={formatDateTime(d.resolvedAt)} />
      </div>
      <Card className="mt-6" title="Details">
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-xs uppercase text-slate-400">Payment</dt>
            <dd>
              <Link to={`/payments/${d.paymentId}`} className="text-indigo-600 hover:underline">
                {d.payment.reference ?? d.paymentId.slice(0, 8)}
              </Link>{' '}
              <StatusBadge status={d.payment.status} />
            </dd>
          </div>
          <div>
            <dt className="text-xs uppercase text-slate-400">Merchant</dt>
            <dd>
              <AccountLink id={d.payment.merchantAccountId} directory={directory} />
            </dd>
          </div>
          <div>
            <dt className="text-xs uppercase text-slate-400">Customer</dt>
            <dd>
              <AccountLink id={d.payment.customerAccountId} directory={directory} />
            </dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-xs uppercase text-slate-400">Reason</dt>
            <dd>{d.reason}</dd>
          </div>
          {d.resolutionNote && (
            <div className="sm:col-span-2">
              <dt className="text-xs uppercase text-slate-400">Resolution note</dt>
              <dd>{d.resolutionNote}</dd>
            </div>
          )}
        </dl>
      </Card>
      <ResolveDialog dispute={d} open={resolving} onClose={() => setResolving(false)} />
    </>
  );
}

function ResolveDialog({ dispute, open, onClose }: { dispute: Dispute; open: boolean; onClose: () => void }) {
  const [outcome, setOutcome] = useState<'WON' | 'LOST'>('WON');
  const [note, setNote] = useState('');
  const [key, rotateKey] = useIdempotencyKey();
  const resolve = useIdempotentMutation<{ outcome: 'WON' | 'LOST'; note?: string }, Dispute>(
    `/disputes/${dispute.id}/resolution`,
  );

  function submit(event: FormEvent) {
    event.preventDefault();
    resolve.mutate(
      { body: { outcome, note: note || undefined }, idempotencyKey: key },
      {
        onSuccess: () => {
          rotateKey();
          onClose();
        },
      },
    );
  }

  return (
    <Dialog open={open} title="Resolve dispute" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <SelectField
          label="Outcome"
          value={outcome}
          onChange={(e) => setOutcome(e.target.value as 'WON' | 'LOST')}
        >
          <option value="WON">Won — merchant keeps the funds</option>
          <option value="LOST">Lost — chargeback to the customer</option>
        </SelectField>
        <Field label="Note" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
        <ErrorAlert error={resolve.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={resolve.isPending}>
            Resolve
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
