import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatDateTime, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar, QuickRanges } from '../reports/ReportTools';
import { FIELD, PARAMETERS, type Limit, type Limits, type Parameter, type Pond, type Reading } from './types';

const decimal = (v: string) => v.trim() === '' || /^-?\d{1,5}(\.\d{1,3})?$/.test(v.trim());
const nowLocal = () => { const d = new Date(); d.setSeconds(0, 0); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16); };
const emptyReading = () => ({ takenAt: nowLocal(), ...Object.fromEntries(PARAMETERS.map(p => [p, ''])), notes: '' }) as Record<Parameter | 'takenAt' | 'notes', string>;

/** A water reading form for one pond (K7); the out-of-limit flags come back with the saved reading. */
export function ReadingForm({ pondId, onSaved }: { pondId: number; onSaved: (r: Reading) => void }) {
  const tenant = useTenant();
  const t = useNs('aquaculture');
  const tc = useNs('common');
  const [form, setForm] = useState(emptyReading);
  const [error, setError] = useState('');
  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!PARAMETERS.every(p => decimal(form[p])) || PARAMETERS.every(p => form[p].trim() === '')) { setError(t('errors.reading')); return; }
    setError('');
    try {
      const body: Record<string, unknown> = { takenAt: new Date(form.takenAt).toISOString(), notes: form.notes.trim() || null };
      for (const p of PARAMETERS) body[FIELD[p]] = form[p].trim() || null;
      const r = await api<Reading>(`/api/v1/tenants/${tenant.id}/aquaculture/ponds/${pondId}/readings`, { method: 'POST', body });
      setForm(emptyReading());
      onSaved(r);
    } catch (err) {
      setError(describeError(err));
    }
  }
  return (
    <form className="form-card form-grid" noValidate onSubmit={e => void save(e)}>
      <ErrorBanner message={error} />
      <label>{t('takenAt')}<input type="datetime-local" value={form.takenAt} onChange={e => setForm({ ...form, takenAt: e.target.value })} /></label>
      {PARAMETERS.map(p => (
        <label key={p}>{t(`parameter.${p}`)} <small>{t(`unit.${p}`)}</small>
          <input inputMode="decimal" value={form[p]} onChange={e => setForm({ ...form, [p]: e.target.value })} /></label>
      ))}
      <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={300} placeholder={t('notesExample')} value={form.notes}
                                                                                 onChange={e => setForm({ ...form, notes: e.target.value })} /></label>
      <div className="form-actions full"><button className="secondary">{t('recordReading')}</button></div>
    </form>
  );
}

/** The farmer's own limits (K7), for the farm business ({@code pondId} null) or one pond. None are filled in by the app. */
export function LimitsEditor({ pondId }: { pondId: number | null }) {
  const tenant = useTenant();
  const t = useNs('aquaculture');
  const canWrite = can(tenant, 'AQUACULTURE_WRITE');
  const path = `/api/v1/tenants/${tenant.id}/aquaculture/water-limits`;
  const [limits, setLimits] = useState<Limits | null>(null);
  const [form, setForm] = useState<Record<string, { lowest: string; highest: string }>>({});
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const fill = (l: Limits) => {
    setLimits(l);
    const own = pondId == null ? l.defaults : l.pond;
    setForm(Object.fromEntries(PARAMETERS.map(p => { const x = own.find(o => o.parameter === p); return [p, { lowest: x?.lowest?.toString() ?? '', highest: x?.highest?.toString() ?? '' }]; })));
  };
  useEffect(() => { api<Limits>(`${path}${pondId == null ? '' : `?pondId=${pondId}`}`).then(fill).catch(e => setError(describeError(e))); }, [path, pondId]); // eslint-disable-line react-hooks/exhaustive-deps

  async function save(e: React.FormEvent) {
    e.preventDefault();
    const list: Limit[] = [];
    for (const p of PARAMETERS) {
      const v = form[p] ?? { lowest: '', highest: '' };
      if (!decimal(v.lowest) || !decimal(v.highest)) { setError(t('errors.limit')); return; }
      if (v.lowest.trim() === '' && v.highest.trim() === '') continue;
      list.push({ parameter: p, lowest: v.lowest.trim() === '' ? null : Number(v.lowest), highest: v.highest.trim() === '' ? null : Number(v.highest) });
    }
    setError(''); setMessage('');
    try {
      fill(await api<Limits>(path, { method: 'PUT', body: { pondId, limits: list } }));
      setMessage(t('limitsSaved'));
    } catch (err) {
      setError(describeError(err));
    }
  }
  if (!limits) return error ? <ErrorBanner message={error} /> : <Loading />;
  const fallback = (p: Parameter) => limits.defaults.find(d => d.parameter === p);
  return (
    <form className="form-card" noValidate onSubmit={e => void save(e)}>
      <p className="hint">{t(pondId == null ? 'limitsHelp' : 'pondLimitsHelp')}</p>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />
      <div className="table-scroll"><table className="data-table">
        <thead><tr><th>{t('parameterLabel')}</th><th>{t('lowest')}</th><th>{t('highest')}</th>{pondId != null && <th>{t('farmDefault')}</th>}</tr></thead>
        <tbody>{PARAMETERS.map(p => {
          const d = fallback(p);
          return (
            <tr key={p}>
              <td>{t(`parameter.${p}`)} <small>{t(`unit.${p}`)}</small></td>
              {(['lowest', 'highest'] as const).map(k => (
                <td key={k}><input aria-label={`${t(`parameter.${p}`)} ${t(k)}`} inputMode="decimal" disabled={!canWrite} value={form[p]?.[k] ?? ''}
                                   onChange={e => setForm({ ...form, [p]: { ...(form[p] ?? { lowest: '', highest: '' }), [k]: e.target.value } })} /></td>
              ))}
              {pondId != null && <td>{d ? `${d.lowest ?? '—'} – ${d.highest ?? '—'}` : '—'}</td>}
            </tr>
          );
        })}</tbody>
      </table></div>
      {canWrite && <div className="form-actions"><button className="secondary">{t('saveLimits')}</button></div>}
    </form>
  );
}

/** Readings as a table with the out-of-limit values marked. */
export function ReadingsTable({ readings, showPond, onDelete }: { readings: Reading[]; showPond: boolean; onDelete?: (r: Reading) => void }) {
  const t = useNs('aquaculture');
  const lang = currentLanguage();
  if (readings.length === 0) return <p className="hint">{t('noReadings')}</p>;
  return (
    <div className="table-scroll">
      <table className="data-table report">
        <thead><tr>{showPond && <th>{t('pond')}</th>}<th>{t('takenAt')}</th>
          {PARAMETERS.map(p => <th key={p} className="num">{t(`parameterShort.${p}`)}</th>)}<th>{t('notes')}</th>{onDelete && <th />}</tr></thead>
        <tbody>{readings.map(r => (
          <tr key={r.id}>
            {showPond && <td>{r.pondName}</td>}
            <td>{formatDateTime(r.takenAt, lang)}</td>
            {PARAMETERS.map(p => {
              const v = r[FIELD[p]] as number | null;
              const out = r.outOfLimits.includes(p);
              return <td key={p} className={`num${out ? ' low-stock-label' : ''}`}>{v == null ? '' : formatNumber(v, lang, 3)}{out && <span title={t('outsideLimits')}> ⚠</span>}</td>;
            })}
            <td>{r.notes ?? ''}</td>
            {onDelete && <td><button type="button" className="secondary" onClick={() => onDelete(r)}>{t('remove')}</button></td>}
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
}

/** Water quality across ponds for a period, and the farm business's limits (aquaculture design, section 7). */
export default function WaterPage() {
  const tenant = useTenant();
  const t = useNs('aquaculture');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/aquaculture`;
  const canWrite = can(tenant, 'AQUACULTURE_WRITE');
  const [range, setRange] = useState({ from: `${today().slice(0, 8)}01`, to: today() });
  const [ponds, setPonds] = useState<Pond[]>([]);
  const [pondId, setPondId] = useState('');
  const [readings, setReadings] = useState<Reading[] | null>(null);
  const [error, setError] = useState('');
  const q = `${pondId ? `pondId=${pondId}&` : ''}from=${range.from}&to=${range.to}`;

  const load = useCallback(() => api<Reading[]>(`${base}/readings?${q}`).then(setReadings).catch(e => setError(describeError(e))), [base, q]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { api<Pond[]>(`${base}/ponds`).then(p => setPonds(p.filter(x => x.active))).catch(() => undefined); }, [base]);

  const flagged = (readings ?? []).filter(r => r.outOfLimits.length > 0).length;
  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('water')}</h2><p>{t('waterHelp')}</p></div></div>
      <ErrorBanner message={error} />
      <QuickRanges onPick={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
        <label>{t('pond')}<select value={pondId} onChange={e => setPondId(e.target.value)}>
          <option value="">{t('allPonds')}</option>{ponds.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
      </div>
      {canWrite && pondId && <ReadingForm pondId={Number(pondId)} onSaved={() => void load()} />}
      <div className="section-head section-space">
        <h3>{t('report.water')}</h3>
        <ExportBar path={`${base}/readings?${q}`} name={`water-quality-${range.from}-${range.to}`} onError={setError} />
      </div>
      <p className="hint">{tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })}
        {flagged > 0 && ` · ${t('flaggedReadings', { count: String(flagged) })}`}</p>
      {!readings ? <Loading /> : <ReadingsTable readings={readings} showPond={!pondId} />}
      <h3 className="section-space">{t('farmLimits')}</h3>
      <LimitsEditor pondId={null} />
    </div>
  );
}
