import { useEffect, useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';
import { can } from '../lib/nav';
import type { Activity } from './types';

/** Sub-navigation for the workforce section. */
export default function WorkforceLayout() {
  const tenant = useTenant();
  const t = useNs('workforce');
  const base = `/t/${tenant.id}/workforce`;
  const tabs = [
    { to: base, label: t('attendance'), end: true },
    { to: `${base}/workers`, label: t('workers') },
    ...(can(tenant, 'PAYROLL_MANAGE') ? [{ to: `${base}/pay`, label: t('pay') }] : []),
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

/** Crop cycles still in the field, as activities work can be assigned to; empty without crop access. */
export function useActivities(): Activity[] {
  const tenant = useTenant();
  const [list, setList] = useState<Activity[]>([]);
  useEffect(() => {
    if (!can(tenant, 'CROP_READ') || !tenant.enabledModules.includes('CROP')) return;
    api<Page<{ name: string; status: string; costObjectId: number | null }>>(`/api/v1/tenants/${tenant.id}/cultivation-cycles?size=100`)
      .then(p => setList(p.items.filter(c => c.costObjectId != null && c.status !== 'CANCELLED')
        .map(c => ({ costObjectId: c.costObjectId as number, name: c.name }))))
      .catch(() => setList([]));
  }, [tenant]);
  return list;
}
