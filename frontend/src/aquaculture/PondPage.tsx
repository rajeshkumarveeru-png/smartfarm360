import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { PondFields, pondBody, pondFormOk, type PondForm } from './PondsPage';
import type { Cycle, Pond, Reading } from './types';
import { LimitsEditor, ReadingForm, ReadingsTable } from './WaterPage';

const daysAgo = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };

/** One pond: details, its crops, water readings and its own limits (aquaculture design, sections 3, 4 and 7). */
export default function PondPage() {
  const { pondId } = useParams();
  const tenant = useTenant();
  const navigate = useNavigate();
  const t = useNs('aquaculture');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/aquaculture`;
  const canWrite = can(tenant, 'AQUACULTURE_WRITE');
  const [pond, setPond] = useState<Pond | null>(null);
  const [cycles, setCycles] = useState<Cycle[]>([]);
  const [readings, setReadings] = useState<Reading[]>([]);
  const [farms, setFarms] = useState<Farm[]>([]);
  const [edit, setEdit] = useState<PondForm | null>(null);
  const [start, setStart] = useState({ name: '', startedOn: today(), plannedHarvest: '' });
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [p, c, r] = await Promise.all([api<Pond>(`${base}/ponds/${pondId}`), api<Cycle[]>(`${base}/cycles?pondId=${pondId}`),
        api<Reading[]>(`${base}/readings?pondId=${pondId}&from=${daysAgo(30)}&to=${today()}`)]);
      setPond(p); setCycles(c); setReadings(r);
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, pondId]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (canWrite) api<Farm[]>(`/api/v1/tenants/${tenant.id}/farms`).then(setFarms).catch(() => undefined); }, [canWrite, tenant.id]);

  async function savePond(e: React.SyntheticEvent, active?: boolean) {
    e.preventDefault();
    if (!pond || !edit || !pondFormOk(edit)) { setError(t('errors.pond')); return; }
    setError(''); setMessage('');
    try {
      setPond(await api<Pond>(`${base}/ponds/${pond.id}`, { method: 'PUT', body: { ...pondBody(edit), active: active ?? pond.active } }));
      setEdit(null);
      setMessage(t('pondSaved'));
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function startCycle(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    try {
      const c = await api<Cycle>(`${base}/ponds/${pondId}/cycles`, { method: 'POST', body: { name: start.name.trim() || null, startedOn: start.startedOn,
        plannedHarvest: start.plannedHarvest || null } });
      navigate(`/t/${tenant.id}/aquaculture/cycles/${c.id}`);
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (!pond) return error ? <ErrorBanner message={error} /> : <Loading />;
  const toForm = (): PondForm => ({ name: pond.name, farmId: String(pond.farmId), kind: pond.kind, waterArea: String(pond.waterArea), areaUnit: pond.areaUnit,
    depthM: pond.depthM == null ? '' : String(pond.depthM) });
  return (
    <>
      <div className="crop-page-head">
        <div>
          <p><Link to={`/t/${tenant.id}/aquaculture`}>{t('ponds')}</Link></p>
          <h2>{pond.name}{!pond.active && <small> · {t('inactive')}</small>}</h2>
          <p>{t(`kind.${pond.kind}`)} · {pond.farmName} · {t('areaAcres', { acres: formatNumber(pond.areaAcres, lang, 2) })}
            {pond.depthM != null && ` · ${t('depthValue', { metres: formatNumber(pond.depthM, lang, 2) })}`}</p>
        </div>
        {canWrite && !edit && <button type="button" className="secondary" onClick={() => setEdit(toForm())}>{tc('edit')}</button>}
      </div>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />
      {edit && (
        <form className="form-card form-grid" noValidate onSubmit={e => void savePond(e)}>
          <PondFields form={edit} set={p => setEdit(f => (f ? { ...f, ...p } : f))} farms={farms} />
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setEdit(null)}>{tc('cancel')}</button>
            <button type="button" className="secondary" onClick={e => void savePond(e, !pond.active)}>{t(pond.active ? 'makeInactive' : 'makeActive')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}

      <h3 className="section-space">{t('crops')}</h3>
      {cycles.length === 0 ? <p className="hint">{t('noCycles')}</p> : (
        <div className="table-scroll"><table className="data-table">
          <thead><tr><th>{t('cycle')}</th><th>{t('statusLabel')}</th><th>{t('stocked')}</th><th>{t('harvested')}</th></tr></thead>
          <tbody>{cycles.map(c => (
            <tr key={c.id}>
              <td><Link to={`/t/${tenant.id}/aquaculture/cycles/${c.id}`}>{c.name}</Link></td><td>{t(`status.${c.status}`)}</td>
              <td>{c.stockedOn ? formatDate(c.stockedOn, lang) : '—'}</td><td>{c.harvestedOn ? formatDate(c.harvestedOn, lang) : '—'}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {canWrite && pond.active && !pond.openCycle && (
        <form className="form-card form-grid" noValidate onSubmit={e => void startCycle(e)}>
          <h4 className="full">{t('startCycle')}</h4>
          <label>{t('cycleName')} <small>{tc('optional')}</small><input maxLength={140} placeholder={`${pond.name} – ${start.startedOn.slice(0, 4)}`} value={start.name}
                                                                     onChange={e => setStart({ ...start, name: e.target.value })} /></label>
          <label>{t('startedOn')}<input type="date" max={today()} value={start.startedOn} onChange={e => setStart({ ...start, startedOn: e.target.value })} /></label>
          <label>{t('plannedHarvest')} <small>{tc('optional')}</small><input type="date" value={start.plannedHarvest}
                                                                            onChange={e => setStart({ ...start, plannedHarvest: e.target.value })} /></label>
          <p className="hint full">{t('startCycleHelp')}</p>
          <div className="form-actions full"><button className="primary">{t('startCycle')}</button></div>
        </form>
      )}

      <h3 className="section-space">{t('water')}</h3>
      {canWrite && pond.active && <ReadingForm pondId={pond.id} onSaved={r => {
        setReadings(x => [r, ...x]);
        setMessage(r.outOfLimits.length > 0 ? t('readingFlagged', { parameters: r.outOfLimits.map(p => t(`parameter.${p}`)).join(', ') }) : t('readingSaved'));
      }} />}
      <p className="hint">{t('last30Days')}</p>
      <ReadingsTable readings={readings} showPond={false} onDelete={canWrite ? r => {
        if (!window.confirm(t('confirmRemoveReading'))) return;
        api(`${base}/readings/${r.id}`, { method: 'DELETE' }).then(() => setReadings(x => x.filter(y => y.id !== r.id))).catch(e => setError(describeError(e)));
      } : undefined} />
      <h3 className="section-space">{t('pondLimits')}</h3>
      <LimitsEditor pondId={pond.id} />
    </>
  );
}
