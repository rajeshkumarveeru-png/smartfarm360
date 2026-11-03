import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ChevronRight, Layers, Leaf, MapPin, MapPinned, Plus, Receipt, Sprout, Trash2, TrendingDown, TrendingUp, Wallet, X } from 'lucide-react';
import { api } from '../api/client';
import type { Farm, Parcel } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import RecordsPanel from '../finance/RecordsPanel';
import type { IncomeStatement } from '../finance/types';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import { can } from '../lib/nav';
import { rangeFor } from '../reports/ranges';
import { QuickRanges, useFiscalStartMonth } from '../reports/ReportTools';
import type { CategoryBreakdown } from '../reports/types';
import { FarmInsight, type Insight, useFarmCycles } from './FarmInsights';
import { FARM_TYPE_KEYS } from './FarmsPage';

const AREA_UNITS = ['ACRE', 'HECTARE', 'CENT', 'SQFT'] as const;

/**
 * One farm's workspace: its money for a period (income, costs, profit and where the money goes), the records booked
 * to it, and its land. Expenses and sales added from here are booked to this farm.
 */
export default function FarmPage() {
  const tenant = useTenant();
  const farmId = Number(useParams().farmId);
  const navigate = useNavigate();
  const t = useNs('farm');
  const tf = useNs('fin');
  const tc = useNs('common');
  const tu = useNs('units');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const startMonth = useFiscalStartMonth();
  const base = `/api/v1/tenants/${tenant.id}`;
  const canMoney = can(tenant, 'FINANCE_READ');
  const canWrite = can(tenant, 'FARM_WRITE');

  const [farm, setFarm] = useState<Farm | null>(null);
  const [parcels, setParcels] = useState<Parcel[]>([]);
  const [range, setRange] = useState(() => rangeFor('thisYear', new Date(), startMonth));
  const [statement, setStatement] = useState<IncomeStatement | null>(null);
  const [costs, setCosts] = useState<CategoryBreakdown | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [showParcel, setShowParcel] = useState(false);
  const [parcelForm, setParcelForm] = useState({ name: '', area: '', areaUnit: 'ACRE', soilType: '', irrigationType: '' });
  const [insight, setInsight] = useState<Insight | null>(null);
  const { cycles, error: cyclesError } = useFarmCycles(farmId, range, canMoney);

  // The financial year follows finance settings once they arrive
  useEffect(() => { setRange(rangeFor('thisYear', new Date(), startMonth)); }, [startMonth]);

  const loadFarm = useCallback(() => {
    setError('');
    Promise.all([api<Farm>(`${base}/farms/${farmId}`), api<Parcel[]>(`${base}/farms/${farmId}/land-parcels`)])
      .then(([f, p]) => { setFarm(f); setParcels(p); }).catch(e => setError(describeError(e)));
  }, [base, farmId]);
  useEffect(loadFarm, [loadFarm]);

  useEffect(() => {
    if (!canMoney) return;
    let alive = true;
    const q = `from=${range.from}&to=${range.to}&farmId=${farmId}`;
    setStatement(null); setCosts(null);
    Promise.all([api<IncomeStatement>(`${base}/finance/reports/income-statement?${q}`), api<CategoryBreakdown>(`${base}/finance/reports/categories?${q}&kind=COSTS`)])
      .then(([s, c]) => { if (alive) { setStatement(s); setCosts(c); } }).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [base, farmId, range, canMoney]);


  async function submitParcel(e: React.FormEvent) {
    e.preventDefault();
    if (!parcelForm.name.trim() || !(Number(parcelForm.area) > 0)) { setError(tc('required')); return; }
    setBusy(true); setError('');
    try {
      const created = await api<Parcel>(`${base}/farms/${farmId}/land-parcels`, { method: 'POST', body: {
        name: parcelForm.name.trim(), area: parcelForm.area, areaUnit: parcelForm.areaUnit,
        soilType: parcelForm.soilType.trim() || null, irrigationType: parcelForm.irrigationType.trim() || null,
      } });
      setParcels(old => [...old, created].sort((a, b) => a.name.localeCompare(b.name)));
      setParcelForm({ name: '', area: '', areaUnit: 'ACRE', soilType: '', irrigationType: '' });
      setShowParcel(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  /** Deleting archives the farm: it leaves every list, while its past records stay in the accounts. */
  async function deleteFarm() {
    if (!farm || !window.confirm(t('confirmDeleteFarm', { farm: farm.name }))) return;
    setBusy(true); setError('');
    try {
      await api(`${base}/farms/${farm.id}`, { method: 'DELETE' });
      navigate(`/t/${tenant.id}/farms`);
    } catch (err) {
      setError(describeError(err));
      setBusy(false);
    }
  }

  async function removeParcel(p: Parcel) {
    if (!window.confirm(t('confirmRemoveParcel', { parcel: p.name }))) return;
    setError('');
    try {
      await api(`${base}/land-parcels/${p.id}`, { method: 'DELETE' });
      setParcels(old => old.filter(x => x.id !== p.id));
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (!farm) return error ? <ErrorBanner message={error} onRetry={loadFarm} /> : <Loading />;
  const money = (v: number) => formatMoney(v, lang);
  const place = [farm.village, farm.district, farm.state].filter(Boolean).join(', ');
  const areaByUnit = parcels.reduce<Record<string, number>>((acc, p) => ({ ...acc, [p.areaUnit]: (acc[p.areaUnit] ?? 0) + Number(p.area) }), {});
  const profit = statement?.operatingProfit ?? 0;

  return (
    <section className="farm-workspace">
      <Link className="fw-back" to={`/t/${tenant.id}/farms`}><ArrowLeft size={16} aria-hidden />{t('back')}</Link>
      <header className="fw-hero">
        <div className="fw-hero-main">
          <div className="fw-hero-icon" aria-hidden><Leaf size={26} /></div>
          <div>
            <h2>{farm.name}</h2>
            <p className="fw-meta">
              <span className="pill">{t(FARM_TYPE_KEYS[farm.farmType] ?? 'other')}</span>
              {place && <span><MapPin size={14} aria-hidden />{place}</span>}
              <span><MapPinned size={14} aria-hidden />{t('parcels', { count: String(parcels.length) })}
                {Object.entries(areaByUnit).map(([u, a]) => ` · ${formatNumber(a, lang)} ${tu(u)}`).join('')}</span>
            </p>
          </div>
        </div>
        {canWrite && (
          <div className="fw-actions">
            {canWrite && <button type="button" className="danger" disabled={busy} onClick={() => void deleteFarm()}><Trash2 size={16} aria-hidden />{t('deleteFarm')}</button>}
          </div>
        )}
      </header>
      <ErrorBanner message={error} />

      {canMoney ? <>
        <div className="fw-section-head">
          <div><h3>{t('money')}</h3><p className="hint">{t('moneyHelp')}</p></div>
          <QuickRanges onPick={setRange} />
        </div>
        <p className="hint fw-range">{formatDate(range.from, lang)} – {formatDate(range.to, lang)}</p>
        <div className="finance-stats db-kpis fw-kpis">
          <button type="button" className="finance-stat db-kpi kpi-button" onClick={() => setInsight({ type: 'income' })}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-income"><TrendingUp size={16} aria-hidden /></i>{t('income')}</span>
            <b>{statement ? money(statement.operatingRevenueTotal) : '…'}</b><small className="kpi-hint">{t('clickHint')}<ChevronRight size={13} aria-hidden /></small></button>
          <button type="button" className="finance-stat db-kpi kpi-button" onClick={() => setInsight({ type: 'costs' })}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-costs"><TrendingDown size={16} aria-hidden /></i>{t('costs')}</span>
            <b>{statement ? money(statement.operatingCostTotal) : '…'}</b><small className="kpi-hint">{t('clickHint')}<ChevronRight size={13} aria-hidden /></small></button>
          <button type="button" className={`finance-stat db-kpi kpi-button${profit > 0 ? ' positive' : profit < 0 ? ' negative' : ''}`} onClick={() => setInsight({ type: 'profit' })}>
            <span className="db-kpi-label"><i className="db-kpi-icon tone-profit"><Wallet size={16} aria-hidden /></i>{t('profit')}</span>
            <b>{statement ? money(profit) : '…'}</b><small className="kpi-hint">{t('clickHint')}<ChevronRight size={13} aria-hidden /></small></button>
        </div>

        <div className="db-card cycle-pnl">
          <div className="db-card-head"><div><h3><Layers size={18} aria-hidden />{t('cyclePnl')}</h3><p className="hint">{t('cyclePnlHelp')}</p></div></div>
          <ErrorBanner message={cyclesError} />
          {!cycles ? <Loading /> : cycles.length === 0 ? <div className="db-all-clear muted"><Sprout size={26} aria-hidden /><p className="hint">{t('noCycles')}</p></div> : (
            <div className="pnl-grid">
              {cycles.map(c => {
                const max = Math.max(1, ...cycles.map(x => Math.max(x.revenue, x.costs)));
                return (
                  <button key={c.costObjectId} type="button" className={`pnl-card${c.contribution < 0 ? ' loss' : c.contribution > 0 ? ' gain' : ''}`} onClick={() => setInsight({ type: 'cycle', cycle: c })}>
                    <span className="pnl-top"><span className="pnl-icon" aria-hidden><Sprout size={16} /></span>
                      <span className="pnl-name"><strong>{c.name}</strong><small>{t(`kind.${c.kind}`)}</small></span>
                      <ChevronRight className="db-chevron" size={16} aria-hidden /></span>
                    <span className="pnl-figures">
                      <span><small>{t('income')}</small><b>{money(c.revenue)}</b></span>
                      <span><small>{t('costs')}</small><b>{money(c.costs)}</b></span>
                    </span>
                    <span className="pnl-bars" aria-hidden>
                      <span className="pnl-bar in"><span style={{ width: `${(c.revenue / max) * 100}%` }} /></span>
                      <span className="pnl-bar out"><span style={{ width: `${(c.costs / max) * 100}%` }} /></span>
                    </span>
                    <span className="pnl-result"><small>{t('profit')}</small><b>{money(c.contribution)}</b></span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div className="db-grid fw-grid">
          <div className="db-card">
            <div className="db-card-head"><h3>{t('whereMoneyGoes')}</h3>{costs && costs.total > 0 && <b>{money(costs.total)}</b>}</div>
            {!costs ? <Loading /> : costs.rows.length === 0 ? <div className="db-all-clear muted"><Receipt size={26} aria-hidden /><p className="hint">{t('noCosts')}</p></div> : (
              <ul className="plain-list fw-costs">{costs.rows.map(c => {
                const label = c.categoryId ? categoryLabel({ code: c.categoryCode, name: c.categoryName }) : tf(`ledger.${c.accountCode}`);
                const inner = <>
                  <span className="db-cost-name">{label}</span>
                  <span className="db-cost-figures"><b>{money(c.amount)}</b>{c.share != null && <small> · {c.share}%</small>}</span>
                  {c.share != null && <span className="db-cost-bar" aria-hidden><span style={{ width: `${Math.min(100, Math.max(0, c.share))}%` }} /></span>}
                </>;
                return <li key={`${c.accountCode}-${c.categoryId}`}>{c.categoryId
                  ? <button type="button" className="cost-row-button" onClick={() => setInsight({ type: 'category', categoryId: c.categoryId!, label, kind: 'COSTS' })}>{inner}</button>
                  : inner}</li>;
              })}</ul>
            )}
          </div>

          <RecordsPanel filter={`farmId=${farm.id}`} prefill={`farm=${farm.id}`} title={t('records')} />
        </div>
      </> : <p className="info-banner">{t('noMoneyAccess')}</p>}

      <div className="fw-section-head">
        <div><h3>{t('land')} <span className="count">{parcels.length}</span></h3></div>
        {canWrite && <button className="primary" onClick={() => setShowParcel(x => !x)}><Plus size={17} aria-hidden />{t('addLand')}</button>}
      </div>
      {showParcel && (
        <form className="form-card form-grid" onSubmit={submitParcel} noValidate>
          <label>{t('parcelName')}<input maxLength={120} value={parcelForm.name} onChange={e => setParcelForm({ ...parcelForm, name: e.target.value })} /></label>
          <div className="inline-fields">
            <label>{t('area')}<input type="number" min="0.001" step="0.001" inputMode="decimal" value={parcelForm.area} onChange={e => setParcelForm({ ...parcelForm, area: e.target.value })} /></label>
            <label>{t('unit')}
              <select value={parcelForm.areaUnit} onChange={e => setParcelForm({ ...parcelForm, areaUnit: e.target.value })}>
                {AREA_UNITS.map(u => <option key={u} value={u}>{tu(u)}</option>)}
              </select>
            </label>
          </div>
          <label>{t('soil')}<input maxLength={80} value={parcelForm.soilType} onChange={e => setParcelForm({ ...parcelForm, soilType: e.target.value })} /></label>
          <label>{t('irrigation')}<input maxLength={80} value={parcelForm.irrigationType} onChange={e => setParcelForm({ ...parcelForm, irrigationType: e.target.value })} /></label>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setShowParcel(false)}>{tc('cancel')}</button>
            <button className="primary" disabled={busy}>{t('save')}</button>
          </div>
        </form>
      )}
      <div className="parcel-list">
        {parcels.map(p => (
          <div className="parcel-row" key={p.id}>
            <div className="parcel-icon"><Leaf size={20} aria-hidden /></div>
            <div className="parcel-info"><strong>{p.name}</strong><span>{[p.soilType, p.irrigationType].filter(Boolean).join(' · ')}</span></div>
            <div className="parcel-area"><strong>{formatNumber(p.area, lang)}</strong><span>{tu(p.areaUnit)}</span></div>
            {canWrite && <button type="button" className="rp-btn danger-btn" title={t('removeParcel')} aria-label={`${t('removeParcel')} ${p.name}`} onClick={() => void removeParcel(p)}><X size={15} aria-hidden /></button>}
          </div>
        ))}
        {!parcels.length && <EmptyState><MapPinned size={18} aria-hidden /> {t('emptyLand')}</EmptyState>}
      </div>
      {insight && <FarmInsight insight={insight} farmId={farm.id} farmName={farm.name} range={range} cycles={cycles} onOpen={setInsight} onClose={() => setInsight(null)} />}
    </section>
  );
}
