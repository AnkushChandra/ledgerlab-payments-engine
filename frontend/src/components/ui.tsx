import {
  useEffect,
  useId,
  useRef,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
} from 'react';
import { ApiError } from '../api/client';
import { humanize } from '../lib/format';
import { formatMoney } from '../lib/money';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';

const variants: Record<Variant, string> = {
  primary: 'bg-indigo-600 text-white hover:bg-indigo-700 disabled:bg-indigo-300',
  secondary: 'bg-white text-slate-700 ring-1 ring-slate-300 hover:bg-slate-50 disabled:text-slate-400',
  danger: 'bg-rose-600 text-white hover:bg-rose-700 disabled:bg-rose-300',
  ghost: 'text-slate-600 hover:bg-slate-100 disabled:text-slate-300',
};

export function Button({
  variant = 'primary',
  className = '',
  busy = false,
  children,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; busy?: boolean }) {
  return (
    <button
      type="button"
      {...props}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed ${variants[variant]} ${className}`}
    >
      {busy && <Spinner small />}
      {children}
    </button>
  );
}

export function Card({
  title,
  actions,
  children,
  className = '',
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`rounded-lg bg-white shadow-sm ring-1 ring-slate-200 ${className}`}>
      {(title || actions) && (
        <header className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
          {title && <h2 className="text-sm font-semibold text-slate-800">{title}</h2>}
          {actions && <div className="flex gap-2">{actions}</div>}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
        {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

const badgeTones: Record<string, string> = {
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  blue: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  amber: 'bg-amber-50 text-amber-800 ring-amber-600/20',
  red: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  gray: 'bg-slate-100 text-slate-600 ring-slate-500/20',
  violet: 'bg-violet-50 text-violet-700 ring-violet-600/20',
};

const statusTone: Record<string, keyof typeof badgeTones> = {
  AUTHORIZED: 'blue',
  PARTIALLY_CAPTURED: 'blue',
  CAPTURED: 'green',
  PARTIALLY_REFUNDED: 'amber',
  REFUNDED: 'gray',
  VOIDED: 'gray',
  DISPUTED: 'red',
  RESOLVED: 'violet',
  FAILED: 'red',
  ACTIVE: 'green',
  FROZEN: 'amber',
  CLOSED: 'gray',
  OPEN: 'red',
  WON: 'green',
  LOST: 'gray',
  MATCHED: 'green',
  MISSING_INTERNAL: 'red',
  MISSING_EXTERNAL: 'red',
  AMOUNT_MISMATCH: 'amber',
  STATUS_MISMATCH: 'amber',
  DUPLICATE_EXTERNAL: 'violet',
};

export function StatusBadge({ status }: { status: string }) {
  const tone = badgeTones[statusTone[status] ?? 'gray'];
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${tone}`}
    >
      {humanize(status)}
    </span>
  );
}

export function Money({ minor, className = '' }: { minor: number | null | undefined; className?: string }) {
  return <span className={`font-mono tabular-nums ${className}`}>{formatMoney(minor)}</span>;
}

export function Spinner({ small = false }: { small?: boolean }) {
  return (
    <span
      className={`inline-block animate-spin rounded-full border-2 border-current border-t-transparent ${small ? 'h-3.5 w-3.5' : 'h-5 w-5'}`}
      aria-hidden="true"
    />
  );
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-2 py-8 text-sm text-slate-500">
      <Spinner /> {label}
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="rounded-md border border-dashed border-slate-300 px-6 py-10 text-center">
      <p className="text-sm font-medium text-slate-700">{title}</p>
      {children && <div className="mt-1 text-sm text-slate-500">{children}</div>}
    </div>
  );
}

export function ErrorAlert({ error, title }: { error: unknown; title?: string }) {
  if (!error) {
    return null;
  }
  const apiError = error instanceof ApiError ? error : null;
  return (
    <div role="alert" className="rounded-md border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">
      <p className="font-medium">{title ?? apiError?.title ?? 'Something went wrong'}</p>
      <p className="mt-0.5">{apiError?.detail || (error instanceof Error ? error.message : String(error))}</p>
      {apiError && apiError.fieldErrors.length > 0 && (
        <ul className="mt-2 list-disc space-y-0.5 pl-5">
          {apiError.fieldErrors.map((e, i) => (
            <li key={i}>
              <span className="font-mono">{e.field}</span>: {e.message}
            </li>
          ))}
        </ul>
      )}
      {apiError?.correlationId && (
        <p className="mt-2 text-xs text-rose-600">
          Reference: <span className="font-mono">{apiError.correlationId}</span>
        </p>
      )}
    </div>
  );
}

export function Pagination({
  page,
  totalPages,
  totalItems,
  onChange,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  onChange: (page: number) => void;
}) {
  if (totalItems === 0) {
    return null;
  }
  return (
    <nav aria-label="Pagination" className="mt-4 flex items-center justify-between text-sm text-slate-600">
      <span>
        Page {page + 1} of {Math.max(totalPages, 1)} · {totalItems} total
      </span>
      <div className="flex gap-2">
        <Button variant="secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>
          Previous
        </Button>
        <Button variant="secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
          Next
        </Button>
      </div>
    </nav>
  );
}

/** Native <dialog> provides focus trapping, Escape to close and an inert background. */
export function Dialog({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) {
      return;
    }
    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);
  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onClose={onClose}
      className="m-auto w-full max-w-md rounded-lg p-0 shadow-xl"
    >
      {open && (
        <div className="p-5">
          <div className="mb-4 flex items-start justify-between">
            <h2 id={titleId} className="text-lg font-semibold text-slate-900">
              {title}
            </h2>
            <button
              type="button"
              onClick={onClose}
              className="rounded p-1 text-slate-400 hover:text-slate-600"
              aria-label="Close dialog"
            >
              ✕
            </button>
          </div>
          {children}
        </div>
      )}
    </dialog>
  );
}

export function Field({
  label,
  error,
  hint,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  error?: string | null;
  hint?: string;
}) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      <input
        id={id}
        {...props}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`block w-full rounded-md border px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none ${error ? 'border-rose-400' : 'border-slate-300'}`}
      />
      {error ? (
        <p id={`${id}-error`} className="text-xs text-rose-600">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${id}-hint`} className="text-xs text-slate-500">
            {hint}
          </p>
        )
      )}
    </div>
  );
}

export function SelectField({
  label,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string }) {
  const id = useId();
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      <select
        id={id}
        {...props}
        className="block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none"
      >
        {children}
      </select>
    </div>
  );
}

export function Table({ children, caption }: { children: ReactNode; caption: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <caption className="sr-only">{caption}</caption>
        {children}
      </table>
    </div>
  );
}

export function Th({ children, align = 'left' }: { children?: ReactNode; align?: 'left' | 'right' }) {
  return (
    <th
      scope="col"
      className={`whitespace-nowrap px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500 ${align === 'right' ? 'text-right' : 'text-left'}`}
    >
      {children}
    </th>
  );
}

export function Td({
  children,
  align = 'left',
  className = '',
}: {
  children?: ReactNode;
  align?: 'left' | 'right';
  className?: string;
}) {
  return (
    <td
      className={`whitespace-nowrap px-3 py-2 ${align === 'right' ? 'text-right' : 'text-left'} ${className}`}
    >
      {children}
    </td>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-lg bg-white p-4 shadow-sm ring-1 ring-slate-200">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 text-xl font-semibold text-slate-900">{value}</p>
      {hint && <p className="mt-0.5 text-xs text-slate-500">{hint}</p>}
    </div>
  );
}
