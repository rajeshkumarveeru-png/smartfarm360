import { NavLink, Outlet } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';

/** Sub-navigation for the crop workspace. */
export default function CropLayout() {
  const tenant = useTenant();
  const t = useNs('crop');
  const base = `/t/${tenant.id}/crops`;
  const tabs = [
    { to: base, label: t('cycles'), end: true },
    { to: `${base}/setup`, label: t('setup') },
    { to: `${base}/weather`, label: t('weather') },
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
