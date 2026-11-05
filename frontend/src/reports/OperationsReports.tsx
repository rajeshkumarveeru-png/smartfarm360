import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { ExportBar, QuickRanges, ReportHeader } from './ReportTools';
import type { RunningCostsReport, WagesReport } from './types';

const monthStart = () => `${today().slice(0, 7)}-01`;
const money = (v: number | null, lang: 'en' | 'ta') => (v == null ? '' : formatMoney(v, lang));

function useReport<T>(url: string) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let alive = true;
    setData(null); setError('');
    api<T>(url).then(d => alive && setData(d)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [url]);
  return { data, error, setError };
}

function Range({ range, setRange }: { range: { from: string; to: string }; setRange: (r: { from: string; to: string }) => void }) {
  const tf = useNs('fin');
  return (
    <>
      <QuickRanges onPick={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
    </>
  );
}

/** Wages by worker and labour by crop for a period (dashboard and reports design, section 4). */
export function WagesReportPage() {
  const tenant = useTenant();
  const t = useNs('reports');
  const tw = useNs('workforce');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const [range, setRange] = useState({ from: monthStart(), to: today() });
  const path = `/api/v1/tenants/${tenant.id}/workforce/reports/wages?from=${range.from}&to=${range.to}`;
  const { data, error, setError } = useReport<WagesReport>(path);
  return (
    <section className="crop-page report-page">
      <div className="crop-page-head"><div><h2>{t('title.wages')}</h2><p>{t('help.wages')}</p></div>
        <ExportBar path={path} name={`wages-${range.from}-${range.to}`} onError={setError} /></div>
      <Range range={range} setRange={setRange} />
      <ReportHeader title={t('title.wages')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      {!data ? !error && <Loading /> : (
        <>
          {!data.amountsShown && <p className="hint">{t('amountsHidden')}</p>}
          {data.workers.length === 0 ? <p className="hint">{t('nothingInPeriod')}</p> : (
            <div className="table-scroll">
              <table className="data-table report">
                <thead><tr><th>{tw('worker')}</th><th className="num">{tw('days')}</th>
                  {data.amountsShown && <><th className="num">{tw('wages')}</th><th className="num">{tw('paidOut')}</th><th className="num">{tw('advanceGiven')}</th>
                    <th className="num">{tw('advanceRecovered')}</th><th className="num">{t('owedAtEnd')}</th><th className="num">{t('advanceAtEnd')}</th></>}</tr></thead>
                <tbody>
                  {data.workers.map(w => (
                    <tr key={w.workerId}>
                      <td><Link to={`/t/${tenant.id}/workforce/workers/${w.workerId}`}>{w.name}</Link><small> · {tw(`payType.${w.payType}`)}</small></td>
                      <td className="num">{formatNumber(w.days, lang, 1)}</td>
                      {data.amountsShown && [w.wages, w.paid, w.advanceGiven, w.advanceRecovered, w.owedAtEnd, w.advanceAtEnd]
                        .map((v, i) => <td key={i} className="num">{money(v, lang)}</td>)}
                    </tr>
                  ))}
                  <tr className="total"><td>{tw('total')}</td><td className="num">{formatNumber(data.total.days, lang, 1)}</td>
                    {data.amountsShown && [data.total.wages, data.total.paid, data.total.advanceGiven, data.total.advanceRecovered, data.total.owedAtEnd, data.total.advanceAtEnd]
                      .map((v, i) => <td key={i} className="num">{money(v, lang)}</td>)}</tr>
                </tbody>
              </table>
            </div>
          )}
          <h3>{tw('labourByActivity')}</h3>
          {data.byCrop.rows.length === 0 ? <p className="hint">{tw('noLabour')}</p> : (
            <div className="table-scroll"><table className="data-table report">
              <thead><tr><th>{tf('activity')}</th><th className="num">{tw('days')}</th>{data.amountsShown && <th className="num">{tw('cost')}</th>}</tr></thead>
              <tbody>{data.byCrop.rows.map(r => <tr key={r.costObjectId}><td>{r.kind === 'OVERHEAD' ? tf('overhead') : r.name}</td>
                <td className="num">{formatNumber(r.days, lang, 1)}</td>{data.amountsShown && <td className="num">{money(r.cost, lang)}</td>}</tr>)}</tbody>
            </table></div>
          )}
        </>
      )}
    </section>
  );
}

/** Depreciation, running costs, use and maintenance by asset for a period (dashboard and reports design, section 4). */
export function RunningCostsPage() {
  const tenant = useTenant();
  const t = useNs('reports');
  const ta = useNs('assets');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const [range, setRange] = useState({ from: monthStart(), to: today() });
  const path = `/api/v1/tenants/${tenant.id}/assets/reports/running-costs?from=${range.from}&to=${range.to}`;
  const { data, error, setError } = useReport<RunningCostsReport>(path);
  return (
    <section className="crop-page report-page">
      <div className="crop-page-head"><div><h2>{t('title.runningCosts')}</h2><p>{t('help.runningCosts')}</p></div>
        <ExportBar path={path} name={`running-costs-${range.from}-${range.to}`} onError={setError} /></div>
      <Range range={range} setRange={setRange} />
      <ReportHeader title={t('title.runningCosts')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      {!data ? !error && <Loading /> : data.assets.length === 0 ? <p className="hint">{ta('noAssets')}</p> : (
        <>
          {!data.amountsShown && <p className="hint">{t('amountsHidden')}</p>}
          <div className="table-scroll">
            <table className="data-table report">
              <thead><tr><th>{ta('name')}</th>
                {data.amountsShown && <><th className="num">{ta('depreciation')}</th><th className="num">{ta('runningCosts')}</th><th className="num">{ta('totalCost')}</th></>}
                <th className="num">{ta('hours')}</th>{data.amountsShown && <th className="num">{t('costPerHour')}</th>}
                <th className="num">{t('maintenanceDone')}</th><th className="num">{t('plansDue')}</th></tr></thead>
              <tbody>
                {data.assets.map(a => (
                  <tr key={a.assetId}>
                    <td><Link to={`/t/${tenant.id}/assets/${a.assetId}`}>{a.name}</Link><small> · {ta(`kind.${a.kind}`)}{a.status !== 'IN_USE' ? ` · ${ta(`status.${a.status}`)}` : ''}</small></td>
                    {data.amountsShown && <><td className="num">{money(a.depreciation, lang)}</td><td className="num">{money(a.runningCosts, lang)}</td>
                      <td className="num">{money(a.totalCost, lang)}</td></>}
                    <td className="num">{formatNumber(a.hours, lang, 1)}</td>{data.amountsShown && <td className="num">{money(a.costPerHour, lang)}</td>}
                    <td className="num">{a.maintenanceDone}</td><td className="num">{a.plansDue > 0 ? <b className="low-stock-label">{a.plansDue}</b> : 0}</td>
                  </tr>
                ))}
                {data.amountsShown && <tr className="total"><td>{tf('total')}</td><td className="num">{money(data.depreciation, lang)}</td>
                  <td className="num">{money(data.runningCosts, lang)}</td><td className="num">{money(data.totalCost, lang)}</td><td colSpan={4} /></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}
