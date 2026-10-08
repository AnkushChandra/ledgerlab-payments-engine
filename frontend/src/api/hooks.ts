import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiRequest, toQuery } from './client';
import type {
  Account,
  AccountStatus,
  AccountType,
  AuditEvent,
  Classification,
  DashboardSummary,
  Deposit,
  Dispute,
  DisputeStatus,
  IntegrityReport,
  LedgerEntry,
  LedgerTransaction,
  Page,
  Payment,
  PaymentEvent,
  PaymentStatus,
  ReconciliationResult,
  Refund,
  SettlementBatch,
  Transfer,
} from './types';

type Params = Record<string, string | number | string[] | undefined | null>;

function useList<T>(key: string, path: string, params: Params, enabled = true) {
  return useQuery({
    queryKey: [key, params],
    queryFn: ({ signal }) => apiRequest<Page<T>>(`${path}${toQuery(params)}`, { signal }),
    placeholderData: keepPreviousData,
    enabled,
  });
}

function useOne<T>(key: string, path: string, enabled = true) {
  return useQuery({
    queryKey: [key, path],
    queryFn: ({ signal }) => apiRequest<T>(path, { signal }),
    enabled,
  });
}

export const useDashboard = () => useOne<DashboardSummary>('dashboard', '/dashboard/summary');
export const useIntegrity = () => useOne<IntegrityReport>('integrity', '/ledger/integrity');

export const useAccounts = (params: {
  type?: AccountType;
  status?: AccountStatus;
  q?: string;
  page?: number;
  size?: number;
}) => useList<Account>('accounts', '/accounts', params);
export const useAccount = (id: string) => useOne<Account>('account', `/accounts/${id}`);
export const useAccountEntries = (id: string, page: number) =>
  useList<LedgerEntry>('account-entries', `/accounts/${id}/ledger-entries`, { page, size: 15 });
export const useLedgerTransaction = (id: string | null) =>
  useOne<LedgerTransaction>('ledger-transaction', `/ledger/transactions/${id}`, id !== null);

export const usePayments = (params: {
  status?: PaymentStatus[];
  q?: string;
  customerAccountId?: string;
  merchantAccountId?: string;
  page?: number;
  size?: number;
  sort?: string;
}) => useList<Payment>('payments', '/payments', params);
export const usePayment = (id: string) => useOne<Payment>('payment', `/payments/${id}`);
export const usePaymentEvents = (id: string) =>
  useOne<PaymentEvent[]>('payment-events', `/payments/${id}/events`);
export const usePaymentRefunds = (id: string) =>
  useOne<Refund[]>('payment-refunds', `/payments/${id}/refunds`);
export const usePaymentDisputes = (id: string) =>
  useOne<Dispute[]>('payment-disputes', `/payments/${id}/disputes`);

export const useDeposits = (params: { accountId?: string; page?: number }) =>
  useList<Deposit>('deposits', '/deposits', { ...params, size: 10 });
export const useTransfers = (params: { accountId?: string; page?: number }) =>
  useList<Transfer>('transfers', '/transfers', { ...params, size: 10 });

export const useDisputes = (params: { status?: DisputeStatus; page?: number }) =>
  useList<Dispute>('disputes', '/disputes', params);
export const useDispute = (id: string) => useOne<Dispute>('dispute', `/disputes/${id}`);

export const useSettlementBatches = (page: number) =>
  useList<SettlementBatch>('settlement-batches', '/settlement-batches', { page, size: 10 });
export const useSettlementBatch = (id: string) =>
  useOne<SettlementBatch>('settlement-batch', `/settlement-batches/${id}`);
export const useReconciliationResults = (id: string, classification: Classification[], page: number) =>
  useList<ReconciliationResult>('reconciliation-results', `/settlement-batches/${id}/results`, {
    classification,
    page,
    size: 25,
  });

export const useAuditEvents = (params: {
  action?: string;
  targetType?: string;
  targetId?: string;
  page?: number;
}) => useList<AuditEvent>('audit-events', '/audit-events', { ...params, size: 25 });

/**
 * Mutation that sends an Idempotency-Key and refreshes every cached query on success, since a
 * single money movement can change balances, payments, disputes and the audit trail at once.
 */
export function useIdempotentMutation<TBody, TResult>(path: string | ((body: TBody) => string)) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: TBody; idempotencyKey: string }) =>
      apiRequest<TResult>(typeof path === 'function' ? path(body) : path, {
        method: 'POST',
        body,
        idempotencyKey,
      }),
    onSuccess: () => queryClient.invalidateQueries(),
  });
}

export function usePatch<TBody, TResult>(path: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: TBody) => apiRequest<TResult>(path, { method: 'PATCH', body }),
    onSuccess: () => queryClient.invalidateQueries(),
  });
}
