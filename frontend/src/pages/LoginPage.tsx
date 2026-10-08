import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Button, ErrorAlert, Field } from '../components/ui';

export function LoginPage() {
  const { session, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  if (session) {
    return <Navigate to="/" replace />;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <span className="inline-flex h-12 w-12 items-center justify-center rounded-xl bg-indigo-600 text-xl font-bold text-white">
            L
          </span>
          <h1 className="mt-3 text-2xl font-semibold">Sign in to LedgerLab</h1>
          <p className="mt-1 text-sm text-slate-500">Payments, double-entry ledger and reconciliation</p>
        </div>
        <form
          onSubmit={submit}
          className="space-y-4 rounded-lg bg-white p-6 shadow-sm ring-1 ring-slate-200"
          noValidate
        >
          <Field
            label="Email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <Field
            label="Password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <ErrorAlert error={error} title="Sign-in failed" />
          <Button type="submit" className="w-full" busy={busy} disabled={!email || !password}>
            Sign in
          </Button>
        </form>
        <div className="mt-4 rounded-md bg-white/70 p-3 text-xs text-slate-600 ring-1 ring-slate-200">
          <p className="font-medium">Local demo users (development data only)</p>
          <p className="mt-1">
            <span className="font-mono">admin@acme.test</span>,{' '}
            <span className="font-mono">ops@acme.test</span>,{' '}
            <span className="font-mono">viewer@acme.test</span>,{' '}
            <span className="font-mono">admin@globex.test</span>
          </p>
          <p>
            Password: <span className="font-mono">LedgerLab!2026</span>
          </p>
        </div>
      </div>
    </div>
  );
}
