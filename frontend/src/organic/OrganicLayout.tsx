import { NavLink, Outlet } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';

/** Sub-navigation for the organic manure and compost section (organic resources design, section 9). */
export default function OrganicLayout() {
  const tenant = useTenant();
  const t = useNs('organic');
  const base = `/t/${tenant.id}/organic`;
  const tabs = [{ to: base, label: t('batches'), end: true }, { to: `${base}/reports`, label: t('reports') }];
  return (
    <section className="crop-page finance-page">
      <nav className="crop-tabs" aria-label={t('title')}>
        {tabs.map(tab => <NavLink key={tab.to} to={tab.to} end={tab.end} className={({ isActive }) => (isActive ? 'active' : '')}>{tab.label}</NavLink>)}
      </nav>
      <Outlet />
    </section>
  );
}
