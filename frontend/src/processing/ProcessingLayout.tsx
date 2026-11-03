import { NavLink, Outlet } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';

/** Sub-navigation for the processing section (processing and trading design, section 11). */
export default function ProcessingLayout() {
  const tenant = useTenant();
  const t = useNs('processing');
  const base = `/t/${tenant.id}/processing`;
  const tabs = [
    { to: base, label: t('batches'), end: true },
    { to: `${base}/trace`, label: t('trace') },
    { to: `${base}/reports`, label: t('reports') },
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
