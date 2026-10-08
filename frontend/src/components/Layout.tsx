import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Button } from './ui';

const links = [
  { to: '/', label: 'Overview', end: true },
  { to: '/accounts', label: 'Accounts' },
  { to: '/payments', label: 'Payments' },
  { to: '/money-movement', label: 'Deposits & transfers' },
  { to: '/disputes', label: 'Disputes' },
  { to: '/settlements', label: 'Reconciliation' },
  { to: '/audit', label: 'Audit trail', role: 'OPERATIONS' as const },
];

export function Layout() {
  const { session, logout, can } = useAuth();
  return (
    <div className="flex min-h-screen">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2"
      >
        Skip to content
      </a>
      <aside className="hidden w-60 shrink-0 flex-col border-r border-slate-200 bg-white md:flex">
        <div className="flex items-center gap-2 px-5 py-5">
          <span className="flex h-8 w-8 items-center justify-center rounded-md bg-indigo-600 font-bold text-white">
            L
          </span>
          <span className="text-lg font-semibold">LedgerLab</span>
        </div>
        <nav aria-label="Main" className="flex-1 space-y-0.5 px-3">
          {links
            .filter((link) => !link.role || can(link.role))
            .map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className={({ isActive }) =>
                  `block rounded-md px-3 py-2 text-sm font-medium ${isActive ? 'bg-indigo-50 text-indigo-700' : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'}`
                }
              >
                {link.label}
              </NavLink>
            ))}
        </nav>
        <div className="border-t border-slate-200 p-4 text-sm">
          <p className="font-medium text-slate-800">{session?.user.displayName}</p>
          <p className="truncate text-slate-500">{session?.organization.name}</p>
          <p
            className="mt-1 inline-block rounded bg-slate-100 px-1.5 py-0.5 text-xs font-medium text-slate-600"
            data-testid="role-badge"
          >
            {session?.role}
          </p>
          <Button variant="ghost" className="mt-3 w-full" onClick={logout}>
            Sign out
          </Button>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-3 md:hidden">
          <span className="font-semibold">LedgerLab</span>
          <Button variant="ghost" onClick={logout}>
            Sign out
          </Button>
        </header>
        <nav
          aria-label="Main mobile"
          className="flex gap-1 overflow-x-auto border-b border-slate-200 bg-white px-2 py-2 md:hidden"
        >
          {links
            .filter((link) => !link.role || can(link.role))
            .map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className="whitespace-nowrap rounded px-2 py-1 text-sm text-slate-600"
              >
                {link.label}
              </NavLink>
            ))}
        </nav>
        <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 md:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
