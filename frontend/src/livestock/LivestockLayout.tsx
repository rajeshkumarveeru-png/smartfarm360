import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';
import type { Group } from './types';

/** Which animal section a path is in: poultry and goats and sheep are sections of their own, apart from cattle. */
export function livestockSection(pathname: string): 'poultry' | 'smallStock' | 'cattle' {
  return /\/livestock\/poultry(\/|$)/.test(pathname) ? 'poultry' : /\/livestock\/small-stock(\/|$)/.test(pathname) ? 'smallStock' : 'cattle';
}

/**
 * Sub-navigation for the animal sections (livestock design, section 9). Cows and dairy, poultry, and goats and sheep are
 * kept apart: each section shows only its own pages, so a poultry farmer never sees a herd or milk screen.
 */
export default function LivestockLayout() {
  const tenant = useTenant();
  const t = useNs('livestock');
  const { pathname } = useLocation();
  const base = `/t/${tenant.id}/livestock`;
  const dairy = tenant.enabledModules.includes('DAIRY');
  const section = livestockSection(pathname);
  const tabs = section === 'poultry'
    ? [{ to: `${base}/poultry`, label: t('batchesTab'), end: true }, { to: `${base}/poultry/reports`, label: t('reports') }]
    : section === 'smallStock'
      ? [{ to: `${base}/small-stock`, label: t('herdsTab'), end: true }, { to: `${base}/small-stock/reports`, label: t('reports') }]
      : [
        ...(dairy ? [{ to: base, label: t('herd'), end: true }, { to: `${base}/milk`, label: t('milk') }] : []),
        { to: `${base}/care`, label: t('care') },
        ...(dairy ? [{ to: `${base}/groups`, label: t('groups') }] : []),
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
