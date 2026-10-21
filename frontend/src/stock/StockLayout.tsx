import { NavLink, Outlet } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';

/** Sub-navigation for the stock workspace. */
export default function StockLayout() {
  const tenant = useTenant();
  const t = useNs('stock');
  const base = `/t/${tenant.id}/stock`;
  const tabs = [
    { to: base, label: t('overview'), end: true },
    { to: `${base}/items`, label: t('items') },
    { to: `${base}/locations`, label: t('locations') },
    { to: `${base}/reports`, label: t('reports') },
    { to: `${base}/trading`, label: t('trading.tab') },
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
