import { NavLink, Outlet } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';

/** Sub-navigation for the assets section. */
export default function AssetsLayout() {
  const tenant = useTenant();
  const t = useNs('assets');
  const base = `/t/${tenant.id}/assets`;
  const tabs = [
    { to: base, label: t('register'), end: true },
    { to: `${base}/depreciation`, label: t('depreciation') },
    { to: `${base}/maintenance`, label: t('maintenance') },
  ];
  return (
    <section className="crop-page finance-page">
      <nav className="crop-tabs" aria-label={t('title')}>
        {tabs.map(tab => <NavLink key={tab.to} to={tab.to} end={tab.end} className={({ isActive }) => (isActive ? 'active' : '')}>{tab.label}</NavLink>)}
      </nav>
      <Outlet />
    </section>
  );
}
