export type Role = 'ADMIN' | 'OPERATIONS' | 'VIEWER';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Session {
  user: { id: string; email: string; displayName: string };
  organization: { id: string; name: string; slug: string };
  role: Role;
  memberships: { organizationId: string; organizationName: string; role: Role }[];
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  session: Session;
}

export type AccountType = 'CUSTOMER' | 'MERCHANT';
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';

export interface Account {
  id: string;
  type: AccountType;
  name: string;
  reference: string;
  status: AccountStatus;
  currency: string;
  availableMinor: number;
  heldMinor: number;
  ledgerBalances: Record<string, number>;
  createdAt: string;
}

export interface LedgerEntry {
  id: string;
  ledgerTransactionId: string;
  transactionType: string;
  description: string;
  sourceType: string;
  sourceId: string;
  ledgerAccountId: string;
  purpose: string;
  amountMinor: number;
  direction: 'DEBIT' | 'CREDIT';
  effectMinor: number;
  balanceAfterMinor: number;
  createdAt: string;
}

export interface LedgerTransaction {
  transaction: {
    id: string;
    type: string;
    currency: string;
    description: string;
    sourceType: string;
    sourceId: string;
    postedAt: string;
    createdBy: string | null;
  };
  entries: {
    id: string;
    ledgerAccountId: string;
    purpose: string;
    financialAccountId: string | null;
    amountMinor: number;
    direction: 'DEBIT' | 'CREDIT';
  }[];
}

export type PaymentStatus =
  | 'AUTHORIZED'
  | 'PARTIALLY_CAPTURED'
  | 'CAPTURED'
  | 'VOIDED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED'
  | 'DISPUTED'
  | 'RESOLVED'
  | 'FAILED';

export type PaymentAction = 'CAPTURE' | 'VOID' | 'REFUND' | 'OPEN_DISPUTE' | 'RESOLVE_DISPUTE';

export interface Payment {
  id: string;
  customerAccountId: string;
  merchantAccountId: string;
  reference: string | null;
  description: string | null;
  currency: string;
  status: PaymentStatus;
  failureCode: string | null;
  authorizedAmountMinor: number;
  capturedAmountMinor: number;
  releasedAmountMinor: number;
  refundedAmountMinor: number;
  disputedAmountMinor: number;
  disputeLostAmountMinor: number;
  remainingAuthorizationMinor: number;
  refundableMinor: number;
  netSettledMinor: number;
  allowedActions: PaymentAction[];
  createdAt: string;
  firstCapturedAt: string | null;
  updatedAt: string;
}

export interface PaymentEvent {
  id: string;
  type: string;
  amountMinor: number;
  fromStatus: PaymentStatus | null;
  toStatus: PaymentStatus;
  ledgerTransactionId: string | null;
  actorUserId: string | null;
  note: string | null;
  createdAt: string;
}

export interface Refund {
  id: string;
  paymentId: string;
  amountMinor: number;
  reason: string | null;
  ledgerTransactionId: string;
  createdAt: string;
}

export interface Deposit {
  id: string;
  accountId: string;
  amountMinor: number;
  memo: string | null;
  ledgerTransactionId: string;
  createdAt: string;
}

export interface Transfer {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amountMinor: number;
  memo: string | null;
  ledgerTransactionId: string;
  createdAt: string;
}

export type DisputeStatus = 'OPEN' | 'WON' | 'LOST';

export interface Dispute {
  id: string;
  paymentId: string;
  amountMinor: number;
  currency: string;
  reason: string;
  status: DisputeStatus;
  openedBy: string | null;
  openedAt: string;
  resolvedBy: string | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
  payment: {
    id: string;
    customerAccountId: string;
    merchantAccountId: string;
    reference: string | null;
    status: PaymentStatus;
    capturedAmountMinor: number;
    refundedAmountMinor: number;
  };
}

export type Classification =
  | 'MATCHED'
  | 'MISSING_INTERNAL'
  | 'MISSING_EXTERNAL'
  | 'AMOUNT_MISMATCH'
  | 'STATUS_MISMATCH'
  | 'DUPLICATE_EXTERNAL';

export interface SettlementBatch {
  id: string;
  fileName: string;
  contentSha256: string;
  periodStart: string;
  periodEnd: string;
  recordCount: number;
  counts: Record<Classification, number>;
  exceptionCount: number;
  uploadedBy: string | null;
  uploadedAt: string;
}

export interface ReconciliationResult {
  id: string;
  sequenceNumber: number;
  classification: Classification;
  rowNumber: number | null;
  processorRecordId: string | null;
  paymentId: string | null;
  expectedStatus: string | null;
  actualStatus: string | null;
  expectedAmountMinor: number | null;
  actualAmountMinor: number | null;
  message: string;
  settledAt: string | null;
}

export interface AuditEvent {
  id: string;
  actorUserId: string | null;
  actorEmail: string | null;
  action: string;
  targetType: string;
  targetId: string | null;
  correlationId: string | null;
  details: Record<string, unknown>;
  occurredAt: string;
}

export interface DashboardSummary {
  customerAvailableMinor: number;
  customerReservedMinor: number;
  merchantAvailableMinor: number;
  merchantDisputeHoldMinor: number;
  externalClearingMinor: number;
  paymentsByStatus: Record<PaymentStatus, number>;
  openDisputes: number;
  recentPayments: Payment[];
}

export interface IntegrityReport {
  healthy: boolean;
  transactionCount: number;
  entryCount: number;
  unbalancedTransactions: number;
  accountsWithBalanceDrift: number;
  clearingBalanceMinor: number;
  totalLiabilitiesMinor: number;
  accountingEquationHolds: boolean;
  checkedAt: string;
}
