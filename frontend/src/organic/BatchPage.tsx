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
import { LOG_KINDS, type BatchReport, type Figures, type Log, type Output } from './types';

const kgOk = (v: string) => v.trim() === '' || (/^\d{1,9}(\.\d{1,3})?$/.test(v.trim()) && Number(v) > 0);

/** One organic batch: figures (L8), what went in (L2), its log (L6), output (L7) and where the output went. */
export default function BatchPage() {
  const { batchId } = useParams();
  const tenant = useTenant();
  const t = useNs('organic');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const path = `${base}/organic/batches/${batchId}`;
  const canWrite = can(tenant, 'ORGANIC_WRITE');
  const canStock = canWrite && can(tenant, 'INVENTORY_WRITE');
  const showMoney = can(tenant, 'FINANCE_READ');
  const [range, setRange] = useState({ from: `${today().slice(0, 8)}01`, to: today() });
  const [report, setReport] = useState<BatchReport | null>(null);
  const [logs, setLogs] = useState<Log[]>([]);
  const [outputs, setOutputs] = useState<Output[]>([]);
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [log, setLog] = useState({ date: today(), kind: 'TURNED', kg: '', temperature: '', moisture: '', notes: '' });
  const [out, setOut] = useState({ date: today(), itemId: '', quantity: '', finish: false });
  const [key, setKey] = useState(newIdempotencyKey);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [r, l, o] = await Promise.all([api<BatchReport>(`${path}/report?from=${range.from}&to=${range.to}`), api<Log[]>(`${path}/logs`), api<Output[]>(`${path}/outputs`)]);
      setReport(r); setLogs(l); setOutputs(o);
      if (can(tenant, 'INVENTORY_READ')) {
        setTransfers(await api<Transfer[]>(`${base}/stock/internal-transfers?from=${r.batch.startedOn}&to=${today()}&costObjectId=${r.batch.costObjectId}`));
      }
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, path, range.from, range.to, tenant]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (canStock) api<Item[]>(`${base}/stock/items`).then(i => setItems(i.filter(x => x.active && x.kind === 'PRODUCE'))).catch(() => undefined);
  }, [base, canStock]);

  async function send(url: string, method: 'POST' | 'DELETE', body: unknown, done: string, withKey = false) {
    setError(''); setMessage('');
    try {
      await api(url, { method, body, headers: withKey ? { 'Idempotency-Key': key } : undefined });
      if (withKey) setKey(newIdempotencyKey());
      await load();
      setMessage(done);
    } catch (e) {
      setError(describeError(e));
    }
  }

  if (!report) return error ? <ErrorBanner message={error} /> : <Loading />;
  const b = report.batch;
  const open = canWrite && !b.finishedOn;
  const own = `/t/${tenant.id}/stock/own-produce`;
  return (
    <>
      <div className="crop-page-head">
        <div>
          <p><Link to={`/t/${tenant.id}/organic`}>{t('batches')}</Link></p>
          <h2>{b.name} <small>· {t(`status.${b.status}`)}</small></h2>
          <p>{t(`kind.${b.kind}`)} · {b.farmName} · {t('startedOn')} {formatDate(b.startedOn, lang)}
            {b.readyBy && ` · ${t('readyByDate', { date: formatDate(b.readyBy, lang) })}`}{b.finishedOn && ` · ${t('finishedOn', { date: formatDate(b.finishedOn, lang) })}`}</p>
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
      <FigureCards title={t('period', { from: formatDate(report.period.from, lang), to: formatDate(report.period.to, lang) })} f={report.period} showMoney={showMoney} />
      {report.whole && <FigureCards title={t('wholeBatch')} f={report.whole} showMoney={showMoney} />}

      <div className="section-head section-space">
        <h3>{t('ownProduceMoves')}</h3>
        {can(tenant, 'INVENTORY_WRITE') && <span>
          {open && <Link className="secondary" to={`${own}?to=${b.costObjectId}`}>{t('moveIn')}</Link>}{' '}
          {outputs.length > 0 && <Link className="secondary" to={`${own}?from=${b.costObjectId}&itemId=${outputs[0].itemId}`}>{t('useOutput')}</Link>}
        </span>}
      </div>
      <p className="hint">{t('movesHelp')}</p>
      <TransfersTable rows={transfers} />

      <h3 className="section-space">{t('log')}</h3>
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => {
          e.preventDefault();
          if (!kgOk(log.kg) || (log.temperature.trim() !== '' && !/^-?\d{1,3}(\.\d)?$/.test(log.temperature.trim()))) { setError(t('errors.log')); return; }
          void send(`${path}/logs`, 'POST', { date: log.date, kind: log.kind, kg: log.kg.trim() || null, temperature: log.temperature.trim() || null,
            moisture: log.moisture || null, notes: log.notes.trim() || null }, t('logSaved'))
            .then(() => setLog(l => ({ ...l, kg: '', temperature: '', notes: '' })));
        }}>
          <label>{t('date')}<input type="date" max={today()} min={b.startedOn} value={log.date} onChange={e => setLog({ ...log, date: e.target.value })} /></label>
          <label>{t('whatWasDone')}<select value={log.kind} onChange={e => setLog({ ...log, kind: e.target.value })}>
            {LOG_KINDS.map(k => <option key={k} value={k}>{t(`logKind.${k}`)}</option>)}</select></label>
          {(log.kind === 'ADDED' || log.kind === 'WORMS') && <label>{t('kg')} <small>{tc('optional')}</small>
            <input inputMode="decimal" value={log.kg} onChange={e => setLog({ ...log, kg: e.target.value })} /></label>}
          {log.kind === 'CHECKED' && <>
            <label>{t('temperature')} <small>{tc('optional')}</small><input inputMode="decimal" value={log.temperature} onChange={e => setLog({ ...log, temperature: e.target.value })} /></label>
            <label>{t('moistureLabel')} <small>{tc('optional')}</small><select value={log.moisture} onChange={e => setLog({ ...log, moisture: e.target.value })}>
              <option value="">—</option>{(['DRY', 'RIGHT', 'WET'] as const).map(m => <option key={m} value={m}>{t(`moisture.${m}`)}</option>)}</select></label>
          </>}
          <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={300} placeholder={log.kind === 'ADDED' ? t('addedExample') : undefined}
                                                                                  value={log.notes} onChange={e => setLog({ ...log, notes: e.target.value })} /></label>
          <div className="form-actions full"><button className="secondary">{t('recordLog')}</button></div>
        </form>
      )}
      {logs.length === 0 ? <p className="hint">{t('noLog')}</p> : (
        <table className="data-table">
          <thead><tr><th>{t('date')}</th><th>{t('whatWasDone')}</th><th>{t('details')}</th><th /></tr></thead>
          <tbody>{logs.map(l => (
            <tr key={l.id}>
              <td>{formatDate(l.date, lang)}</td><td>{t(`logKind.${l.kind}`)}</td>
              <td>{[l.kg != null ? `${formatNumber(l.kg, lang, 3)} ${t('kg')}` : null, l.temperature != null ? `${formatNumber(l.temperature, lang, 1)} °C` : null,
                l.moisture ? t(`moisture.${l.moisture}`) : null, l.notes].filter(Boolean).join(' · ')}</td>
              <td>{open && <button type="button" className="icon-button" aria-label={t('removeLog')}
                onClick={() => { if (window.confirm(t('confirmRemoveLog'))) void send(`${base}/organic/logs/${l.id}`, 'DELETE', undefined, t('logRemoved')); }}>
                <Trash2 size={16} aria-hidden /></button>}</td>
            </tr>
          ))}</tbody>
        </table>
      )}

      <h3 className="section-space">{t('outputs')}</h3>
      {outputs.length > 0 && (
        <table className="data-table">
          <thead><tr><th>{t('date')}</th><th>{t('item')}</th><th className="num">{t('quantity')}</th></tr></thead>
          <tbody>{outputs.map(o => (
            <tr key={o.documentId} className={o.reversed ? 'muted' : undefined}>
              <td>{formatDate(o.date, lang)}<small> · <Link to={`/t/${tenant.id}/finance/records/${o.documentId}`}>{o.number}</Link></small></td>
              <td>{o.itemName}</td><td className="num">{formatNumber(o.quantity, lang, 3)} {o.unit}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {open && canStock && (
        <form className="form-card form-grid" noValidate onSubmit={e => {
          e.preventDefault();
          if (!out.itemId || !/^\d{1,10}(\.\d{1,4})?$/.test(out.quantity.trim()) || Number(out.quantity) <= 0) { setError(t('errors.output')); return; }
          if (out.finish && !window.confirm(t('confirmFinish'))) return;
          void send(`${path}/outputs`, 'POST', { date: out.date, itemId: Number(out.itemId), quantity: out.quantity.trim(), finish: out.finish }, t('outputSaved'), true)
            .then(() => setOut(o => ({ ...o, quantity: '', finish: false })));
        }}>
          <label>{t('date')}<input type="date" max={today()} min={b.startedOn} value={out.date} onChange={e => setOut({ ...out, date: e.target.value })} /></label>
          <label>{t('item')}<select value={out.itemId} onChange={e => setOut({ ...out, itemId: e.target.value })}>
            <option value="">—</option>{items.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}</select></label>
          <label>{t('quantity')}<input inputMode="decimal" value={out.quantity} onChange={e => setOut({ ...out, quantity: e.target.value })} /></label>
          <label className="check"><input type="checkbox" checked={out.finish} onChange={e => setOut({ ...out, finish: e.target.checked })} />{t('lastOutput')}</label>
          <p className="hint full">{t('outputHelp')}</p>
          <div className="form-actions full"><button className="primary">{t('recordOutput')}</button></div>
        </form>
      )}
    </>
  );
}

/** The batch's figures for a period (L8); money only with FINANCE_READ. */
function FigureCards({ title, f, showMoney }: { title: string; f: Figures; showMoney: boolean }) {
  const t = useNs('organic');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const unit = t(f.outputUnit === 'litre' ? 'litres' : 'kg');
  const m = (v: number | null) => (v == null ? '—' : formatMoney(v, lang));
  return (
    <>
      <h3 className="section-space">{title}</h3>
      <div className="finance-stats">
        <div className="finance-stat"><span>{t('inputKg')}</span><b>{formatNumber(f.inputKg, lang, 1)}</b>{f.days != null && <small>{t('daysValue', { days: String(f.days) })}</small>}</div>
        <div className="finance-stat"><span>{t('output')}</span><b>{formatNumber(f.output, lang, 1)} {unit}</b>
          <small>{t('yield')} {f.yieldPercent == null ? '—' : `${formatNumber(f.yieldPercent, lang, 1)}%`} · {t('inStock')} {formatNumber(f.inStock, lang, 1)}</small></div>
        {showMoney && <div className="finance-stat"><span>{t('costPerUnit')}</span><b>{m(f.costPerUnit)}</b>
          <small>{tf('costs')} {m(f.costs)} · {t('movedOut')} {m(f.movedOut)}</small></div>}
        {showMoney && <div className="finance-stat"><span>{t('profit')}</span><b>{m(f.profit)}</b><small>{tf('revenue')} {m(f.revenue)}</small></div>}
      </div>
    </>
  );
}
