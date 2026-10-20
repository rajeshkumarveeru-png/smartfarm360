import { NavLink, Outlet } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';
import { can } from '../lib/nav';

/** Sub-navigation for the finance workspace. */
export default function FinanceLayout() {
  const tenant = useTenant();
  const t = useNs('fin');
  const base = `/t/${tenant.id}/finance`;
  const tabs = [
    { to: base, label: t('overview'), end: true },
    { to: `${base}/records`, label: t('records') },
    { to: `${base}/parties`, label: t('parties') },
    { to: `${base}/accounts`, label: t('accounts') },
    { to: `${base}/reports`, label: t('reports') },
    ...(can(tenant, 'FINANCE_MANAGE') ? [{ to: `${base}/settings`, label: t('settings') }] : []),
  ];
  return (
    <section className="crop-page finance-page">
      <nav className="crop-tabs" aria-label={t('title')}>
        {tabs.map(tab => (
          <NavLink key={tab.to} to={tab.to} end={tab.end} className={({ isActive }) => (isActive ? 'active' : '')}>{tab.label}</NavLink>
        ))}
      </nav>
      <Outlet />
    </section>
  );
}
