import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber } from '../lib/format';
import { can } from '../lib/nav';
import type { Pond } from './types';

export const AREA_UNITS = ['ACRE', 'HECTARE', 'CENT', 'SQFT'];
export const POND_KINDS = ['EARTHEN', 'LINED', 'TANK', 'CAGE'];
export const emptyPond = () => ({ name: '', farmId: '', kind: 'EARTHEN', waterArea: '', areaUnit: 'ACRE', depthM: '' });
export type PondForm = ReturnType<typeof emptyPond>;
export const pondFormOk = (f: PondForm) => f.name.trim() !== '' && f.farmId !== '' && /^\d{1,8}(\.\d{1,4})?$/.test(f.waterArea.trim()) && Number(f.waterArea) > 0
  && (f.depthM.trim() === '' || /^\d{1,4}(\.\d{1,2})?$/.test(f.depthM.trim()));
export const pondBody = (f: PondForm) => ({ name: f.name.trim(), farmId: Number(f.farmId), kind: f.kind, waterArea: f.waterArea.trim(), areaUnit: f.areaUnit,
  depthM: f.depthM.trim() || null });

/** The pond form, shared by adding a pond and changing one (K1). */
export function PondFields({ form, set, farms }: { form: PondForm; set: (p: Partial<PondForm>) => void; farms: Farm[] }) {
  const t = useNs('aquaculture');
  const tc = useNs('common');
  const tu = useNs('units');
  return <>
    <label>{t('pondName')}<input maxLength={120} placeholder={t('pondExample')} value={form.name} onChange={e => set({ name: e.target.value })} /></label>
    <label>{t('farm')}<select value={form.farmId} onChange={e => set({ farmId: e.target.value })}>
      <option value="">—</option>{farms.filter(f => f.active).map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
    <label>{t('kindLabel')}<select value={form.kind} onChange={e => set({ kind: e.target.value })}>
      {POND_KINDS.map(k => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}</select></label>
    <label>{t('waterArea')}<input inputMode="decimal" value={form.waterArea} onChange={e => set({ waterArea: e.target.value })} /></label>
    <label>{t('areaUnit')}<select value={form.areaUnit} onChange={e => set({ areaUnit: e.target.value })}>
      {AREA_UNITS.map(u => <option key={u} value={u}>{tu(u)}</option>)}</select></label>
    <label>{t('depth')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.depthM} onChange={e => set({ depthM: e.target.value })} /></label>
  </>;
}

/** The farm business's ponds with the crop in each and the latest water reading (aquaculture design, section 11). */
export default function PondsPage() {
  const tenant = useTenant();
  const t = useNs('aquaculture');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const canWrite = can(tenant, 'AQUACULTURE_WRITE');
  const [ponds, setPonds] = useState<Pond[] | null>(null);
  const [farms, setFarms] = useState<Farm[]>([]);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(emptyPond);
  const [error, setError] = useState('');

  const load = useCallback(() => api<Pond[]>(`${base}/aquaculture/ponds`).then(setPonds).catch(e => setError(describeError(e))), [base]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (canWrite) api<Farm[]>(`${base}/farms`).then(f => { setFarms(f); if (f.length === 1) setForm(x => ({ ...x, farmId: String(f[0].id) })); }).catch(() => undefined);
  }, [base, canWrite]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!pondFormOk(form)) { setError(t('errors.pond')); return; }
    setError('');
    try {
      await api(`${base}/aquaculture/ponds`, { method: 'POST', body: pondBody(form) });
      setOpen(false); setForm(emptyPond());
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('ponds')}</h2><p>{t('pondsHelp')}</p></div>
        {canWrite && !open && <button type="button" className="primary" onClick={() => setOpen(true)}><Plus size={16} aria-hidden />{t('addPond')}</button>}
      </div>
      <ErrorBanner message={error} />
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => void create(e)}>
          <PondFields form={form} set={p => setForm(f => ({ ...f, ...p }))} farms={farms} />
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}
      {!ponds ? <Loading /> : ponds.length === 0 ? <p className="hint">{t('noPonds')}</p> : (
        <table className="data-table">
          <thead><tr><th>{t('pondName')}</th><th className="num">{t('waterAreaAcres')}</th><th>{t('crop')}</th><th>{t('latestReading')}</th></tr></thead>
          <tbody>{ponds.map(p => (
            <tr key={p.id} className={p.active ? undefined : 'muted'}>
              <td><Link to={`/t/${tenant.id}/aquaculture/ponds/${p.id}`}>{p.name}</Link><small> · {t(`kind.${p.kind}`)} · {p.farmName}</small>
                {!p.active && <small> · {t('inactive')}</small>}</td>
              <td className="num">{formatNumber(p.areaAcres, lang, 2)}</td>
              <td>{p.openCycle ? <><Link to={`/t/${tenant.id}/aquaculture/cycles/${p.openCycle.id}`}>{p.openCycle.name}</Link>
                <small> · {t(`status.${p.openCycle.status}`)}{p.openCycle.stockedOn && ` · ${t('stockedOn', { date: formatDate(p.openCycle.stockedOn, lang) })}`}</small></> : '—'}</td>
              <td>{p.latestReading ? <span className={p.latestReading.outOfLimits.length > 0 ? 'low-stock-label' : undefined}>
                {formatDate(p.latestReading.takenAt.slice(0, 10), lang)}
                {p.latestReading.outOfLimits.length > 0 && ` · ${p.latestReading.outOfLimits.map(x => t(`parameter.${x}`)).join(', ')} ${t('outsideLimits')}`}</span> : '—'}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </>
  );
}
