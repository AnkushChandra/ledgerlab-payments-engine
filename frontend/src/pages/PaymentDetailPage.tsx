import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  useIdempotentMutation,
  usePayment,
  usePaymentDisputes,
  usePaymentEvents,
  usePaymentRefunds,
} from '../api/hooks';
import type { Dispute, Payment, Refund } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AccountLink, useAccountDirectory } from '../components/AccountName';
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
  Stat,
  StatusBadge,
  Table,
  Td,
  Th,
} from '../components/ui';
import { formatDateTime, humanize } from '../lib/format';

export function PaymentDetailPage() {
  const { paymentId = '' } = useParams();
  const { can } = useAuth();
  const payment = usePayment(paymentId);
  const events = usePaymentEvents(paymentId);
  const refunds = usePaymentRefunds(paymentId);
  const disputes = usePaymentDisputes(paymentId);
  const directory = useAccountDirectory();
  const [journal, setJournal] = useState<string | null>(null);
  const [dialog, setDialog] = useState<'capture' | 'void' | 'refund' | 'dispute' | null>(null);

  if (payment.isLoading) {
    return <Loading />;
  }
  if (payment.error || !payment.data) {
    return <ErrorAlert error={payment.error} />;
  }
  const p = payment.data;
  const actions = p.allowedActions;
  const ops = can('OPERATIONS');

  return (
    <>
      <PageHeader
        title={p.reference ?? 'Payment'}
        description={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={p.status} />
            {p.failureCode && <span className="text-rose-600">{humanize(p.failureCode)}</span>}
            {p.description && <span>· {p.description}</span>}
          </span>
        }
        actions={
          ops && (
            <>
              {actions.includes('CAPTURE') && <Button onClick={() => setDialog('capture')}>Capture</Button>}
              {actions.includes('VOID') && (
                <Button variant="secondary" onClick={() => setDialog('void')}>
                  Void
                </Button>
              )}
              {actions.includes('REFUND') && (
                <Button variant="secondary" onClick={() => setDialog('refund')}>
                  Refund
                </Button>
              )}
              {actions.includes('OPEN_DISPUTE') && (
                <Button variant="danger" onClick={() => setDialog('dispute')}>
                  Open dispute
                </Button>
              )}
            </>
          )
        }
      />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Stat label="Authorized" value={<Money minor={p.authorizedAmountMinor} />} />
        <Stat
          label="Captured"
          value={<Money minor={p.capturedAmountMinor} />}
          hint={`${formatDateTime(p.firstCapturedAt)}`}
        />
        <Stat label="Refunded" value={<Money minor={p.refundedAmountMinor} />} />
        <Stat
          label="Net settled"
          value={<Money minor={p.netSettledMinor} />}
          hint="Captured − refunded − chargebacks"
        />
      </div>

      <dl className="mt-6 grid gap-3 rounded-lg bg-white p-4 text-sm shadow-sm ring-1 ring-slate-200 sm:grid-cols-2">
        <div>
          <dt className="text-xs uppercase text-slate-400">Customer</dt>
          <dd>
            <AccountLink id={p.customerAccountId} directory={directory} />
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-400">Merchant</dt>
          <dd>
            <AccountLink id={p.merchantAccountId} directory={directory} />
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-400">Remaining authorization</dt>
          <dd>
            <Money minor={p.remainingAuthorizationMinor} />
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-400">Refundable</dt>
          <dd>
            <Money minor={p.refundableMinor} />
          </dd>
        </div>
      </dl>

      <Card title="Timeline" className="mt-6">
        {events.isLoading ? (
          <Loading />
        ) : events.error ? (
          <ErrorAlert error={events.error} />
        ) : !events.data || events.data.length === 0 ? (
          <EmptyState title="No events" />
        ) : (
          <ol className="relative space-y-4 border-l border-slate-200 pl-6">
            {events.data.map((e) => (
              <li key={e.id} className="relative">
                <span className="absolute -left-[29px] mt-1 h-3 w-3 rounded-full bg-indigo-500 ring-4 ring-white" />
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <StatusBadge status={e.toStatus} />
                  <span className="font-medium">{humanize(e.type)}</span>
                  {e.amountMinor > 0 && <Money minor={e.amountMinor} />}
                  <span className="text-slate-400">{formatDateTime(e.createdAt)}</span>
                </div>
                {e.note && <p className="mt-1 text-sm text-slate-500">{e.note}</p>}
                {e.ledgerTransactionId && (
                  <Button
                    variant="ghost"
                    className="mt-1 px-0"
                    onClick={() => setJournal(e.ledgerTransactionId)}
                  >
                    View journal
                  </Button>
                )}
              </li>
            ))}
          </ol>
        )}
      </Card>

      {refunds.data && refunds.data.length > 0 && (
        <Card title="Refunds" className="mt-6">
          <Table caption="Refunds">
            <thead>
              <tr>
                <Th>When</Th>
                <Th align="right">Amount</Th>
                <Th>Reason</Th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {refunds.data.map((r) => (
                <tr key={r.id}>
                  <Td>{formatDateTime(r.createdAt)}</Td>
                  <Td align="right">
                    <Money minor={r.amountMinor} />
                  </Td>
                  <Td>{r.reason ?? '—'}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}

      {disputes.data && disputes.data.length > 0 && (
        <Card title="Disputes" className="mt-6">
          <ul className="space-y-2 text-sm">
            {disputes.data.map((d) => (
              <li key={d.id} className="flex items-center justify-between">
                <Link to={`/disputes/${d.id}`} className="text-indigo-600 hover:underline">
                  {d.reason}
                </Link>
                <span className="flex items-center gap-2">
                  <Money minor={d.amountMinor} />
                  <StatusBadge status={d.status} />
                </span>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <LedgerTransactionDialog transactionId={journal} onClose={() => setJournal(null)} />
      <CaptureDialog payment={p} open={dialog === 'capture'} onClose={() => setDialog(null)} />
      <VoidDialog payment={p} open={dialog === 'void'} onClose={() => setDialog(null)} />
      <RefundDialog payment={p} open={dialog === 'refund'} onClose={() => setDialog(null)} />
      <OpenDisputeDialog payment={p} open={dialog === 'dispute'} onClose={() => setDialog(null)} />
    </>
  );
}

function CaptureDialog({ payment, open, onClose }: { payment: Payment; open: boolean; onClose: () => void }) {
  const money = useMoneyInput();
  const [finalCapture, setFinalCapture] = useState(false);
  const [key, rotateKey] = useIdempotencyKey();
  const capture = useIdempotentMutation<{ amountMinor: number; finalCapture: boolean }, Payment>(
    `/payments/${payment.id}/captures`,
  );

  function submit(event: FormEvent) {
    event.preventDefault();
    if (money.minor === null) {
      return;
    }
    capture.mutate(
      { body: { amountMinor: money.minor, finalCapture }, idempotencyKey: key },
      {
        onSuccess: () => {
          rotateKey();
          money.reset();
          setFinalCapture(false);
          onClose();
        },
      },
    );
  }

  return (
    <Dialog open={open} title="Capture" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField label="Amount" money={money} max={payment.remainingAuthorizationMinor} />
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={finalCapture} onChange={(e) => setFinalCapture(e.target.checked)} />
          Final capture (release any uncaptured remainder)
        </label>
        <ErrorAlert error={capture.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={capture.isPending} disabled={money.minor === null}>
            Capture
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function VoidDialog({ payment, open, onClose }: { payment: Payment; open: boolean; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [key, rotateKey] = useIdempotencyKey();
  const voidPayment = useIdempotentMutation<{ reason?: string }, Payment>(`/payments/${payment.id}/void`);

  function submit(event: FormEvent) {
    event.preventDefault();
    voidPayment.mutate(
      { body: { reason: reason || undefined }, idempotencyKey: key },
      {
        onSuccess: () => {
          rotateKey();
          setReason('');
          onClose();
        },
      },
    );
  }

  return (
    <Dialog open={open} title="Void authorization" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-sm text-slate-500">
          Releases <Money minor={payment.remainingAuthorizationMinor} /> back to the customer.
        </p>
        <Field label="Reason" maxLength={255} value={reason} onChange={(e) => setReason(e.target.value)} />
        <ErrorAlert error={voidPayment.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" busy={voidPayment.isPending}>
            Void
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function RefundDialog({ payment, open, onClose }: { payment: Payment; open: boolean; onClose: () => void }) {
  const money = useMoneyInput();
  const [reason, setReason] = useState('');
  const [key, rotateKey] = useIdempotencyKey();
  const refund = useIdempotentMutation<
    { amountMinor: number; reason?: string },
    { refund: Refund; payment: Payment }
  >(`/payments/${payment.id}/refunds`);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (money.minor === null) {
      return;
    }
    refund.mutate(
      { body: { amountMinor: money.minor, reason: reason || undefined }, idempotencyKey: key },
      {
        onSuccess: () => {
          rotateKey();
          money.reset();
          setReason('');
          onClose();
        },
      },
    );
  }

  return (
    <Dialog open={open} title="Refund" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <MoneyField label="Amount" money={money} max={payment.refundableMinor} />
        <Field label="Reason" maxLength={255} value={reason} onChange={(e) => setReason(e.target.value)} />
        <ErrorAlert error={refund.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={refund.isPending} disabled={money.minor === null}>
            Refund
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function OpenDisputeDialog({
  payment,
  open,
  onClose,
}: {
  payment: Payment;
  open: boolean;
  onClose: () => void;
}) {
  const money = useMoneyInput();
  const [reason, setReason] = useState('');
  const [key, rotateKey] = useIdempotencyKey();
  const openDispute = useIdempotentMutation<{ amountMinor?: number; reason: string }, Dispute>(
    `/payments/${payment.id}/disputes`,
  );

  function submit(event: FormEvent) {
    event.preventDefault();
    openDispute.mutate(
      {
        body: { amountMinor: money.minor ?? undefined, reason },
        idempotencyKey: key,
      },
      {
        onSuccess: () => {
          rotateKey();
          money.reset();
          setReason('');
          onClose();
        },
      },
    );
  }

  return (
    <Dialog open={open} title="Open dispute" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <p className="text-sm text-slate-500">
          Freezes merchant funds in a dispute hold. Leave the amount blank to dispute the full refundable{' '}
          <Money minor={payment.refundableMinor} />.
        </p>
        <MoneyField
          label="Amount (optional)"
          money={money}
          max={payment.refundableMinor}
          hint="Blank = full refundable amount"
        />
        <Field
          label="Reason"
          required
          maxLength={255}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
        <ErrorAlert error={openDispute.error} />
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" busy={openDispute.isPending} disabled={!reason.trim()}>
            Open dispute
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
