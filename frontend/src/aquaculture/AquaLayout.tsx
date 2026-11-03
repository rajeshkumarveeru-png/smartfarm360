import { NavLink, Outlet } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';

/** Sub-navigation for the fish farming section (aquaculture design, section 11). */
export default function AquaLayout() {
  const tenant = useTenant();
  const t = useNs('aquaculture');
  const base = `/t/${tenant.id}/aquaculture`;
  const tabs = [
    { to: base, label: t('ponds'), end: true },
    { to: `${base}/water`, label: t('water') },
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

/** A species' name: the catalogue name, or the farmer's own for Other (K11). */
export function useSpeciesName(): (s: { species: string | null; otherName: string | null }) => string {
  const t = useNs('aquaculture');
  return s => (s.species == null ? t('mixedSample') : s.otherName ?? t(`species.${s.species}`));
}
