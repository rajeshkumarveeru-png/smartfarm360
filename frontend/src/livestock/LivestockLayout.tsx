import { useEffect, useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';
import type { Group } from './types';

/** Sub-navigation for the livestock section (livestock design, section 9). */
export default function LivestockLayout() {
  const tenant = useTenant();
  const t = useNs('livestock');
  const base = `/t/${tenant.id}/livestock`;
  const tabs = [
    { to: base, label: t('herd'), end: true },
    { to: `${base}/milk`, label: t('milk') },
    { to: `${base}/care`, label: t('care') },
    { to: `${base}/groups`, label: t('groups') },
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

/** The tenant's animal groups (empty while loading or without access). */
export function useGroups(includeClosed = false): { groups: Group[]; reload: () => void } {
  const tenant = useTenant();
  const [groups, setGroups] = useState<Group[]>([]);
  const [n, setN] = useState(0);
  useEffect(() => {
    api<Group[]>(`/api/v1/tenants/${tenant.id}/livestock/groups`).then(g => setGroups(includeClosed ? g : g.filter(x => x.active))).catch(() => setGroups([]));
  }, [tenant.id, includeClosed, n]);
  return { groups, reload: () => setN(x => x + 1) };
}
