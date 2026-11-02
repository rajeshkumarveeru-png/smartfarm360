import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, CalendarDays, MapPin, Plus, Ruler, Sprout, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { Category, FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey, useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Item, Lot, Unit } from '../stock/types';
import { baseUnitLabel, isQuantity, unitChoices } from '../stock/types';
import type { Activity, Cycle, CycleReport, Harvest, Observation } from './types';
import { ACTIVITY_TYPES, OBSERVATION_KINDS, SEVERITIES } from './types';
import { ExportBar } from '../reports/ReportTools';
import RecordsPanel from '../finance/RecordsPanel';

type Tab = 'activities' | 'money' | 'harvests' | 'observations';
type Input = { itemId: string; quantity: string; unit: string; lotNo: string };

/** One cultivation cycle: stage, figures and its field records (crop design, sections 3–8). */
export default function CyclePage() {
  const tenant = useTenant();
  const { cycleId } = useParams();
  const t = useNs('crop');
  const tc = useNs('common');
  const tf = useNs('fin');
  const ts = useNs('stock');
  const tu = useNs('units');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const base = `/api/v1/tenants/${tenant.id}`;
  const [cycle, setCycle] = useState<Cycle | null>(null);
  const [report, setReport] = useState<CycleReport | null>(null);
  const [range, setRange] = useState({ from: '', to: '', basis: 'ACCRUAL', price: '' });
  const [tab, setTab] = useState<Tab>('activities');
  const [activities, setActivities] = useState<Activity[]>([]);
  const [harvests, setHarvests] = useState<Harvest[]>([]);
  const [observations, setObservations] = useState<Observation[]>([]);
  const [form, setForm] = useState<'' | 'activity' | 'harvest' | 'observation'>('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const [c, a, h, o] = await Promise.all([
        api<Cycle>(`${base}/cultivation-cycles/${cycleId}`), api<Page<Activity>>(`${base}/activities?cycleId=${cycleId}&size=100`),
        api<Page<Harvest>>(`${base}/harvests?cycleId=${cycleId}&size=100`), api<Observation[]>(`${base}/cultivation-cycles/${cycleId}/observations`),
      ]);
      setCycle(c); setActivities(a.items); setHarvests(h.items); setObservations(o);
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, cycleId]);
  useEffect(() => { void load(); }, [load]);

  const reportQuery = useCallback(() => {
    const q = new URLSearchParams({ basis: range.basis });
    if (range.from) q.set('from', range.from);
    if (range.to) q.set('to', range.to);
    if (range.price && Number(range.price) >= 0) q.set('price', range.price);
    return q.toString();
  }, [range]);
  useEffect(() => {
    if (!cycle) return;
    api<CycleReport>(`${base}/cultivation-cycles/${cycleId}/report?${reportQuery()}`).then(setReport).catch(e => setError(describeError(e)));
  }, [base, cycleId, cycle, reportQuery]);

  async function act(path: string, body?: unknown, method = 'POST') {
    setError(''); setNotice('');
    try { await api(`${base}${path}`, { method, body }); await load(); } catch (e) { setError(describeError(e)); }
  }

  if (!cycle) return error ? <ErrorBanner message={error} /> : <Loading />;
  const open = cycle.status !== 'CLOSED' && cycle.status !== 'CANCELLED';
  const canRecord = can(tenant, 'CROP_ACTIVITY_WRITE') && open;
  const canMoney = can(tenant, 'FINANCE_READ');
  const canWrite = can(tenant, 'CROP_WRITE');
  const canReverse = can(tenant, 'FINANCE_REVERSE');
  const canSeeFinance = can(tenant, 'FINANCE_READ');
  return (
    <>
      <Link className="fw-back" to={`/t/${tenant.id}/crops`}><ArrowLeft size={16} aria-hidden />{tc('back')}</Link>
      <header className="fw-hero cycle-hero">
        <div className="fw-hero-main">
          <div className="fw-hero-icon" aria-hidden><Sprout size={26} /></div>
          <div>
            <h2>{cycle.name} <span className={`stage stage-${cycle.status.toLowerCase()}`}>{t(`stage.${cycle.status}`)}</span></h2>
            <p className="fw-meta">
              <span className="pill">{cycle.cropName}{cycle.varietyName ? ` · ${cycle.varietyName}` : ''}</span>
              <span><MapPin size={14} aria-hidden />{cycle.farmName} / {cycle.parcelName}</span>
              {cycle.areaAcres != null && <span><Ruler size={14} aria-hidden />{formatNumber(cycle.areaAcres, lang, 2)} {tu('ACRE')}</span>}
              {cycle.seasonName && <span><CalendarDays size={14} aria-hidden />{cycle.seasonName}</span>}
            </p>
            <p className="fw-meta cycle-dates">
              <span><CalendarDays size={14} aria-hidden />{t('sowing')}: {formatDate(cycle.plannedSowingDate, lang)}</span>
              {cycle.actualSowingDate && <span>{t('sown')}: {formatDate(cycle.actualSowingDate, lang)}</span>}
              {cycle.expectedHarvestDate && <span>{t('expected')}: {formatDate(cycle.expectedHarvestDate, lang)}</span>}
              {cycle.closedOn && <span>{t('closedOn')}: {formatDate(cycle.closedOn, lang)}</span>}
            </p>
            {cycle.warnings.includes('FIELD_OVERCOMMITTED') && <p className="low-stock-label">{t('warning.FIELD_OVERCOMMITTED')}</p>}
          </div>
        </div>
      </header>
      <ErrorBanner message={error} />
      {notice && <p className="low-stock-label" role="status">{notice}</p>}
      {canWrite && <StageActions cycle={cycle} onAction={(action, date) => void act(`/cultivation-cycles/${cycle.id}/status`, { action, date: date || null })} />}

      {report && (
        <>
          <div className="section-head section-space">
            <h3>{t('report')}</h3>
            <ExportBar path={`${base}/cultivation-cycles/${cycle.id}/report?${reportQuery()}`} name={`crop-cycle-${cycle.id}`} onError={setError} />
          </div>
          <div className="form-card form-grid filters">
            <label>{tf('from')}<input type="date" value={range.from || report.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
            <label>{tf('to')}<input type="date" value={range.to || report.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
            <label>{tf('basis')}<select value={range.basis} onChange={e => setRange({ ...range, basis: e.target.value })}>
              <option value="ACCRUAL">{tf('accrual')}</option><option value="CASH">{tf('cashBasis')}</option></select></label>
            <label>{report.costUnit ? t('estimatePrice', { unit: ts(`unit.${report.costUnit}`) }) : t('estimatePriceNoHarvest')}<input inputMode="decimal" value={range.price} onChange={e => setRange({ ...range, price: e.target.value })} /></label>
          </div>
          <p className="hint">{tf('rangeLabel', { from: formatDate(report.from, lang), to: formatDate(report.to, lang) })} · {tf('actualFigures')}</p>
          <div className="finance-stats">
            <div className="finance-stat"><span>{tf('revenue')}</span><b>{formatMoney(report.revenueTotal, lang)}</b></div>
            <div className="finance-stat"><span>{t('directCosts')}</span><b>{formatMoney(report.directCostTotal, lang)}</b></div>
            <div className="finance-stat"><span>{t('allocatedOverhead')}</span><b>{formatMoney(report.allocatedTotal, lang)}</b></div>
            <div className={`finance-stat${report.profit > 0 ? ' positive' : report.profit < 0 ? ' negative' : ''}`}><span>{t('profit')}</span><b>{formatMoney(report.profit, lang)}</b></div>
            <div className="finance-stat"><span>{t('costPerAcre')}</span><b>{report.costPerAcre != null ? formatMoney(report.costPerAcre, lang) : '—'}</b></div>
            <div className="finance-stat"><span>{t('yield')}</span>
              <b>{report.harvested[0] ? `${formatNumber(report.harvested[0].quantity, lang, 2)} ${ts(`unit.${report.harvested[0].baseUnit}`)}` : '—'}</b>
              {report.yieldPerAcre != null && report.harvested[0] && <small>{t('perAcre', { value: formatNumber(report.yieldPerAcre, lang, 2), unit: ts(`unit.${report.harvested[0].baseUnit}`) })}</small>}
              {report.plan.expectedYield != null && <small>{t('planned')}: {formatNumber(report.plan.expectedYield, lang, 2)}</small>}</div>
            <div className="finance-stat"><span>{t('costPerUnit')}</span><b>{report.costPerUnit != null ? formatMoney(report.costPerUnit, lang) : '—'}</b>
              {report.costUnit && <small>{ts('perUnit', { unit: ts(`unit.${report.costUnit}`) })} · {formatNumber(report.costPerUnit, lang, 4)}</small>}</div>
            <div className="finance-stat"><span>{t('unsold')}</span><b>{formatNumber(report.unsold, lang, 2)} {report.costUnit ? ts(`unit.${report.costUnit}`) : ''}</b>
              {report.estimateValue != null && <small className="low-stock-label">{t('estimateLabel', { amount: formatMoney(report.estimateValue, lang) })}</small>}</div>
          </div>
          <table className="data-table report">
            <tbody>
              <tr className="section"><th colSpan={2}>{tf('operatingRevenue')}</th></tr>
              {report.revenue.map(r => <tr key={`r${r.accountCode}-${r.categoryId}`}><td>{r.categoryId ? categoryLabel({ code: r.categoryCode, name: r.categoryName }) : tf(`ledger.${r.accountCode}`)}</td><td className="num">{formatMoney(r.amount, lang)}</td></tr>)}
              <tr className="section"><th colSpan={2}>{t('directCosts')}</th></tr>
              {report.directCosts.map(r => <tr key={`c${r.accountCode}-${r.categoryId}`}><td>{r.categoryId ? categoryLabel({ code: r.categoryCode, name: r.categoryName }) : tf(`ledger.${r.accountCode}`)}</td><td className="num">{formatMoney(r.amount, lang)}</td></tr>)}
              {report.allocatedOverhead.length > 0 && <tr className="section"><th colSpan={2}>{t('allocatedOverhead')}</th></tr>}
              {report.allocatedOverhead.map(r => <tr key={`a${r.accountCode}-${r.categoryId}`}><td>{r.categoryId ? categoryLabel({ code: r.categoryCode, name: r.categoryName }) : tf(`ledger.${r.accountCode}`)}</td><td className="num">{formatMoney(r.amount, lang)}</td></tr>)}
              <tr className="total"><td>{t('profit')}</td><td className="num">{formatMoney(report.profit, lang)}</td></tr>
            </tbody>
          </table>
          {(report.memo.activityCost > 0 || report.memo.harvestCount > 0) && (
            <p className="hint">{t('memoHelp', { cost: formatMoney(report.memo.activityCost, lang), value: formatMoney(report.memo.harvestValue, lang) })}</p>
          )}
        </>
      )}

      <div className="crop-tabs section-space" role="tablist">
        {(['activities', 'money', 'harvests', 'observations'] as Tab[]).filter(x => x !== 'money' || (canMoney && cycle.costObjectId)).map(x => (
          <button key={x} role="tab" aria-selected={tab === x} className={tab === x ? 'active' : ''} onClick={() => { setTab(x); setForm(''); }}>{t(x)}</button>
        ))}
      </div>
      {tab === 'money' && cycle.costObjectId && (
        <RecordsPanel filter={`costObjectId=${cycle.costObjectId}`} prefill={`farm=${cycle.farmId}&activity=${cycle.costObjectId}`} title={t('moneyTitle')} />
      )}
      {tab === 'activities' && <>
        {canRecord && form !== 'activity' && <button type="button" className="primary" onClick={() => setForm('activity')}><Plus size={16} aria-hidden />{t('logActivity')}</button>}
        {form === 'activity' && <ActivityForm cycle={cycle} onDone={warnings => { setForm(''); setNotice(warnings.length ? ts('warning.LOT_EXPIRED', { item: warnings[0].split(':')[1], lot: warnings[0].split(':')[2] }) : ''); void load(); }}
                                              onCancel={() => setForm('')} onError={setError} />}
        {activities.length === 0 ? <p className="hint">{t('noActivities')}</p> : (
          <div className="activity-list">
            {activities.map(a => (
              <article className="activity-row" key={a.id}>
                <div className="cycle-main"><b>{t(`activity.${a.activityType}`)}</b><small> · {formatDate(a.activityDate, lang)}</small>
                  {a.status === 'REVERSED' && <small className="low-stock-label"> · {tf('status.REVERSED')}</small>}
                  {a.notes && <div><small>{a.notes}</small></div>}
                  {(a.workers != null || a.hours != null) && <div><small>{a.workers != null ? t('workersCount', { count: String(a.workers) }) : ''}{a.hours != null ? ` · ${t('hoursCount', { hours: String(a.hours) })}` : ''}</small></div>}
                  {a.legacyMemo && <div><small>{[a.inputName, a.quantity != null ? `${a.quantity} ${a.quantityUnit ?? ''}` : null].filter(Boolean).join(' · ')}
                    {a.memoCost ? ` · ${t('memoCost', { amount: formatMoney(a.memoCost, lang) })}` : ''}</small></div>}
                  {canSeeFinance && <div>
                    {a.stockDocumentId && <Link to={`/t/${tenant.id}/finance/records/${a.stockDocumentId}`}><small>{t('stockUsedLink')}</small></Link>}
                    {a.stockDocumentId && a.billDocumentId && <small> · </small>}
                    {a.billDocumentId && <Link to={`/t/${tenant.id}/finance/records/${a.billDocumentId}`}><small>{t('paidWorkLink')}</small></Link>}</div>}
                </div>
                <div className="value-side">
                  {a.status === 'ACTIVE' && (a.stockDocumentId || a.billDocumentId) && canReverse &&
                    <button type="button" className="text-action" onClick={() => { if (window.confirm(t('confirmReverse'))) void act(`/activities/${a.id}/reverse`, {}); }}>{tf('reverse')}</button>}
                  {a.status === 'ACTIVE' && !a.stockDocumentId && !a.billDocumentId && !a.legacyMemo && can(tenant, 'CROP_ACTIVITY_WRITE') &&
                    <button type="button" className="text-action" aria-label={tc('remove')} onClick={() => { if (window.confirm(t('confirmDelete'))) void act(`/activities/${a.id}`, undefined, 'DELETE'); }}><Trash2 size={15} /></button>}
                </div>
              </article>
            ))}
          </div>
        )}
      </>}
      {tab === 'harvests' && <>
        {canRecord && form !== 'harvest' && <button type="button" className="primary" onClick={() => setForm('harvest')}><Plus size={16} aria-hidden />{t('recordHarvest')}</button>}
        {form === 'harvest' && <HarvestForm cycle={cycle} onDone={() => { setForm(''); void load(); }} onCancel={() => setForm('')} onError={setError} />}
        {harvests.length === 0 ? <p className="hint">{t('noHarvests')}</p> : (
          <div className="activity-list">
            {harvests.map(h => (
              <article className="activity-row" key={h.id}>
                <div className="cycle-main"><b>{formatNumber(h.quantity, lang, 4)} {ts(`unit.${h.quantityUnit}`, { defaultValue: h.quantityUnit })}</b>
                  <small> · {formatDate(h.harvestDate, lang)}{h.lotNo ? ` · ${ts('lot')} ${h.lotNo}` : ''}{h.grade ? ` · ${t('grade')} ${h.grade}` : ''}
                    {h.moisturePct != null ? ` · ${t('moisture')} ${h.moisturePct}%` : ''}</small>
                  {h.status === 'REVERSED' && <small className="low-stock-label"> · {tf('status.REVERSED')}</small>}
                  {h.legacy && <div><small>{t('legacyHarvest')}{h.memoSaleValue ? ` · ${t('memoValue', { amount: formatMoney(h.memoSaleValue, lang) })}` : ''}</small></div>}
                </div>
                <div className="value-side">
                  {!h.legacy && h.status === 'ACTIVE' && canReverse &&
                    <button type="button" className="text-action" onClick={() => { if (window.confirm(t('confirmReverse'))) void act(`/harvests/${h.id}/reverse`, {}); }}>{tf('reverse')}</button>}
                </div>
              </article>
            ))}
          </div>
        )}
      </>}
      {tab === 'observations' && <>
        {can(tenant, 'CROP_ACTIVITY_WRITE') && form !== 'observation' && <button type="button" className="primary" onClick={() => setForm('observation')}><Plus size={16} aria-hidden />{t('addObservation')}</button>}
        {form === 'observation' && <ObservationForm cycle={cycle} activities={activities} onDone={() => { setForm(''); void load(); }} onCancel={() => setForm('')} onError={setError} />}
        {observations.length === 0 ? <p className="hint">{t('noObservations')}</p> : (
          <div className="activity-list">
            {observations.map(o => (
              <article className="activity-row" key={o.id}>
                <div className="cycle-main"><b>{t(`observation.${o.kind}`)}</b><small> · {formatDate(o.observedOn, lang)} · </small>
                  <span className={`severity severity-${o.severity.toLowerCase()}`}>{t(`severity.${o.severity}`)}</span>
                  {o.fieldPart && <small> · {o.fieldPart}</small>}
                  {o.notes && <div><small>{o.notes}</small></div>}
                  {o.actionTaken && <div><small>{t('actionTaken')}: {o.actionTaken}</small></div>}
                </div>
                <div className="value-side">
                  {can(tenant, 'CROP_ACTIVITY_WRITE') && <button type="button" className="text-action" aria-label={tc('remove')}
                    onClick={() => { if (window.confirm(t('confirmDelete'))) void act(`/observations/${o.id}`, undefined, 'DELETE'); }}><Trash2 size={15} /></button>}
                </div>
              </article>
            ))}
          </div>
        )}
      </>}
    </>
  );
}

function StageActions({ cycle, onAction }: { cycle: Cycle; onAction: (action: string, date: string) => void }) {
  const t = useNs('crop');
  const [date, setDate] = useState(today());
  const actions: string[] = cycle.status === 'PLANNED' ? ['START', 'CLOSE', 'CANCEL'] : cycle.status === 'GROWING' ? ['CLOSE', 'CANCEL']
    : cycle.status === 'HARVESTING' ? ['CLOSE'] : cycle.status === 'CLOSED' ? ['REOPEN'] : [];
  if (!actions.length) return null;
  return (
    <form className="inline-form" onSubmit={e => e.preventDefault()}>
      {actions.some(a => a === 'START' || a === 'CLOSE') && <label>{t('stageDate')}<input type="date" value={date} onChange={e => setDate(e.target.value)} /></label>}
      {actions.map(a => (
        <button key={a} type="button" className="secondary" onClick={() => { if (a !== 'CLOSE' && a !== 'CANCEL' || window.confirm(t(`confirm.${a}`))) onAction(a, date); }}>{t(`action.${a}`)}</button>
      ))}
    </form>
  );
}

function ActivityForm({ cycle, onDone, onCancel, onError }: { cycle: Cycle; onDone: (warnings: string[]) => void; onCancel: () => void; onError: (m: string) => void }) {
  const tenant = useTenant();
  const t = useNs('crop');
  const tc = useNs('common');
  const tf = useNs('fin');
  const ts = useNs('stock');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const base = `/api/v1/tenants/${tenant.id}`;
  const canUseStock = can(tenant, 'INVENTORY_WRITE');
  const canPay = can(tenant, 'FINANCE_WRITE');
  const [key] = useState(newIdempotencyKey);
  const [form, setForm] = useState<Record<string, string>>({ activityType: 'LAND_PREPARATION', activityDate: today() });
  const [inputs, setInputs] = useState<Input[]>([]);
  const [paid, setPaid] = useState(false);
  const [items, setItems] = useState<Item[]>([]);
  const [units, setUnits] = useState<Unit[]>([]);
  const [lots, setLots] = useState<Record<string, Lot[]>>({});
  const [categories, setCategories] = useState<Category[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [busy, setBusy] = useState(false);
  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));

  useEffect(() => {
    if (canUseStock) Promise.all([api<Item[]>(`${base}/stock/items`), api<Unit[]>(`${base}/stock/units`)])
      .then(([i, u]) => { setItems(i.filter(x => x.active)); setUnits(u); }).catch(() => undefined);
    if (canPay) Promise.all([api<Category[]>(`${base}/finance/categories`), api<FinancialAccount[]>(`${base}/finance/accounts`), api<Party[]>(`${base}/finance/parties`)])
      .then(([c, a, p]) => { setCategories(c.filter(x => x.active && x.kind === 'EXPENSE')); setAccounts(a.filter(x => x.active)); setParties(p.filter(x => x.active)); })
      .catch(() => undefined);
  }, [base, canUseStock, canPay]);

  function updateInput(i: number, patch: Partial<Input>) {
    setInputs(xs => xs.map((x, j) => (j === i ? { ...x, ...patch } : x)));
    if (patch.itemId) {
      const item = items.find(x => String(x.id) === patch.itemId);
      if (item?.trackLots && !lots[patch.itemId]) {
        api<Lot[]>(`${base}/stock/items/${patch.itemId}/lots`).then(l => {
          setLots(x => ({ ...x, [patch.itemId!]: l }));
          const first = l.find(y => y.quantity > 0);
          if (first) setInputs(xs => xs.map((x, j) => (j === i ? { ...x, lotNo: first.lotNo } : x)));
        }).catch(() => undefined);
      }
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    for (const i of inputs) {
      const item = items.find(x => String(x.id) === i.itemId);
      if (!item) { onError(ts('errors.item')); return; }
      if (!isQuantity(i.quantity)) { onError(ts('errors.quantity')); return; }
      if (item.trackLots && !i.lotNo) { onError(ts('errors.lot')); return; }
    }
    if (paid && (!form.categoryId || !/^\d{1,12}(\.\d{1,2})?$/.test(form.amount ?? '') || Number(form.amount) <= 0)) { onError(tf('errors.amount')); return; }
    if (paid && !form.partyId && !form.financialAccountId) { onError(tf('errors.partyOrAccount')); return; }
    setBusy(true); onError('');
    try {
      const r = await api<{ warnings: string[] }>(`${base}/activities`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: {
        cycleId: cycle.id, activityType: form.activityType, activityDate: form.activityDate, notes: form.notes || null,
        workers: form.workers ? Number(form.workers) : null, hours: form.hours || null,
        inputs: inputs.map(i => ({ itemId: Number(i.itemId), quantity: i.quantity, unit: i.unit || null, lotNo: i.lotNo || null })),
        paidWork: paid ? { categoryId: Number(form.categoryId), amount: form.amount, partyId: form.partyId ? Number(form.partyId) : null,
          financialAccountId: !form.partyId && form.financialAccountId ? Number(form.financialAccountId) : null, description: form.description || null } : null,
      } });
      onDone(r.warnings);
    } catch (err) {
      onError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form-card form-grid" noValidate onSubmit={e => void submit(e)}>
      <label>{t('activityType')}<select value={form.activityType} onChange={e => set('activityType', e.target.value)}>
        {ACTIVITY_TYPES.map(a => <option key={a} value={a}>{t(`activity.${a}`)}</option>)}</select></label>
      <label>{t('date')}<input type="date" required value={form.activityDate} onChange={e => set('activityDate', e.target.value)} /></label>
      <label>{t('workers')} <small>{tc('optional')}</small><input inputMode="numeric" value={form.workers ?? ''} onChange={e => set('workers', e.target.value)} /></label>
      <label>{t('hours')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.hours ?? ''} onChange={e => set('hours', e.target.value)} /></label>
      <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={500} value={form.notes ?? ''} onChange={e => set('notes', e.target.value)} /></label>

      {canUseStock && (
        <fieldset className="full lines">
          <legend>{t('inputsFromStock')}</legend>
          {inputs.map((x, i) => {
            const item = items.find(y => String(y.id) === x.itemId);
            return (
              <div className="line-row" key={i}>
                <label>{ts('item')}<select value={x.itemId} onChange={e => updateInput(i, { itemId: e.target.value, unit: items.find(y => String(y.id) === e.target.value)?.baseUnit ?? '', lotNo: '' })}>
                  <option value="">—</option>{items.filter(y => y.kind !== 'PRODUCE' || y.quantity > 0).map(y => <option key={y.id} value={y.id}>{y.name}</option>)}</select></label>
                <label>{ts('quantity')}<input inputMode="decimal" value={x.quantity} onChange={e => updateInput(i, { quantity: e.target.value })} /></label>
                {item && <label>{ts('unitLabel')}<select value={x.unit} onChange={e => updateInput(i, { unit: e.target.value })}>
                  {unitChoices(item, units).map(u => <option key={u.value} value={u.value}>{u.label ?? ts(`unit.${u.value}`)}</option>)}</select></label>}
                {item?.trackLots && <label>{ts('lot')}<select value={x.lotNo} onChange={e => updateInput(i, { lotNo: e.target.value })}>
                  <option value="">—</option>{(lots[x.itemId] ?? []).filter(l => l.quantity > 0).map(l => <option key={l.id} value={l.lotNo}>{l.lotNo}{l.expiryDate ? ` · ${formatDate(l.expiryDate, lang)}` : ''}{l.expired ? ` · ${ts('expired')}` : ''}</option>)}</select></label>}
                {item && <small className="hint">{ts('inStock', { quantity: formatNumber(item.quantity, lang, 4), unit: baseUnitLabel(item, ts) })}</small>}
                <button type="button" className="text-action" aria-label={tc('remove')} onClick={() => setInputs(xs => xs.filter((_, j) => j !== i))}><Trash2 size={16} /></button>
              </div>
            );
          })}
          <button type="button" className="secondary" onClick={() => setInputs(xs => [...xs, { itemId: '', quantity: '', unit: '', lotNo: '' }])}><Plus size={16} />{t('addInput')}</button>
        </fieldset>
      )}

      {canPay && <label className="check full"><input type="checkbox" checked={paid} onChange={e => setPaid(e.target.checked)} />{t('paidWorkToggle')}</label>}
      {paid && <>
        <label>{t('paidFor')}<select value={form.categoryId ?? ''} onChange={e => set('categoryId', e.target.value)}>
          <option value="">—</option>{categories.map(c => <option key={c.id} value={c.id}>{categoryLabel(c)}</option>)}</select></label>
        <label>{tf('amount')}<input inputMode="decimal" value={form.amount ?? ''} onChange={e => set('amount', e.target.value)} /></label>
        <label>{t('owedTo')} <small>{tc('optional')}</small><select value={form.partyId ?? ''} onChange={e => set('partyId', e.target.value)}>
          <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        {!form.partyId && <label>{tf('paidFrom')}<select value={form.financialAccountId ?? ''} onChange={e => set('financialAccountId', e.target.value)}>
          <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
      </>}
      <p className="hint full">{t('recordOnceHelp')}</p>
      <div className="form-actions full">
        <button type="button" className="secondary" onClick={onCancel}>{tc('cancel')}</button>
        <button className="primary" disabled={busy}>{busy ? tc('loading') : tc('save')}</button>
      </div>
    </form>
  );
}

function HarvestForm({ cycle, onDone, onCancel, onError }: { cycle: Cycle; onDone: () => void; onCancel: () => void; onError: (m: string) => void }) {
  const tenant = useTenant();
  const t = useNs('crop');
  const tc = useNs('common');
  const ts = useNs('stock');
  const base = `/api/v1/tenants/${tenant.id}`;
  const [key] = useState(newIdempotencyKey);
  const [form, setForm] = useState<Record<string, string>>({ harvestDate: today(), unit: 'quintal' });
  const [item, setItem] = useState<Item | null>(null);
  const [units, setUnits] = useState<Unit[]>([]);
  const [busy, setBusy] = useState(false);
  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));
  useEffect(() => {
    api<{ item: Item | null }>(`${base}/crops/${cycle.cropId}/produce-item`).then(r => setItem(r.item)).catch(() => undefined);
    api<Unit[]>(`${base}/stock/units`).then(setUnits).catch(() => setUnits([]));
  }, [base, cycle.cropId]);
  const choices = item ? unitChoices(item, units) : units.filter(u => u.dimension === 'MASS').map(u => ({ value: u.code, label: undefined as string | undefined }));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!isQuantity(form.quantity ?? '')) { onError(ts('errors.quantity')); return; }
    setBusy(true); onError('');
    try {
      await api(`${base}/harvests`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: {
        cycleId: cycle.id, harvestDate: form.harvestDate, quantity: form.quantity, unit: form.unit || null, grade: form.grade || null,
        moisturePct: form.moisturePct || null, notes: form.notes || null } });
      onDone();
    } catch (err) {
      onError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form-card form-grid" noValidate onSubmit={e => void submit(e)}>
      <label>{t('harvestDate')}<input type="date" required value={form.harvestDate} onChange={e => set('harvestDate', e.target.value)} /></label>
      <label>{t('quantity')}<input inputMode="decimal" value={form.quantity ?? ''} onChange={e => set('quantity', e.target.value)} /></label>
      <label>{ts('unitLabel')}<select value={form.unit} onChange={e => set('unit', e.target.value)}>
        {choices.map(u => <option key={u.value} value={u.value}>{u.label ?? ts(`unit.${u.value}`)}</option>)}</select></label>
      <label>{t('grade')} <small>{tc('optional')}</small><input maxLength={40} value={form.grade ?? ''} onChange={e => set('grade', e.target.value)} /></label>
      <label>{t('moisture')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.moisturePct ?? ''} onChange={e => set('moisturePct', e.target.value)} /></label>
      <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={500} value={form.notes ?? ''} onChange={e => set('notes', e.target.value)} /></label>
      <p className="hint full">{item ? t('harvestIntoItem', { item: item.name }) : t('harvestNewItem', { crop: cycle.cropName })}</p>
      <div className="form-actions full">
        <button type="button" className="secondary" onClick={onCancel}>{tc('cancel')}</button>
        <button className="primary" disabled={busy}>{busy ? tc('loading') : tc('save')}</button>
      </div>
    </form>
  );
}

function ObservationForm({ cycle, activities, onDone, onCancel, onError }: { cycle: Cycle; activities: Activity[]; onDone: () => void; onCancel: () => void; onError: (m: string) => void }) {
  const tenant = useTenant();
  const t = useNs('crop');
  const tc = useNs('common');
  const lang = currentLanguage();
  const [form, setForm] = useState<Record<string, string>>({ observedOn: today(), kind: 'PEST', severity: 'MEDIUM' });
  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }));
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    onError('');
    try {
      await api(`/api/v1/tenants/${tenant.id}/cultivation-cycles/${cycle.id}/observations`, { method: 'POST', body: {
        observedOn: form.observedOn, kind: form.kind, severity: form.severity, fieldPart: form.fieldPart || null, notes: form.notes || null,
        actionTaken: form.actionTaken || null, activityId: form.activityId ? Number(form.activityId) : null } });
      onDone();
    } catch (err) {
      onError(describeError(err));
    }
  }
  return (
    <form className="form-card form-grid" noValidate onSubmit={e => void submit(e)}>
      <label>{t('date')}<input type="date" required value={form.observedOn} onChange={e => set('observedOn', e.target.value)} /></label>
      <label>{t('observationKind')}<select value={form.kind} onChange={e => set('kind', e.target.value)}>{OBSERVATION_KINDS.map(k => <option key={k} value={k}>{t(`observation.${k}`)}</option>)}</select></label>
      <label>{t('severityLabel')}<select value={form.severity} onChange={e => set('severity', e.target.value)}>{SEVERITIES.map(k => <option key={k} value={k}>{t(`severity.${k}`)}</option>)}</select></label>
      <label>{t('fieldPart')} <small>{tc('optional')}</small><input maxLength={120} value={form.fieldPart ?? ''} onChange={e => set('fieldPart', e.target.value)} /></label>
      <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={1000} value={form.notes ?? ''} onChange={e => set('notes', e.target.value)} /></label>
      <label>{t('actionTaken')} <small>{tc('optional')}</small><input maxLength={500} value={form.actionTaken ?? ''} onChange={e => set('actionTaken', e.target.value)} /></label>
      <label>{t('treatedBy')} <small>{tc('optional')}</small><select value={form.activityId ?? ''} onChange={e => set('activityId', e.target.value)}>
        <option value="">—</option>{activities.filter(a => a.status === 'ACTIVE').map(a => <option key={a.id} value={a.id}>{t(`activity.${a.activityType}`)} · {formatDate(a.activityDate, lang)}</option>)}</select></label>
      <div className="form-actions full">
        <button type="button" className="secondary" onClick={onCancel}>{tc('cancel')}</button>
        <button className="primary">{tc('save')}</button>
      </div>
    </form>
  );
}
