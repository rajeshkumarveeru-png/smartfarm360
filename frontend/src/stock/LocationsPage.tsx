import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { can } from '../lib/nav';
import type { Location } from './types';

/** Where stock is kept. Every farm business has a default location. */
export default function LocationsPage() {
  const tenant = useTenant();
  const t = useNs('stock');
  const tf = useNs('fin');
  const tc = useNs('common');
  const base = `/api/v1/tenants/${tenant.id}/stock/locations`;
  const canWrite = can(tenant, 'INVENTORY_WRITE');
  const [locations, setLocations] = useState<Location[] | null>(null);
  const [farms, setFarms] = useState<Farm[]>([]);
  const [form, setForm] = useState({ name: '', farmId: '' });
  const [error, setError] = useState('');

  const load = () => api<Location[]>(base).then(setLocations).catch(e => setError(describeError(e)));
  useEffect(() => { void load(); }, [base]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (can(tenant, 'FARM_READ')) api<Farm[]>(`/api/v1/tenants/${tenant.id}/farms`).then(setFarms).catch(() => setFarms([]));
  }, [tenant]);

  async function run(action: () => Promise<unknown>) {
    setError('');
    try { await action(); await load(); } catch (e) { setError(describeError(e)); }
  }

  if (!locations) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('locations')}</h2><p>{t('locationsHelp')}</p></div></div>
      <ErrorBanner message={error} />
      {canWrite && (
        <form className="form-card form-grid" onSubmit={e => {
          e.preventDefault();
          if (!form.name.trim()) { setError(tc('required')); return; }
          void run(async () => {
            await api(base, { method: 'POST', body: { name: form.name.trim(), farmId: form.farmId ? Number(form.farmId) : null } });
            setForm({ name: '', farmId: '' });
          });
        }}>
          <label>{t('name')}<input required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          {farms.length > 0 && <label>{tf('farm')} <small>{tc('optional')}</small><select value={form.farmId} onChange={e => setForm({ ...form, farmId: e.target.value })}>
            <option value="">—</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>}
          <div className="form-actions full"><button className="primary">{t('addLocation')}</button></div>
        </form>
      )}
      <div className="activity-list">
        {locations.map(l => (
          <article className="activity-row" key={l.id}>
            <div className="cycle-main"><b>{l.name}</b>{l.isDefault && <small> · {t('defaultLocation')}</small>}{!l.active && <small> · {tf('inactive')}</small>}
              {l.farmId && <small> · {farms.find(f => f.id === l.farmId)?.name}</small>}</div>
            {canWrite && (
              <div className="value-side">
                {!l.isDefault && l.active && <button type="button" className="text-action" onClick={() => void run(() => api(`${base}/${l.id}`, { method: 'PATCH', body: { makeDefault: true } }))}>{t('makeDefault')}</button>}
                {!l.isDefault && <button type="button" className="text-action" onClick={() => void run(() => api(`${base}/${l.id}`, { method: 'PATCH', body: { active: !l.active } }))}>
                  {l.active ? tf('deactivate') : tf('activate')}</button>}
              </div>
            )}
          </article>
        ))}
      </div>
    </>
  );
}
