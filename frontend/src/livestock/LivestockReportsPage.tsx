import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar, QuickRanges, ReportHeader } from '../reports/ReportTools';
import type { FlockReport, HerdRow, MilkReport } from './types';

/** Groups of a section in the batches-and-herds report: poultry is chickens, goats and sheep the rest. */
const inScope = (species: string, scope: 'poultry' | 'smallStock') => (scope === 'poultry' ? species === 'CHICKEN' : species !== 'CHICKEN' && species !== 'CATTLE');

/**
 * Reports of one animal section. Cattle: the milk report and herd profitability with cost per litre produced (I13).
 * Poultry, or goats and sheep: their batches or herds (poultry, goats and sheep design, J14).
 */
export default function LivestockReportsPage({ scope = 'cattle' }: { scope?: 'cattle' | 'poultry' | 'smallStock' }) {
  const tenant = useTenant();
  const t = useNs('livestock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const base = `/api/v1/tenants/${tenant.id}/livestock/reports`;
  const showMoney = can(tenant, 'FINANCE_READ');
  const [range, setRange] = useState({ from: `${today().slice(0, 7)}-01`, to: today() });
  const [milk, setMilk] = useState<MilkReport | null>(null);
  const [herd, setHerd] = useState<HerdRow[] | null>(null);
  const [flocks, setFlocks] = useState<FlockReport[] | null>(null);
  const [error, setError] = useState('');
  const dairy = scope === 'cattle' && tenant.enabledModules.includes('DAIRY');
  const anyCounted = tenant.enabledModules.includes('POULTRY') || tenant.enabledModules.includes('GOAT_SHEEP');
  const counted = scope !== 'cattle' && anyCounted;
  const herdMoney = scope === 'cattle' && showMoney;
  const q = `from=${range.from}&to=${range.to}`;

  useEffect(() => {
    setMilk(null); setHerd(null); setFlocks(null); setError('');
    if (dairy) api<MilkReport>(`${base}/milk?${q}`).then(setMilk).catch(e => setError(describeError(e)));
    // Cattle reports read the batches too, only to leave poultry and goat groups out of herd profitability
    if (anyCounted) api<FlockReport[]>(`${base}/flocks?${q}`).then(setFlocks).catch(e => setError(describeError(e)));
    if (herdMoney) api<{ groups: HerdRow[] }>(`${base}/herd?${q}`).then(r => setHerd(r.groups)).catch(e => setError(describeError(e)));
  }, [base, q, herdMoney, dairy, anyCounted]);
  const shownFlocks = scope === 'cattle' ? null : flocks?.filter(r => inScope(r.group.species, scope)) ?? null;
  const flockIds = new Set((flocks ?? []).map(r => r.group.id));
  const cattleHerd = herd?.filter(h => !flockIds.has(h.groupId)) ?? null;

  const n = (v: number | null) => (v == null ? '—' : formatNumber(v, lang, 2));
  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('reports')}</h2></div></div>
      <QuickRanges onPick={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <ReportHeader title={t(scope === 'cattle' ? (dairy ? 'report.milk' : 'report.herd') : 'report.flocks')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />


      {counted && <>
        <div className="section-head section-space"><h3>{t('report.flocks')}</h3><ExportBar path={`${base}/flocks?${q}`} name={`batches-and-herds-${range.from}-${range.to}`} onError={setError} /></div>
        {!shownFlocks ? <Loading /> : shownFlocks.length === 0 ? <p className="hint">{t('nothingInPeriod')}</p> : (
          <div className="table-scroll">
            <table className="data-table report">
              <thead><tr><th>{t('group')}</th><th className="num">{t('openingHeads')}</th><th className="num">{t('closingHeads')}</th><th className="num">{t('died')}</th>
                <th className="num">{t('mortality')}</th><th className="num">{t('soldHeads')}</th><th className="num">{t('eggs')}</th><th className="num">{t('henDay')}</th>
                <th className="num">{t('feedKg')}</th><th className="num">{t('fcr')}</th>
                {showMoney && <><th className="num">{tf('revenue')}</th><th className="num">{tf('costs')}</th><th className="num">{t('profit')}</th></>}</tr></thead>
              <tbody>{shownFlocks.map(r => { const f = r.period; return (
                <tr key={r.group.id}>
                  <td><Link to={`/t/${tenant.id}/livestock/${r.group.species === 'CHICKEN' ? 'poultry' : 'small-stock'}/${r.group.id}`}>{r.group.name}</Link><small> · {t(`purpose.${r.group.purpose}`)}</small></td>
                  <td className="num">{f.openingHeads}</td><td className="num">{f.closingHeads}</td><td className="num">{f.died}</td><td className="num">{n(f.mortalityPercent)}</td>
                  <td className="num">{f.sold}</td><td className="num">{r.group.species === 'CHICKEN' ? f.eggs : '—'}</td><td className="num">{n(f.henDayPercent)}</td>
                  <td className="num">{r.group.species === 'CHICKEN' ? n(f.feedKg) : '—'}</td><td className="num">{n(f.fcr)}</td>
                  {showMoney && <><td className="num">{f.revenue != null ? formatMoney(f.revenue, lang) : '—'}</td><td className="num">{f.costs != null ? formatMoney(f.costs, lang) : '—'}</td>
                    <td className="num"><b>{f.profit != null ? formatMoney(f.profit, lang) : '—'}</b></td></>}
                </tr>); })}</tbody>
            </table>
            <p className="hint">{t('flocksHelpReport')}</p>
          </div>
        )}
      </>}

      {herdMoney && <>
        <div className="section-head section-space"><h3>{t('report.herd')}</h3><ExportBar path={`${base}/herd?${q}`} name={`herd-profitability-${range.from}-${range.to}`} onError={setError} /></div>
        {!cattleHerd ? <Loading /> : cattleHerd.length === 0 ? <p className="hint">{t('nothingInPeriod')}</p> : (
          <div className="table-scroll">
            <table className="data-table report">
              <thead><tr><th>{t('group')}</th><th className="num">{tf('revenue')}</th><th className="num">{tf('costs')}</th><th className="num">{t('profit')}</th>
                <th className="num">{t('produced')}</th><th className="num">{t('costPerLitre')}</th><th className="num">{t('pricePerLitre')}</th></tr></thead>
              <tbody>{cattleHerd.map(h => (
                <tr key={h.groupId}>
                  <td>{h.groupName}<small>{h.costs.length > 0 ? ` · ${h.costs.map(c => `${c.categoryId ? categoryLabel({ code: c.categoryCode, name: c.categoryName }) : tf(`ledger.${c.accountCode}`)} ${formatMoney(c.amount, lang)}`).join(', ')}` : ''}</small></td>
                  <td className="num">{formatMoney(h.revenue, lang)}</td><td className="num">{formatMoney(h.costTotal, lang)}</td><td className="num"><b>{formatMoney(h.profit, lang)}</b></td>
                  <td className="num">{n(h.litresProduced)}</td><td className="num">{h.costPerLitre != null ? formatMoney(h.costPerLitre, lang) : '—'}</td>
                  <td className="num">{h.pricePerLitre != null ? formatMoney(h.pricePerLitre, lang) : '—'}</td>
                </tr>
              ))}</tbody>
            </table>
            <p className="hint">{t('herdHelpReport')}</p>
          </div>
        )}
      </>}

      {dairy && <>
      <div className="section-head section-space"><h3>{t('report.milk')}</h3><ExportBar path={`${base}/milk?${q}`} name={`milk-${range.from}-${range.to}`} onError={setError} /></div>
      {!milk ? <Loading /> : (
        <>
          <div className="finance-stats">
            <div className="finance-stat"><span>{t('produced')}</span><b>{n(milk.produced)}</b>
              <small>{t('fat')} {n(milk.fat)} · {t('snf')} {n(milk.snf)}</small></div>
            <div className="finance-stat"><span>{t('sold')}</span><b>{n(milk.sold)}</b></div>
            <div className="finance-stat"><span>{t('homeUse')}</span><b>{n(milk.homeUse)}</b></div>
            <div className="finance-stat"><span>{t('fedToAnimals')}</span><b>{n(milk.fedToAnimals)}</b></div>
            <div className="finance-stat"><span>{t('discarded')}</span><b>{n(milk.discarded)}</b></div>
          </div>
          {milk.animals.length > 0 && (
            <table className="data-table report">
              <thead><tr><th>{t('tag')}</th><th className="num">{t('litres')}</th><th className="num">{t('daysRecorded')}</th><th className="num">{t('perDay')}</th></tr></thead>
              <tbody>{milk.animals.map(a => <tr key={a.animalId}><td>{a.tag}{a.name ? ` ${a.name}` : ''}</td><td className="num">{n(a.litres)}</td>
                <td className="num">{a.days}</td><td className="num">{n(a.perDay)}</td></tr>)}</tbody>
            </table>
          )}
          {milk.days.length > 0 && (
            <details className="trend-table section-space">
              <summary>{t('byDay')}</summary>
              <table className="data-table report">
                <thead><tr><th>{tf('date')}</th><th>{t('group')}</th><th className="num">{t('session.MORNING')}</th><th className="num">{t('session.EVENING')}</th>
                  <th className="num">{t('litres')}</th></tr></thead>
                <tbody>{milk.days.map(d => <tr key={`${d.date}-${d.groupId}`}><td>{formatDate(d.date, lang)}</td><td>{d.groupName}</td>
                  <td className="num">{n(d.morning)}</td><td className="num">{n(d.evening)}</td><td className="num">{n(d.total)}</td></tr>)}</tbody>
              </table>
            </details>
          )}
        </>
      )}
      </>}
    </div>
  );
}
