import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Statement, Worker } from './types';

const yearStart = () => `${today().slice(0, 4)}-01-01`;

/** One worker: rates, leaving, and a statement of wages, payments and advances. */
export default function WorkerPage() {
  const tenant = useTenant();
  const { workerId } = useParams();
  const t = useNs('workforce');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/workforce/workers/${workerId}`;
  const [worker, setWorker] = useState<Worker | null>(null);
  const [range, setRange] = useState({ from: yearStart(), to: today() });
  const [statement, setStatement] = useState<Statement | null>(null);
  const [rate, setRate] = useState({ effectiveFrom: today(), rate: '' });
  const [leftOn, setLeftOn] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(() => api<Worker>(base).then(setWorker).catch(e => setError(describeError(e))), [base]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    api<Statement>(`${base}/statement?from=${range.from}&to=${range.to}`).then(setStatement).catch(e => setError(describeError(e)));
  }, [base, range, worker]);

  async function act(path: string, method: string, body: unknown) {
    setError('');
    try { setWorker(await api<Worker>(`${base}${path}`, { method, body })); } catch (e) { setError(describeError(e)); }
  }

  if (!worker) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <>
      <div className="crop-page-head">
        <div>
          <h2>{worker.name}</h2>
          <p>{t(`payType.${worker.payType}`)} · {t('joinedOn')}: {formatDate(worker.joinedOn, lang)}{worker.leftOn ? ` · ${t('leftOn')}: ${formatDate(worker.leftOn, lang)}` : ''}
            {worker.phone ? ` · ${worker.phone}` : ''}{worker.village ? ` · ${worker.village}` : ''}</p>
        </div>
        <Link className="secondary" to={`/t/${tenant.id}/workforce/workers`}>{tc('back')}</Link>
      </div>
      <ErrorBanner message={error} />
      <div className="finance-stats">
        <div className="finance-stat"><span>{t('rate')}</span><b>{worker.rate != null ? formatMoney(worker.rate, lang) : '—'}</b><small>{t(`rateLabel.${worker.payType}`)}</small></div>
        <div className="finance-stat"><span>{t('owed')}</span><b>{formatMoney(worker.owed, lang)}</b></div>
        <div className="finance-stat"><span>{t('advance')}</span><b>{formatMoney(worker.advance, lang)}</b></div>
      </div>
      <ul className="plain-list">{worker.rates.map(r => <li key={r.effectiveFrom}>{t('rateFrom', { date: formatDate(r.effectiveFrom, lang) })}: {formatMoney(r.rate, lang)}{r.pieceUnit ? ` / ${r.pieceUnit}` : ''}</li>)}</ul>
      {can(tenant, 'PAYROLL_MANAGE') && (
        <form className="inline-form" onSubmit={e => { e.preventDefault(); void act('/rates', 'POST', { effectiveFrom: rate.effectiveFrom, rate: rate.rate, pieceUnit: worker.pieceUnit }); }}>
          <label>{t('newRateFrom')}<input type="date" value={rate.effectiveFrom} onChange={e => setRate({ ...rate, effectiveFrom: e.target.value })} /></label>
          <label>{t('rate')}<input inputMode="decimal" value={rate.rate} onChange={e => setRate({ ...rate, rate: e.target.value })} /></label>
          <button className="secondary">{t('changeRate')}</button>
        </form>
      )}
      {can(tenant, 'WORKFORCE_WRITE') && (worker.active ? (
        <form className="inline-form" onSubmit={e => { e.preventDefault(); if (leftOn && window.confirm(t('confirmLeaving'))) void act('', 'PATCH', { leftOn }); }}>
          <label>{t('lastDay')}<input type="date" value={leftOn} onChange={e => setLeftOn(e.target.value)} /></label>
          <button className="secondary">{t('markLeft')}</button>
        </form>
      ) : <button type="button" className="secondary" onClick={() => void act('', 'PATCH', { rejoin: true })}>{t('rejoin')}</button>)}

      <div className="section-head section-space"><h3>{t('statement')}</h3></div>
      <div className="form-card form-grid filters">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      {!statement ? <Loading /> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{tf('date')}</th><th>{t('record')}</th><th className="num">{t('wages')}</th><th className="num">{t('paidOut')}</th>
              <th className="num">{t('advanceGiven')}</th><th className="num">{t('advanceRecovered')}</th><th className="num">{t('balance')}</th></tr></thead>
            <tbody>
              <tr><td colSpan={6}>{t('openingBalance')}</td><td className="num">{formatMoney(statement.opening, lang)}</td></tr>
              {statement.lines.map((l, i) => (
                <tr key={`${l.documentId}-${i}`}>
                  <td>{formatDate(l.date, lang)}</td>
                  <td>{can(tenant, 'FINANCE_READ') ? <Link to={`/t/${tenant.id}/finance/records/${l.documentId}`}>{l.number}</Link> : l.number}
                    <small> · {tf(`type.${l.documentType}`)}{l.reversal ? ` · ${tf('status.REVERSED')}` : ''}</small></td>
                  {[l.wages, l.paid, l.advanceGiven, l.advanceRecovered, l.balance].map((v, j) => <td key={j} className="num">{v ? formatMoney(v, lang) : ''}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
          <p className="hint">{t('balanceHelp')}</p>
        </div>
      )}
    </>
  );
}
