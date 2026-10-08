import { useCallback, useState } from 'react';
import { useLedgerTransaction } from '../api/hooks';
import { formatMoney, parseMoneyInput } from '../lib/money';
import { humanize, newIdempotencyKey } from '../lib/format';
import { Dialog, ErrorAlert, Field, Loading, Money, Table, Td, Th } from './ui';

/**
 * One key per logical operation: it survives retries of the same submission (so a timeout followed
 * by a retry cannot double-charge) and is rotated only after the operation succeeds.
 */
export function useIdempotencyKey(): [string, () => void] {
  const [key, setKey] = useState(newIdempotencyKey);
  const rotate = useCallback(() => setKey(newIdempotencyKey()), []);
  return [key, rotate];
}

export function useMoneyInput(initial = '') {
  const [text, setText] = useState(initial);
  const [touched, setTouched] = useState(false);
  const minor = parseMoneyInput(text);
  const error = touched && minor === null ? 'Enter an amount between $0.01 and $1,000,000.00' : null;
  return {
    text,
    minor,
    error,
    setText,
    reset: (value = '') => {
      setText(value);
      setTouched(false);
    },
    inputProps: {
      value: text,
      inputMode: 'decimal' as const,
      placeholder: '0.00',
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => setText(e.target.value),
      onBlur: () => setTouched(true),
    },
  };
}

export function MoneyField({
  label,
  money,
  max,
  hint,
}: {
  label: string;
  money: ReturnType<typeof useMoneyInput>;
  max?: number;
  hint?: string;
}) {
  const overMax = max !== undefined && money.minor !== null && money.minor > max;
  return (
    <Field
      label={label}
      {...money.inputProps}
      error={money.error ?? (overMax ? `Must not exceed ${formatMoney(max)}` : null)}
      hint={hint ?? (max !== undefined ? `Up to ${formatMoney(max)}` : 'US dollars')}
    />
  );
}

export function LedgerTransactionDialog({
  transactionId,
  onClose,
}: {
  transactionId: string | null;
  onClose: () => void;
}) {
  const { data, isLoading, error } = useLedgerTransaction(transactionId);
  return (
    <Dialog open={transactionId !== null} title="Journal entry" onClose={onClose}>
      {isLoading && <Loading />}
      <ErrorAlert error={error} />
      {data && (
        <div className="space-y-3 text-sm">
          <div>
            <p className="font-medium">{data.transaction.description}</p>
            <p className="text-slate-500">{humanize(data.transaction.type)}</p>
          </div>
          <Table caption="Journal lines">
            <thead>
              <tr>
                <Th>Ledger account</Th>
                <Th align="right">Debit</Th>
                <Th align="right">Credit</Th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.entries.map((e) => (
                <tr key={e.id}>
                  <Td>{humanize(e.purpose)}</Td>
                  <Td align="right">{e.amountMinor > 0 ? <Money minor={e.amountMinor} /> : ''}</Td>
                  <Td align="right">{e.amountMinor < 0 ? <Money minor={-e.amountMinor} /> : ''}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
          <p className="text-xs text-slate-500">
            Debits equal credits (
            {formatMoney(
              data.entries.filter((e) => e.amountMinor > 0).reduce((s, e) => s + e.amountMinor, 0),
            )}
            ). Journals are immutable.
          </p>
        </div>
      )}
    </Dialog>
  );
}
