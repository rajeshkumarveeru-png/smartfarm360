import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { TransfersTable, type Transfer } from '../stock/OwnProducePage';
import type { Item } from '../stock/types';
import { ROLES, STEP_KINDS, type BatchReport, type Figures, type Output, type Step } from './types';

const num = (v: string, max = 9) => v.trim() === '' || new RegExp(`^\\d{1,${max}}(\\.\\d{1,3})?$`).test(v.trim());

/** One processing batch: weight balance and figures (M6, M7), inputs (M2), steps (M4) and output by role (M5). */
export default function BatchPage() {
  const { batchId } = useParams();
  const tenant = useTenant();
  const t = useNs('processing');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const path = `${base}/processing/batches/${batchId}`;
  const canWrite = can(tenant, 'PROCESSING_WRITE');
  const canStock = can(tenant, 'INVENTORY_WRITE');
  const showMoney = can(tenant, 'FINANCE_READ');
  const [range, setRange] = useState({ from: `${today().slice(0, 8)}01`, to: today() });
  const [report, setReport] = useState<BatchReport | null>(null);
  const [steps, setSteps] = useState<Step[]>([]);
  const [outputs, setOutputs] = useState<Output[]>([]);
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [step, setStep] = useState({ date: today(), kind: 'DRYING', kgIn: '', kgOut: '', moistureBefore: '', moistureAfter: '', grade: '', notes: '' });
  const [out, setOut] = useState({ date: today(), role: 'MAIN', itemId: '', description: '', quantity: '', grade: '', finish: false });
  const [key, setKey] = useState(newIdempotencyKey);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [r, s, o] = await Promise.all([api<BatchReport>(`${path}/report?from=${range.from}&to=${range.to}`), api<Step[]>(`${path}/steps`), api<Output[]>(`${path}/outputs`)]);
      setReport(r); setSteps(s); setOutputs(o);
      if (can(tenant, 'INVENTORY_READ')) {
        setTransfers(await api<Transfer[]>(`${base}/stock/internal-transfers?from=${r.batch.startedOn}&to=${today()}&costObjectId=${r.batch.costObjectId}`));
      }
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, path, range.from, range.to, tenant]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (canWrite) api<Item[]>(`${base}/stock/items`).then(i => setItems(i.filter(x => x.active && x.kind === 'PRODUCE'))).catch(() => undefined);
  }, [base, canWrite]);

  async function send(url: string, method: 'POST' | 'DELETE', body: unknown, done: string, withKey = false): Promise<boolean> {
    setError(''); setMessage('');
    try {
      await api(url, { method, body, headers: withKey ? { 'Idempotency-Key': key } : undefined });
      if (withKey) setKey(newIdempotencyKey());
      await load();
      setMessage(done);
      return true;
    } catch (e) {
      setError(describeError(e));
      return false;
    }
  }

  if (!report) return error ? <ErrorBanner message={error} /> : <Loading />;
  const b = report.batch;
  const open = canWrite && !b.finishedOn;
  const waste = out.role === 'WASTE';
  return (
    <>
      <div className="crop-page-head">
        <div>
          <p><Link to={`/t/${tenant.id}/processing`}>{t('batches')}</Link></p>
          <h2>{b.code} · {b.name} <small>· {t(`status.${b.status}`)}</small></h2>
          <p>{t(`kind.${b.kind}`)} · {t('mainProduct')}: {b.mainItemName} · {b.farmName} · {t('startedOn')} {formatDate(b.startedOn, lang)}
            {b.expectedEnd && ` · ${t('expectedEndDate', { date: formatDate(b.expectedEnd, lang) })}`}{b.finishedOn && ` · ${t('finishedOn', { date: formatDate(b.finishedOn, lang) })}`}</p>
        </div>
        {canWrite && (b.finishedOn
          ? <button type="button" className="secondary" onClick={() => void send(`${path}/reopen`, 'POST', {}, t('reopened'))}>{t('reopen')}</button>
          : <button type="button" className="secondary" onClick={() => { if (window.confirm(t('confirmFinish'))) void send(`${path}/finish`, 'POST', { date: today() }, t('finished')); }}>
            {t('finish')}</button>)}
      </div>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />

      <div className="form-card form-grid filters">
        <label>{t('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{t('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      {report.whole && <Balance title={t('wholeBatch')} f={report.whole} showMoney={showMoney} />}
      <Balance title={t('period', { from: formatDate(report.period.from, lang), to: formatDate(report.period.to, lang) })} f={report.period} showMoney={showMoney} />

      <div className="section-head section-space">
        <h3>{t('inputs')}</h3>
        {open && canStock && <Link className="secondary" to={`/t/${tenant.id}/stock/own-produce?to=${b.costObjectId}`}>{t('moveIn')}</Link>}
      </div>
      <p className="hint">{t('inputsHelp')}</p>
      <TransfersTable rows={transfers} />

      <h3 className="section-space">{t('steps')}</h3>
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => {
          e.preventDefault();
          if (![step.kgIn, step.kgOut].every(v => num(v)) || ![step.moistureBefore, step.moistureAfter].every(v => num(v, 3))) { setError(t('errors.step')); return; }
          const n = (v: string) => (v.trim() === '' ? null : v.trim());
          void send(`${path}/steps`, 'POST', { date: step.date, kind: step.kind, kgIn: n(step.kgIn), kgOut: n(step.kgOut), moistureBefore: n(step.moistureBefore),
            moistureAfter: n(step.moistureAfter), grade: n(step.grade), notes: n(step.notes) }, t('stepSaved'))
            .then(ok => ok && setStep(s => ({ ...s, kgIn: '', kgOut: '', moistureBefore: '', moistureAfter: '', grade: '', notes: '' })));
        }}>
          <label>{t('date')}<input type="date" max={today()} min={b.startedOn} value={step.date} onChange={e => setStep({ ...step, date: e.target.value })} /></label>
          <label>{t('stepLabel')}<select value={step.kind} onChange={e => setStep({ ...step, kind: e.target.value })}>
            {STEP_KINDS.map(k => <option key={k} value={k}>{t(`step.${k}`)}</option>)}</select></label>
          <label>{t('kgIn')} <small>{tc('optional')}</small><input inputMode="decimal" value={step.kgIn} onChange={e => setStep({ ...step, kgIn: e.target.value })} /></label>
          <label>{t('kgOut')} <small>{tc('optional')}</small><input inputMode="decimal" value={step.kgOut} onChange={e => setStep({ ...step, kgOut: e.target.value })} /></label>
          {step.kind === 'DRYING' && <>
            <label>{t('moistureBefore')} <small>{tc('optional')}</small><input inputMode="decimal" value={step.moistureBefore} onChange={e => setStep({ ...step, moistureBefore: e.target.value })} /></label>
            <label>{t('moistureAfter')} <small>{tc('optional')}</small><input inputMode="decimal" value={step.moistureAfter} onChange={e => setStep({ ...step, moistureAfter: e.target.value })} /></label>
          </>}
          <label>{t('grade')} <small>{tc('optional')}</small><input maxLength={60} value={step.grade} onChange={e => setStep({ ...step, grade: e.target.value })} /></label>
          <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={300} value={step.notes} onChange={e => setStep({ ...step, notes: e.target.value })} /></label>
          <p className="hint full">{t('stepsHelp')}</p>
          <div className="form-actions full"><button className="secondary">{t('recordStep')}</button></div>
        </form>
      )}
      {steps.length === 0 ? <p className="hint">{t('noSteps')}</p> : (
        <div className="table-scroll"><table className="data-table">
          <thead><tr><th>{t('date')}</th><th>{t('stepLabel')}</th><th className="num">{t('kgIn')}</th><th className="num">{t('kgOut')}</th><th className="num">{t('stepLoss')}</th>
            <th>{t('details')}</th><th /></tr></thead>
          <tbody>{steps.map(s => (
            <tr key={s.id}>
              <td>{formatDate(s.date, lang)}</td><td>{t(`step.${s.kind}`)}</td>
              <td className="num">{s.kgIn == null ? '' : formatNumber(s.kgIn, lang, 3)}</td><td className="num">{s.kgOut == null ? '' : formatNumber(s.kgOut, lang, 3)}</td>
              <td className="num">{s.lossKg == null ? '' : formatNumber(s.lossKg, lang, 3)}</td>
              <td>{[s.moistureBefore != null || s.moistureAfter != null ? t('moistureChange', { before: s.moistureBefore == null ? '—' : formatNumber(s.moistureBefore, lang, 2),
                after: s.moistureAfter == null ? '—' : formatNumber(s.moistureAfter, lang, 2) }) : null, s.grade, s.notes].filter(Boolean).join(' · ')}</td>
              <td>{open && <button type="button" className="icon-button" aria-label={t('removeStep')}
                onClick={() => { if (window.confirm(t('confirmRemoveStep'))) void send(`${base}/processing/steps/${s.id}`, 'DELETE', undefined, t('stepRemoved')); }}>
                <Trash2 size={16} aria-hidden /></button>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}

      <h3 className="section-space">{t('outputs')}</h3>
      {outputs.length > 0 && (
        <div className="table-scroll"><table className="data-table">
          <thead><tr><th>{t('date')}</th><th>{t('roleLabel')}</th><th>{t('item')}</th><th className="num">{t('quantity')}</th><th>{t('lot')}</th><th /></tr></thead>
          <tbody>{outputs.map(o => (
            <tr key={o.id} className={o.reversed ? 'muted' : undefined}>
              <td>{formatDate(o.date, lang)}{o.documentId && <small> · <Link to={`/t/${tenant.id}/finance/records/${o.documentId}`}>{o.documentNumber}</Link></small>}</td>
              <td>{t(`role.${o.role}`)}</td><td>{o.itemName ?? o.description}{o.grade && <small> · {o.grade}</small>}</td>
              <td className="num">{formatNumber(o.quantity, lang, 3)} {o.unit}</td>
              <td>{o.lotNo ? <Link to={`/t/${tenant.id}/processing/trace?lotNo=${encodeURIComponent(o.lotNo)}`}>{o.lotNo}</Link> : ''}</td>
              <td>{open && o.role === 'WASTE' && <button type="button" className="icon-button" aria-label={t('removeOutput')}
                onClick={() => { if (window.confirm(t('confirmRemoveOutput'))) void send(`${base}/processing/outputs/${o.id}`, 'DELETE', undefined, t('outputRemoved')); }}>
                <Trash2 size={16} aria-hidden /></button>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => {
          e.preventDefault();
          if ((waste ? !out.description.trim() : !out.itemId) || !/^\d{1,10}(\.\d{1,4})?$/.test(out.quantity.trim()) || Number(out.quantity) <= 0) {
            setError(t('errors.output')); return;
          }
          if (out.finish && !window.confirm(t('confirmFinish'))) return;
          void send(`${path}/outputs`, 'POST', { date: out.date, role: out.role, itemId: waste ? null : Number(out.itemId), description: waste ? out.description.trim() : null,
            quantity: out.quantity.trim(), grade: out.grade.trim() || null, finish: out.finish }, t('outputSaved'), true)
            .then(ok => ok && setOut(o => ({ ...o, quantity: '', description: '', grade: '', finish: false })));
        }}>
          <label>{t('date')}<input type="date" max={today()} min={b.startedOn} value={out.date} onChange={e => setOut({ ...out, date: e.target.value })} /></label>
          <label>{t('roleLabel')}<select value={out.role} onChange={e => setOut({ ...out, role: e.target.value, itemId: e.target.value === 'MAIN' ? String(b.mainItemId) : out.itemId })}>
            {ROLES.map(r => <option key={r} value={r}>{t(`role.${r}`)}</option>)}</select></label>
          {waste
            ? <label>{t('wasteWhat')}<input maxLength={120} placeholder={t('wasteExample')} value={out.description} onChange={e => setOut({ ...out, description: e.target.value })} /></label>
            : <label>{t('item')}<select value={out.role === 'MAIN' ? String(b.mainItemId) : out.itemId} disabled={out.role === 'MAIN'}
                                         onChange={e => setOut({ ...out, itemId: e.target.value })}>
              <option value="">—</option>{items.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}</select></label>}
          <label>{waste ? t('kg') : t('quantity')}<input inputMode="decimal" value={out.quantity} onChange={e => setOut({ ...out, quantity: e.target.value })} /></label>
          {!waste && <label>{t('grade')} <small>{tc('optional')}</small><input maxLength={60} value={out.grade} onChange={e => setOut({ ...out, grade: e.target.value })} /></label>}
          <label className="check"><input type="checkbox" checked={out.finish} onChange={e => setOut({ ...out, finish: e.target.checked })} />{t('lastOutput')}</label>
          {!waste && !canStock && <p className="hint full">{t('outputNeedsStock')}</p>}
          <p className="hint full">{t('outputHelp')}</p>
          <div className="form-actions full"><button className="primary" disabled={!waste && !canStock}>{t('recordOutput')}</button></div>
        </form>
      )}
    </>
  );
}

/** The weight balance and money for a period (M6, M7); money only with FINANCE_READ. */
function Balance({ title, f, showMoney }: { title: string; f: Figures; showMoney: boolean }) {
  const t = useNs('processing');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const n = (v: number | null, d = 1) => (v == null ? '—' : formatNumber(v, lang, d));
  const m = (v: number | null) => (v == null ? '—' : formatMoney(v, lang));
  return (
    <>
      <h3 className="section-space">{title}</h3>
      <div className="table-scroll">
        <table className="data-table report">
          <thead><tr><th>{t('balance')}</th><th>{t('roleLabel')}</th><th className="num">{t('kg')}</th><th className="num">{t('yield')}</th></tr></thead>
          <tbody>
            <tr><td><b>{t('inputKg')}</b>{f.days != null && <small> · {t('daysValue', { days: String(f.days) })}</small>}</td><td /><td className="num"><b>{n(f.inputKg)}</b></td><td /></tr>
            {f.outputs.map(o => <tr key={`${o.role}-${o.name}`}><td>{o.name}</td><td>{t(`role.${o.role}`)}</td><td className="num">{n(o.kg)}</td>
              <td className="num">{o.yieldPercent == null ? '—' : `${n(o.yieldPercent)}%`}</td></tr>)}
            <tr><td>{t('processLoss')}</td><td /><td className="num">{n(f.lossKg)}</td><td className="num">{f.lossPercent == null ? '—' : `${n(f.lossPercent)}%`}</td></tr>
          </tbody>
        </table>
      </div>
      {showMoney && (
        <div className="finance-stats">
          <div className="finance-stat"><span>{t('mainCostPerKg')}</span><b>{m(f.mainCostPerKg)}</b><small>{t('rawCostPerKg')} {m(f.rawCostPerKg)}</small></div>
          <div className="finance-stat"><span>{t('profit')}</span><b>{m(f.profit)}</b><small>{tf('revenue')} {m(f.revenue)} · {tf('costs')} {m(f.costs)}</small></div>
          <div className="finance-stat"><span>{t('byproductSales')}</span><b>{m(f.byproductSales)}</b></div>
        </div>
      )}
    </>
  );
}
