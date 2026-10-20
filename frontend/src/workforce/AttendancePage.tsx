import { useCallback, useEffect, useState } from 'react';
import { Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { AttendanceRow, AttendanceStatus, Muster, PieceWork } from './types';
import { ATTENDANCE } from './types';
import { useActivities } from './WorkforceLayout';

type Edit = { status: AttendanceStatus | ''; costObjectId: string; hours: string };

/** The day's muster roll: mark everyone, change the few who differ, choose the crop worked on (G3). Plus piece work. */
export default function AttendancePage() {
  const tenant = useTenant();
  const t = useNs('workforce');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/workforce`;
  const canWrite = can(tenant, 'WORKFORCE_WRITE');
  const activities = useActivities();
  const [date, setDate] = useState(today());
  const [muster, setMuster] = useState<Muster | null>(null);
  const [edits, setEdits] = useState<Record<number, Edit>>({});
  const [pieces, setPieces] = useState<PieceWork[]>([]);
  const [piece, setPiece] = useState({ workerId: '', quantity: '', unit: '', rate: '', costObjectId: '' });
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  const load = useCallback(async () => {
    setError(''); setSaved(false);
    try {
      const [m, p] = await Promise.all([api<Muster>(`${base}/attendance/${date}`), api<PieceWork[]>(`${base}/piece-work?from=${date}&to=${date}`)]);
      setMuster(m); setPieces(p);
      setEdits(Object.fromEntries(m.workers.map(w => [w.workerId, { status: w.status ?? '', costObjectId: w.costObjectId ? String(w.costObjectId) : '', hours: w.hours != null ? String(w.hours) : '' }])));
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, date]);
  useEffect(() => { void load(); }, [load]);

  const set = (id: number, patch: Partial<Edit>) => setEdits(e => ({ ...e, [id]: { ...e[id], ...patch } }));
  function markAll(status: AttendanceStatus, costObjectId: string) {
    setEdits(e => Object.fromEntries(Object.entries(e).map(([id, v]) => {
      const row = muster?.workers.find(w => String(w.workerId) === id);
      return [id, row?.locked ? v : { ...v, status, costObjectId }];
    })));
  }

  async function save() {
    if (!muster) return;
    setError(''); setSaved(false);
    const entries = muster.workers.filter(w => !w.locked).map((w: AttendanceRow) => {
      const e = edits[w.workerId];
      return { workerId: w.workerId, status: e.status || null, costObjectId: e.costObjectId ? Number(e.costObjectId) : null, hours: e.hours || null };
    });
    try { setMuster(await api<Muster>(`${base}/attendance/${date}`, { method: 'PUT', body: { entries } })); setSaved(true); } catch (e) { setError(describeError(e)); }
  }

  async function addPiece(e: React.FormEvent) {
    e.preventDefault();
    if (!piece.workerId || !(Number(piece.quantity) > 0)) { setError(tc('required')); return; }
    setError('');
    try {
      await api(`${base}/piece-work`, { method: 'POST', body: { workerId: Number(piece.workerId), workDate: date, quantity: piece.quantity, unit: piece.unit || null,
        rate: piece.rate || null, costObjectId: piece.costObjectId ? Number(piece.costObjectId) : null } });
      setPiece({ workerId: '', quantity: '', unit: '', rate: '', costObjectId: '' });
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  const activityOptions = <><option value="">{t('general')}</option>{activities.map(a => <option key={a.costObjectId} value={a.costObjectId}>{a.name}</option>)}</>;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('attendance')}</h2><p>{t('attendanceHelp')}</p></div></div>
      <ErrorBanner message={error} />
      <div className="form-card form-grid filters">
        <label>{t('date')}<input type="date" max={today()} value={date} onChange={e => setDate(e.target.value)} /></label>
      </div>
      {!muster ? <Loading /> : muster.workers.length === 0 ? <p className="hint">{t('noWorkers')}</p> : (
        <>
          {canWrite && (
            <div className="quick-actions">
              <button type="button" className="secondary" onClick={() => markAll('PRESENT', '')}>{t('allPresent')}</button>
              {activities.map(a => <button key={a.costObjectId} type="button" className="secondary" onClick={() => markAll('PRESENT', String(a.costObjectId))}>
                {t('allPresentOn', { activity: a.name })}</button>)}
            </div>
          )}
          <div className="table-scroll">
            <table className="data-table">
              <thead><tr><th>{t('worker')}</th><th>{t('statusLabel')}</th><th>{t('workedOn')}</th><th>{t('hours')}</th></tr></thead>
              <tbody>
                {muster.workers.map(w => {
                  const e = edits[w.workerId] ?? { status: '', costObjectId: '', hours: '' };
                  const disabled = !canWrite || w.locked;
                  const working = e.status === 'PRESENT' || e.status === 'HALF_DAY';
                  return (
                    <tr key={w.workerId}>
                      <td>{w.workerName}<small> · {t(`payType.${w.payType}`)}</small>{w.locked && <small className="low-stock-label"> · {t('paid')}</small>}</td>
                      <td><select aria-label={t('statusFor', { name: w.workerName })} disabled={disabled} value={e.status} onChange={ev => set(w.workerId, { status: ev.target.value as AttendanceStatus | '' })}>
                        <option value="">—</option>{ATTENDANCE.map(s => <option key={s} value={s}>{t(`status.${s}`)}</option>)}</select></td>
                      <td><select aria-label={t('workedOnFor', { name: w.workerName })} disabled={disabled || !working} value={e.costObjectId} onChange={ev => set(w.workerId, { costObjectId: ev.target.value })}>
                        {activityOptions}</select></td>
                      <td><input aria-label={t('hoursFor', { name: w.workerName })} inputMode="decimal" disabled={disabled || !working} value={e.hours} onChange={ev => set(w.workerId, { hours: ev.target.value })} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {canWrite && <div className="form-actions"><button type="button" className="primary" onClick={() => void save()}>{t('saveAttendance')}</button></div>}
          {saved && <p className="hint" role="status">{t('saved', { date: formatDate(date, lang) })}</p>}
        </>
      )}

      <h3 className="section-space">{t('pieceWork')}</h3>
      {canWrite && muster && muster.workers.length > 0 && (
        <form className="form-card form-grid" onSubmit={e => void addPiece(e)}>
          <label>{t('worker')}<select value={piece.workerId} onChange={e => setPiece({ ...piece, workerId: e.target.value })}>
            <option value="">—</option>{muster.workers.map(w => <option key={w.workerId} value={w.workerId}>{w.workerName}</option>)}</select></label>
          <label>{t('quantity')}<input inputMode="decimal" value={piece.quantity} onChange={e => setPiece({ ...piece, quantity: e.target.value })} /></label>
          <label>{t('unit')} <small>{tc('optional')}</small><input maxLength={30} value={piece.unit} onChange={e => setPiece({ ...piece, unit: e.target.value })} /></label>
          <label>{t('ratePerUnit')} <small>{tc('optional')}</small><input inputMode="decimal" value={piece.rate} onChange={e => setPiece({ ...piece, rate: e.target.value })} /></label>
          <label>{t('workedOn')}<select value={piece.costObjectId} onChange={e => setPiece({ ...piece, costObjectId: e.target.value })}>{activityOptions}</select></label>
          <div className="form-actions full"><button className="secondary">{t('addPieceWork')}</button></div>
        </form>
      )}
      {pieces.length === 0 ? <p className="hint">{t('noPieceWork')}</p> : (
        <ul className="plain-list">
          {pieces.map(p => (
            <li key={p.id}>{p.workerName} · {formatNumber(p.quantity, lang, 3)} {p.unit} × {formatMoney(p.rate, lang)} = <b>{formatMoney(p.amount, lang)}</b>
              {p.costObjectName && <small> · {p.costObjectName}</small>}
              {canWrite && !p.locked && <button type="button" className="text-action" aria-label={tc('remove')}
                onClick={() => void api(`${base}/piece-work/${p.id}`, { method: 'DELETE' }).then(load).catch(e => setError(describeError(e)))}><Trash2 size={15} /></button>}</li>
          ))}
        </ul>
      )}
    </>
  );
}
