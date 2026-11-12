import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { formatDate, formatNumber, today } from '../lib/format';
import { currentLanguage } from '../i18n';
import { describeError } from '../lib/errors';
import type { FlockLine } from './types';

type Hatch = { id: number; groupId: number; lineId: number; variety: string | null; collectedOn: string; incubatedOn: string;
  expectedOn: string | null; eggs: number; fertile: number | null; hatched: number | null; failed: number | null;
  resultOn: string | null; notes: string | null; completed: boolean };

export default function HatchingPanel({ groupId, lines, editable, onChanged }: {
  groupId: number; lines: FlockLine[]; editable: boolean; onChanged: () => void;
}) {
  const tenant = useTenant();
  const base = `/api/v1/tenants/${tenant.id}/livestock`;
  const lang = currentLanguage();
  const [rows, setRows] = useState<Hatch[] | null>(null);
  const [error, setError] = useState('');
  const [form, setForm] = useState({ lineId: lines[0] ? String(lines[0].id) : '', collectedOn: today(), incubatedOn: today(), expectedOn: '', eggs: '', notes: '' });
  const [results, setResults] = useState<Record<number, { resultOn: string; fertile: string; hatched: string; failed: string }>>({});
  const load = useCallback(() => api<Hatch[]>(`${base}/flocks/${groupId}/hatches`).then(setRows).catch(e => setError(describeError(e))), [base, groupId]);
  useEffect(() => { void load(); }, [load]);

  async function create(e: React.FormEvent) {
    e.preventDefault(); setError('');
    if (!form.lineId || !/^\d+$/.test(form.eggs) || Number(form.eggs) < 1) { setError('Choose a bird variety and enter the number of eggs.'); return; }
    try {
      await api(`${base}/flocks/${groupId}/hatches`, { method: 'POST', body: { lineId: Number(form.lineId), collectedOn: form.collectedOn,
        incubatedOn: form.incubatedOn, expectedOn: form.expectedOn || null, eggs: Number(form.eggs), notes: form.notes || null } });
      setForm({ ...form, eggs: '', notes: '' }); await load();
    } catch (e) { setError(describeError(e)); }
  }
  async function saveResult(id: number) {
    const r = results[id]; if (!r || !/^\d+$/.test(r.fertile) || !/^\d+$/.test(r.hatched) || !/^\d+$/.test(r.failed)) {
      setError('Enter valid fertile, hatched and failed egg counts.'); return;
    }
    setError('');
    try {
      await api(`${base}/hatches/${id}/result`, { method: 'PUT', body: { resultOn: r.resultOn, fertile: Number(r.fertile),
        hatched: Number(r.hatched), failed: Number(r.failed) } });
      await load(); onChanged();
    } catch (e) { setError(describeError(e)); }
  }
  if (!rows) return <Loading />;
  return <section className="hatching-panel">
    <h3 className="section-space">Hatching & chick production</h3>
    <p className="hint">Record eggs incubated by variety. When the hatch is complete, enter the result; successful chicks are added to this batch's bird count.</p>
    <ErrorBanner message={error} />
    {editable && <form className="form-card form-grid" onSubmit={e => void create(e)}>
      <label>Bird variety<select value={form.lineId} onChange={e => setForm({ ...form, lineId: e.target.value })}>
        <option value="">Select variety</option>{lines.map(l => <option key={l.id} value={l.id}>{l.variety || l.species}</option>)}</select></label>
      <label>Eggs collected on<input type="date" max={today()} value={form.collectedOn} onChange={e => setForm({ ...form, collectedOn: e.target.value })}/></label>
      <label>Incubation date<input type="date" max={today()} value={form.incubatedOn} onChange={e => setForm({ ...form, incubatedOn: e.target.value })}/></label>
      <label>Expected hatch date<input type="date" min={form.incubatedOn} value={form.expectedOn} onChange={e => setForm({ ...form, expectedOn: e.target.value })}/></label>
      <label>Eggs incubated<input inputMode="numeric" value={form.eggs} onChange={e => setForm({ ...form, eggs: e.target.value })}/></label>
      <label className="full">Notes<input maxLength={300} value={form.notes} onChange={e => setForm({ ...form, notes: e.target.value })}/></label>
      <div className="form-actions full"><button className="primary">Save incubation batch</button></div>
    </form>}
    {rows.length === 0 ? <p className="hint">No hatching batches recorded yet.</p> : <div className="table-scroll"><table className="data-table">
      <thead><tr><th>Variety</th><th>Incubated</th><th className="num">Eggs</th><th>Expected hatch</th><th>Result</th><th className="num">Chicks</th><th className="num">Hatch rate</th></tr></thead>
      <tbody>{rows.map(h => <tr key={h.id}>
        <td>{h.variety || 'Birds'}</td><td>{formatDate(h.incubatedOn, lang)}</td><td className="num">{formatNumber(h.eggs, lang, 0)}</td>
        <td>{h.expectedOn ? formatDate(h.expectedOn, lang) : '—'}</td><td>{h.completed ? `Fertile ${h.fertile} · Failed ${h.failed}` : 'In progress'}</td>
        <td className="num">{h.hatched ?? '—'}</td><td className="num">{h.fertile && h.hatched != null ? `${(h.hatched / h.fertile * 100).toFixed(1)}%` : '—'}</td>
      </tr>)}</tbody></table></div>}
    {editable && rows.filter(h => !h.completed).map(h => <form key={h.id} className="form-card form-grid section-space" onSubmit={e => { e.preventDefault(); void saveResult(h.id); }}>
      <h4 className="full">Record hatch result · {h.variety || 'Birds'} ({h.eggs} eggs)</h4>
      {(['resultOn','fertile','hatched','failed'] as const).map(field => <label key={field}>{({ resultOn: 'Result date', fertile: 'Fertile eggs', hatched: 'Chicks hatched', failed: 'Failed / unhatched' })[field]}
        <input type={field === 'resultOn' ? 'date' : 'number'} min="0" max={field === 'resultOn' ? undefined : h.eggs} maxLength={field === 'resultOn' ? undefined : 7}
          value={results[h.id]?.[field] ?? (field === 'resultOn' ? today() : '')}
          onChange={e => setResults(old => ({ ...old, [h.id]: { resultOn: old[h.id]?.resultOn ?? today(), fertile: old[h.id]?.fertile ?? '', hatched: old[h.id]?.hatched ?? '', failed: old[h.id]?.failed ?? '', [field]: e.target.value } }))}/></label>)}
      <p className="hint full">Hatch rate is calculated as hatched chicks ÷ fertile eggs. Hatched chicks are added to the linked variety.</p>
      <div className="form-actions full"><button className="secondary">Save hatch result</button></div>
    </form>)}
  </section>;
}
