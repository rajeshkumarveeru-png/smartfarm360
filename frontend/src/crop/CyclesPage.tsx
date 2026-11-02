import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Sprout } from 'lucide-react';
import { api } from '../api/client';
import type { Farm, Page, Parcel } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Crop, Cycle, Season, Variety } from './types';
import { AREA_UNITS, CYCLE_STATUSES } from './types';

/** Farms with their fields, for cycle forms. */
export function useFarmsAndParcels() {
  const tenant = useTenant();
  const [state, setState] = useState<{ farms: Farm[]; parcels: Parcel[] }>({ farms: [], parcels: [] });
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const farms = await api<Farm[]>(`/api/v1/tenants/${tenant.id}/farms`);
        const parcels = (await Promise.all(farms.map(f => api<Parcel[]>(`/api/v1/tenants/${tenant.id}/farms/${f.id}/land-parcels`)))).flat();
        if (alive) setState({ farms, parcels });
      } catch { /* the page shows its own errors; forms then have no choices */ }
    })();
    return () => { alive = false; };
  }, [tenant.id]);
  return state;
}

/** Cultivation cycles with their stage, and a form to plan a new one. */
export default function CyclesPage() {
  const tenant = useTenant();
  const t = useNs('crop');
  const tc = useNs('common');
  const tu = useNs('units');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const { farms, parcels } = useFarmsAndParcels();
  const [filter, setFilter] = useState({ farmId: '', status: '' });
  const [page, setPage] = useState(0);
  const [cycles, setCycles] = useState<Page<Cycle> | null>(null);
  const [crops, setCrops] = useState<Crop[]>([]);
  const [seasons, setSeasons] = useState<Season[]>([]);
  const [varieties, setVarieties] = useState<Variety[]>([]);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<Record<string, string>>({ plannedSowingDate: today(), areaUnit: 'ACRE' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));

  const load = useCallback(async () => {
    setError('');
    try {
      const q = new URLSearchParams({ page: String(page), size: '25' });
      if (filter.farmId) q.set('farmId', filter.farmId);
      if (filter.status) q.set('status', filter.status);
      setCycles(await api<Page<Cycle>>(`${base}/cultivation-cycles?${q}`));
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, page, filter]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    Promise.all([api<Crop[]>(`${base}/crops`), api<Season[]>(`${base}/seasons`)]).then(([c, s]) => { setCrops(c); setSeasons(s); }).catch(() => undefined);
  }, [base]);
  useEffect(() => {
    if (!form.cropId) { setVarieties([]); return; }
    api<Variety[]>(`${base}/crops/${form.cropId}/varieties`).then(setVarieties).catch(() => setVarieties([]));
  }, [base, form.cropId]);

  const farmParcels = useMemo(() => parcels.filter(p => String(p.farmId) === form.farmId), [parcels, form.farmId]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!form.farmId || !form.landParcelId || !form.cropId || !form.name?.trim()) { setError(tc('required')); return; }
    setBusy(true); setError('');
    try {
      await api(`${base}/cultivation-cycles`, { method: 'POST', body: {
        farmId: Number(form.farmId), landParcelId: Number(form.landParcelId), cropId: Number(form.cropId),
        varietyId: form.varietyId ? Number(form.varietyId) : null, seasonId: form.seasonId ? Number(form.seasonId) : null, name: form.name.trim(),
        plannedSowingDate: form.plannedSowingDate, expectedHarvestDate: form.expectedHarvestDate || null,
        cultivatedArea: form.cultivatedArea || null, areaUnit: form.areaUnit, expectedYield: form.expectedYield || null,
      } });
      setOpen(false); setForm({ plannedSowingDate: today(), areaUnit: 'ACRE' });
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('title')}</h2><p>{t('sub')}</p></div>
        {can(tenant, 'CROP_WRITE') && !open && <button type="button" className="primary" onClick={() => setOpen(true)}><Plus size={16} aria-hidden />{t('newCycle')}</button>}
      </div>
      <ErrorBanner message={error} />
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => void create(e)}>
          <label>{t('cycleName')}<input required maxLength={120} value={form.name ?? ''} placeholder={t('cyclePlaceholder')} onChange={e => set('name', e.target.value)} /></label>
          <label>{t('farm')}<select value={form.farmId ?? ''} onChange={e => { set('farmId', e.target.value); set('landParcelId', ''); }}>
            <option value="">—</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
          <label>{t('land')}<select value={form.landParcelId ?? ''} onChange={e => set('landParcelId', e.target.value)}>
            <option value="">—</option>{farmParcels.map(p => <option key={p.id} value={p.id}>{p.name} · {formatNumber(p.area, lang)} {tu(p.areaUnit)}</option>)}</select></label>
          <label>{t('crop')}<select value={form.cropId ?? ''} onChange={e => { set('cropId', e.target.value); set('varietyId', ''); }}>
            <option value="">—</option>{crops.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
          <label>{t('varietyLabel')}<select value={form.varietyId ?? ''} onChange={e => set('varietyId', e.target.value)}>
            <option value="">—</option>{varieties.map(v => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label>
          <label>{t('season')}<select value={form.seasonId ?? ''} onChange={e => set('seasonId', e.target.value)}>
            <option value="">—</option>{seasons.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
          <label>{t('sowing')}<input type="date" required value={form.plannedSowingDate ?? ''} onChange={e => set('plannedSowingDate', e.target.value)} /></label>
          <label>{t('expected')} <small>{tc('optional')}</small><input type="date" min={form.plannedSowingDate} value={form.expectedHarvestDate ?? ''} onChange={e => set('expectedHarvestDate', e.target.value)} /></label>
          <div className="inline-fields">
            <label>{t('cultivatedArea')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.cultivatedArea ?? ''} onChange={e => set('cultivatedArea', e.target.value)} /></label>
            <label>{t('unit')}<select value={form.areaUnit} onChange={e => set('areaUnit', e.target.value)}>{AREA_UNITS.map(u => <option key={u} value={u}>{tu(u)}</option>)}</select></label>
          </div>
          <label>{t('expectedYield')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.expectedYield ?? ''} onChange={e => set('expectedYield', e.target.value)} /></label>
          <p className="hint full">{t('areaHelp')}</p>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary" disabled={busy}>{busy ? tc('loading') : tc('save')}</button>
          </div>
        </form>
      )}
      <div className="form-card form-grid filters">
        <label>{t('farm')}<select value={filter.farmId} onChange={e => { setPage(0); setFilter({ ...filter, farmId: e.target.value }); }}>
          <option value="">{t('allFarms')}</option>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
        <label>{t('status')}<select value={filter.status} onChange={e => { setPage(0); setFilter({ ...filter, status: e.target.value }); }}>
          <option value="">{t('allStages')}</option>{CYCLE_STATUSES.map(s => <option key={s} value={s}>{t(`stage.${s}`)}</option>)}</select></label>
      </div>
      {!cycles ? <Loading /> : cycles.items.length === 0 ? <p className="hint">{t('noCycles')}</p> : (
        <>
          <div className="activity-list">
            {cycles.items.map(c => (
              <article className="activity-row cycle-row" key={c.id}>
                <div className="record-icon" aria-hidden><Sprout size={20} /></div>
                <div className="cycle-main">
                  <Link to={`/t/${tenant.id}/crops/cycles/${c.id}`}><b>{c.name}</b></Link>
                  <small> · {c.cropName}{c.varietyName ? ` (${c.varietyName})` : ''} · {c.farmName} / {c.parcelName}</small>
                  <div><small>{t('sowing')}: {formatDate(c.actualSowingDate ?? c.plannedSowingDate, lang)}
                    {c.daysSinceSowing != null ? ` · ${t('daysSinceSowing', { days: String(c.daysSinceSowing) })}` : ''}
                    {c.areaAcres != null ? ` · ${formatNumber(c.areaAcres, lang, 2)} ${tu('ACRE')}` : ''}</small></div>
                  {c.warnings.includes('FIELD_OVERCOMMITTED') && <small className="low-stock-label">{t('warning.FIELD_OVERCOMMITTED')}</small>}
                </div>
                <div className="value-side"><span className={`stage stage-${c.status.toLowerCase()}`}>{t(`stage.${c.status}`)}</span></div>
              </article>
            ))}
          </div>
          {cycles.totalPages > 1 && (
            <div className="form-actions">
              <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>{tc('previous')}</button>
              <button type="button" className="secondary" disabled={page + 1 >= cycles.totalPages} onClick={() => setPage(p => p + 1)}>{tc('next')}</button>
            </div>
          )}
        </>
      )}
    </>
  );
}
