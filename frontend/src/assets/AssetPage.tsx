import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey, useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { useActivities } from '../workforce/WorkforceLayout';
import type { Asset, Costs, Maintenance, Plan, ScheduleLine, Usage } from './types';
import { isMoney } from './types';

const optNumber = (v: string) => (v.trim() ? v.trim() : null);
const yearStart = () => `${today().slice(0, 4)}-01-01`;

/** One asset: its depreciation schedule, use, maintenance, running costs, and selling or scrapping it (G9–G13). */
export default function AssetPage() {
  const tenant = useTenant();
  const { assetId } = useParams();
  const navigate = useNavigate();
  const t = useNs('assets');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/assets`;
  const one = `${base}/${assetId}`;
  const canWrite = can(tenant, 'ASSETS_WRITE');
  const canManage = can(tenant, 'FINANCE_MANAGE');
  const activities = useActivities();
  const categoryLabel = useCategoryLabel();
  const [asset, setAsset] = useState<Asset | null>(null);
  const [schedule, setSchedule] = useState<ScheduleLine[]>([]);
  const [usage, setUsage] = useState<Usage[]>([]);
  const [plans, setPlans] = useState<Plan[]>([]);
  const [logs, setLogs] = useState<Maintenance[]>([]);
  const [range, setRange] = useState({ from: yearStart(), to: today() });
  const [costs, setCosts] = useState<Costs | null>(null);
  const [use, setUse] = useState({ date: today(), hours: '', km: '', acres: '', costObjectId: '' });
  const [plan, setPlan] = useState({ name: '', everyDays: '', everyHours: '' });
  const [log, setLog] = useState({ doneOn: today(), planId: '', hoursReading: '', description: '' });
  const [edit, setEdit] = useState<{ name: string; serialNo: string; notes: string } | null>(null);
  const [disposal, setDisposal] = useState<{ date: string; kind: 'SOLD' | 'SCRAPPED'; proceeds: string; partyId: string; financialAccountId: string } | null>(null);
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [a, s, u, p, m] = await Promise.all([api<Asset>(one), api<ScheduleLine[]>(`${one}/schedule`), api<Usage[]>(`${one}/usage`),
        api<Plan[]>(`${one}/maintenance-plans`), api<Maintenance[]>(`${one}/maintenance`)]);
      setAsset(a); setSchedule(s); setUsage(u); setPlans(p); setLogs(m);
    } catch (e) {
      setError(describeError(e));
    }
  }, [one]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    api<Costs>(`${one}/costs?from=${range.from}&to=${range.to}`).then(setCosts).catch(e => setError(describeError(e)));
  }, [one, range, usage]);

  async function act(action: () => Promise<unknown>, after?: () => void) {
    setError('');
    try { await action(); after?.(); await load(); } catch (e) { setError(describeError(e)); }
  }

  function openDisposal() {
    setDisposal({ date: today(), kind: 'SOLD', proceeds: '', partyId: '', financialAccountId: '' });
    const fin = `/api/v1/tenants/${tenant.id}/finance`;
    Promise.all([api<Party[]>(`${fin}/parties`), api<FinancialAccount[]>(`${fin}/accounts`)])
      .then(([p, a]) => { setParties(p.filter(x => x.active)); setAccounts(a.filter(x => x.active)); }).catch(() => undefined);
  }

  function dispose(e: React.FormEvent) {
    e.preventDefault();
    if (!disposal) return;
    const sold = disposal.kind === 'SOLD';
    if (sold && (!isMoney(disposal.proceeds) || (!disposal.partyId && !disposal.financialAccountId))) { setError(t('errors.proceeds')); return; }
    if (!window.confirm(t(sold ? 'confirmSell' : 'confirmScrap'))) return;
    void act(() => api(`${one}/disposal`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: {
      date: disposal.date, kind: disposal.kind, proceeds: sold ? disposal.proceeds : null,
      partyId: sold && disposal.partyId ? Number(disposal.partyId) : null,
      financialAccountId: sold && !disposal.partyId && disposal.financialAccountId ? Number(disposal.financialAccountId) : null } }),
    () => { setDisposal(null); setKey(newIdempotencyKey()); });
  }

  if (!asset) return error ? <ErrorBanner message={error} /> : <Loading />;
  const inUse = asset.status === 'IN_USE';
  const activityOptions = <><option value="">{t('general')}</option>{activities.map(a => <option key={a.costObjectId} value={a.costObjectId}>{a.name}</option>)}</>;
  return (
    <>
      <div className="crop-page-head">
        <div>
          <h2>{asset.name}</h2>
          <p>{t(`kind.${asset.kind}`)} · {t(`status.${asset.status}`)}{asset.serialNo ? ` · ${asset.serialNo}` : ''}
            {asset.inUseFrom ? ` · ${t('inUseFrom')}: ${formatDate(asset.inUseFrom, lang)}` : ''} · {t('source')}:{' '}
            {can(tenant, 'FINANCE_READ') ? <Link to={`/t/${tenant.id}/finance/records/${asset.sourceDocumentId}`}>{asset.sourceNumber}</Link> : asset.sourceNumber}</p>
        </div>
        <Link className="secondary" to={`/t/${tenant.id}/assets`}>{tc('back')}</Link>
      </div>
      <ErrorBanner message={error} />
      <div className="finance-stats">
        <div className="finance-stat"><span>{t('cost')}</span><b>{formatMoney(asset.cost, lang)}</b><small>{t('salvageValue')}: {formatMoney(asset.salvageValue, lang)}</small></div>
        <div className="finance-stat"><span>{t('accumulated')}</span><b>{formatMoney(asset.accumulated, lang)}</b>
          <small>{asset.lastDepreciatedMonth ? t('throughMonth', { month: asset.lastDepreciatedMonth }) : t('noRunsYet')}</small></div>
        <div className="finance-stat"><span>{t('bookValue')}</span><b>{formatMoney(asset.bookValue, lang)}</b>
          <small>{asset.monthlyDepreciation != null ? `${formatMoney(asset.monthlyDepreciation, lang)} ${t('aMonth')}` : t('notDepreciated')}</small></div>
      </div>
      {asset.notes && <p className="hint">{asset.notes}</p>}

      {canWrite && inUse && (edit ? (
        <form className="form-card form-grid" onSubmit={e => { e.preventDefault(); void act(() => api(one, { method: 'PATCH', body: { name: edit.name.trim(), serialNo: edit.serialNo, notes: edit.notes } }), () => setEdit(null)); }}>
          <label>{t('name')}<input required maxLength={140} value={edit.name} onChange={e => setEdit({ ...edit, name: e.target.value })} /></label>
          <label>{t('serialNo')}<input maxLength={80} value={edit.serialNo} onChange={e => setEdit({ ...edit, serialNo: e.target.value })} /></label>
          <label className="full">{t('notes')}<input maxLength={500} value={edit.notes} onChange={e => setEdit({ ...edit, notes: e.target.value })} /></label>
          <div className="form-actions full"><button type="button" className="secondary" onClick={() => setEdit(null)}>{tc('cancel')}</button><button className="primary">{tc('save')}</button></div>
        </form>
      ) : (
        <div className="quick-actions">
          <button type="button" className="secondary" onClick={() => setEdit({ name: asset.name, serialNo: asset.serialNo ?? '', notes: asset.notes ?? '' })}>{tc('edit')}</button>
          {!asset.lastDepreciatedMonth && <button type="button" className="secondary" onClick={() => {
            if (window.confirm(t('confirmRemove'))) void act(() => api(one, { method: 'DELETE' }).then(() => navigate(`/t/${tenant.id}/assets`)));
          }}>{t('removeFromRegister')}</button>}
          {canManage && !disposal && <button type="button" className="secondary" onClick={openDisposal}>{t('sellOrScrap')}</button>}
        </div>
      ))}

      {disposal && (
        <form className="form-card form-grid" noValidate onSubmit={dispose}>
          <h3 className="full">{t('sellOrScrap')}</h3>
          <label>{tf('date')}<input type="date" value={disposal.date} onChange={e => setDisposal({ ...disposal, date: e.target.value })} /></label>
          <label>{t('disposalKind')}<select value={disposal.kind} onChange={e => setDisposal({ ...disposal, kind: e.target.value as 'SOLD' | 'SCRAPPED' })}>
            <option value="SOLD">{t('status.SOLD')}</option><option value="SCRAPPED">{t('status.SCRAPPED')}</option></select></label>
          {disposal.kind === 'SOLD' && (
            <>
              <label>{t('proceeds')}<input inputMode="decimal" value={disposal.proceeds} onChange={e => setDisposal({ ...disposal, proceeds: e.target.value })} /></label>
              <label>{t('soldTo')} <small>{tc('optional')}</small><select value={disposal.partyId} onChange={e => setDisposal({ ...disposal, partyId: e.target.value })}>
                <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
              {!disposal.partyId && <label>{t('receivedInto')}<select value={disposal.financialAccountId} onChange={e => setDisposal({ ...disposal, financialAccountId: e.target.value })}>
                <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
            </>
          )}
          <p className="hint full">{t('disposalHelp', { bookValue: formatMoney(asset.bookValue, lang) })}</p>
          <div className="form-actions full"><button type="button" className="secondary" onClick={() => setDisposal(null)}>{tc('cancel')}</button>
            <button className="primary">{t('recordDisposal')}</button></div>
        </form>
      )}

      {schedule.length > 0 && (
        <>
          <h3 className="section-space">{t('schedule')}</h3>
          <div className="table-scroll">
            <table className="data-table">
              <thead><tr><th>{t('month')}</th><th className="num">{t('depreciationAmount')}</th><th className="num">{t('bookValueAfter')}</th><th>{t('posted')}</th></tr></thead>
              <tbody>{schedule.map(s => <tr key={s.month}><td>{s.month}</td><td className="num">{formatMoney(s.amount, lang)}</td>
                <td className="num">{formatMoney(s.bookValueAfter, lang)}</td><td>{s.posted ? '✓' : ''}</td></tr>)}</tbody>
            </table>
          </div>
        </>
      )}

      <h3 className="section-space">{t('usage')}</h3>
      {canWrite && inUse && (
        <form className="form-card form-grid" onSubmit={e => {
          e.preventDefault();
          if (!use.hours && !use.km && !use.acres) { setError(t('errors.usage')); return; }
          void act(() => api(`${one}/usage`, { method: 'POST', body: { date: use.date, hours: optNumber(use.hours), km: optNumber(use.km), acres: optNumber(use.acres),
            costObjectId: use.costObjectId ? Number(use.costObjectId) : null } }), () => setUse({ ...use, hours: '', km: '', acres: '' }));
        }}>
          <label>{tf('date')}<input type="date" value={use.date} onChange={e => setUse({ ...use, date: e.target.value })} /></label>
          <label>{t('hours')}<input inputMode="decimal" value={use.hours} onChange={e => setUse({ ...use, hours: e.target.value })} /></label>
          <label>{t('km')}<input inputMode="decimal" value={use.km} onChange={e => setUse({ ...use, km: e.target.value })} /></label>
          <label>{t('acres')}<input inputMode="decimal" value={use.acres} onChange={e => setUse({ ...use, acres: e.target.value })} /></label>
          <label>{t('usedOn')}<select value={use.costObjectId} onChange={e => setUse({ ...use, costObjectId: e.target.value })}>{activityOptions}</select></label>
          <div className="form-actions full"><button className="secondary">{t('addUsage')}</button></div>
        </form>
      )}
      {usage.length === 0 ? <p className="hint">{t('noUsage')}</p> : (
        <ul className="plain-list">
          {usage.map(u => (
            <li key={u.id}>{formatDate(u.date, lang)}{u.hours != null && ` · ${formatNumber(u.hours, lang, 2)} ${t('hoursShort')}`}
              {u.km != null && ` · ${formatNumber(u.km, lang, 1)} km`}{u.acres != null && ` · ${formatNumber(u.acres, lang, 2)} ${t('acresShort')}`}
              {u.costObjectName && <small> · {u.costObjectName}</small>}
              {canWrite && <button type="button" className="text-action" aria-label={tc('remove')}
                onClick={() => void act(() => api(`${base}/usage/${u.id}`, { method: 'DELETE' }))}><Trash2 size={15} /></button>}</li>
          ))}
        </ul>
      )}

      <h3 className="section-space">{t('maintenance')}</h3>
      {plans.length > 0 && (
        <ul className="plain-list">
          {plans.map(p => (
            <li key={p.id}><b>{p.name}</b> · {t(`planStatus.${p.status}`)}
              {p.nextDate && ` · ${t('nextOn', { date: formatDate(p.nextDate, lang) })}`}
              {p.hoursLeft != null && ` · ${t('hoursLeft', { hours: formatNumber(p.hoursLeft, lang, 1) })}`}
              {!p.active && <small> · {t('inactive')}</small>}</li>
          ))}
        </ul>
      )}
      {canWrite && inUse && (
        <form className="inline-form" onSubmit={e => {
          e.preventDefault();
          if (!plan.name.trim() || (!plan.everyDays && !plan.everyHours)) { setError(t('errors.plan')); return; }
          void act(() => api(`${one}/maintenance-plans`, { method: 'POST', body: { name: plan.name.trim(), everyDays: plan.everyDays ? Number(plan.everyDays) : null,
            everyHours: optNumber(plan.everyHours), lastDoneOn: today() } }), () => setPlan({ name: '', everyDays: '', everyHours: '' }));
        }}>
          <label>{t('planName')}<input maxLength={120} placeholder={t('planExample')} value={plan.name} onChange={e => setPlan({ ...plan, name: e.target.value })} /></label>
          <label>{t('everyDays')}<input inputMode="numeric" value={plan.everyDays} onChange={e => setPlan({ ...plan, everyDays: e.target.value })} /></label>
          <label>{t('everyHours')}<input inputMode="decimal" value={plan.everyHours} onChange={e => setPlan({ ...plan, everyHours: e.target.value })} /></label>
          <button className="secondary">{t('addPlan')}</button>
        </form>
      )}
      {canWrite && (
        <form className="form-card form-grid" onSubmit={e => {
          e.preventDefault();
          if (!log.description.trim()) { setError(t('errors.maintenance')); return; }
          void act(() => api(`${one}/maintenance`, { method: 'POST', body: { doneOn: log.doneOn, planId: log.planId ? Number(log.planId) : null,
            hoursReading: optNumber(log.hoursReading), description: log.description.trim() } }), () => setLog({ ...log, planId: '', hoursReading: '', description: '' }));
        }}>
          <label>{t('doneOn')}<input type="date" value={log.doneOn} onChange={e => setLog({ ...log, doneOn: e.target.value })} /></label>
          <label>{t('plan')} <small>{tc('optional')}</small><select value={log.planId} onChange={e => setLog({ ...log, planId: e.target.value })}>
            <option value="">—</option>{plans.filter(p => p.active).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
          <label>{t('hoursReading')} <small>{tc('optional')}</small><input inputMode="decimal" value={log.hoursReading} onChange={e => setLog({ ...log, hoursReading: e.target.value })} /></label>
          <label className="full">{t('whatWasDone')}<input maxLength={500} value={log.description} onChange={e => setLog({ ...log, description: e.target.value })} /></label>
          <div className="form-actions full"><button className="secondary">{t('recordMaintenance')}</button></div>
        </form>
      )}
      {logs.length === 0 ? <p className="hint">{t('noMaintenance')}</p> : (
        <ul className="plain-list">
          {logs.map(m => <li key={m.id}>{formatDate(m.doneOn, lang)} · {m.description}{m.planName && <small> · {m.planName}</small>}
            {m.hoursReading != null && <small> · {formatNumber(m.hoursReading, lang, 1)} {t('hoursShort')}</small>}{m.documentNumber && <small> · {m.documentNumber}</small>}</li>)}
        </ul>
      )}

      <h3 className="section-space">{t('runningCosts')}</h3>
      <div className="form-card form-grid filters">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      {!costs ? <Loading /> : (
        <div className="table-scroll"><table className="data-table report">
          <tbody>
            <tr><td>{t('depreciation')}</td><td className="num">{formatMoney(costs.depreciation, lang)}</td></tr>
            {costs.runningCosts.map(c => <tr key={`${c.accountCode}-${c.categoryId}`}><td>{c.categoryId ? categoryLabel({ code: c.categoryCode, name: c.categoryName }) : c.accountCode}</td>
              <td className="num">{formatMoney(c.amount, lang)}</td></tr>)}
            <tr className="total"><td>{t('totalCost')}</td><td className="num">{formatMoney(costs.depreciation + costs.runningTotal, lang)}</td></tr>
            {costs.costPerHour != null && <tr><td>{t('costPerHour', { hours: formatNumber(costs.hours, lang, 1) })}</td><td className="num">{formatMoney(costs.costPerHour, lang)}</td></tr>}
          </tbody>
        </table></div>
      )}
      <p className="hint">{t('runningCostsHelp')}</p>
    </>
  );
}
