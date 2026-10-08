import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { AccountDetailPage } from './pages/AccountDetailPage';
import { AccountsPage } from './pages/AccountsPage';
import { AuditPage } from './pages/AuditPage';
import { DashboardPage } from './pages/DashboardPage';
import { DisputeDetailPage } from './pages/DisputeDetailPage';
import { DisputesPage } from './pages/DisputesPage';
import { LoginPage } from './pages/LoginPage';
import { MoneyMovementPage } from './pages/MoneyMovementPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { PaymentDetailPage } from './pages/PaymentDetailPage';
import { PaymentsPage } from './pages/PaymentsPage';
import { SettlementDetailPage } from './pages/SettlementDetailPage';
import { SettlementsPage } from './pages/SettlementsPage';

function RequireAuth({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const location = useLocation();
  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="accounts" element={<AccountsPage />} />
        <Route path="accounts/:accountId" element={<AccountDetailPage />} />
        <Route path="payments" element={<PaymentsPage />} />
        <Route path="payments/:paymentId" element={<PaymentDetailPage />} />
        <Route path="money-movement" element={<MoneyMovementPage />} />
        <Route path="disputes" element={<DisputesPage />} />
        <Route path="disputes/:disputeId" element={<DisputeDetailPage />} />
        <Route path="settlements" element={<SettlementsPage />} />
        <Route path="settlements/:batchId" element={<SettlementDetailPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
