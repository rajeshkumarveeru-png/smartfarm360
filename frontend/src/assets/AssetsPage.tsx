import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Plus } from 'lucide-react';
import { api, download } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import DetailsFields, { detailsValid } from './DetailsFields';
import type { Asset, DetailsForm, UnregisteredLine } from './types';
import { detailsBody, emptyDetails, isMoney } from './types';

/** The asset register: buy an asset on a bill, register costs already on Farm assets, and export the register (G8). */
export default function AssetsPage() {
  const tenant = useTenant();
  const t = useNs('assets');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/assets`;
  const canWrite = can(tenant, 'ASSETS_WRITE');
  const canBuy = canWrite && can(tenant, 'FINANCE_WRITE');
  const [showDisposed, setShowDisposed] = useState(false);
  const [assets, setAssets] = useState<Asset[] | null>(null);
  const [unregistered, setUnregistered] = useState<UnregisteredLine[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [mode, setMode] = useState<'' | 'buy' | number>('');
  const [details, setDetails] = useState<DetailsForm>(emptyDetails(today()));
  const [bill, setBill] = useState({ docDate: today(), amount: '', partyId: '', financialAccountId: '', paidNow: '', referenceNo: '' });
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [a, u] = await Promise.all([api<Asset[]>(`${base}?includeDisposed=${showDisposed}`), api<UnregisteredLine[]>(`${base}/unregistered`)]);
      setAssets(a); setUnregistered(u);
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, showDisposed]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!canBuy) return;
    const fin = `/api/v1/tenants/${tenant.id}/finance`;
    Promise.all([api<Party[]>(`${fin}/parties`), api<FinancialAccount[]>(`${fin}/accounts`)])
      .then(([p, a]) => { setParties(p.filter(x => x.active)); setAccounts(a.filter(x => x.active)); }).catch(() => undefined);
  }, [canBuy, tenant.id]);

  function start(next: '' | 'buy' | number, line?: UnregisteredLine) {
    setMode(next); setError('');
    setDetails({ ...emptyDetails(line?.date ?? today()), name: line?.description ?? '' });
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!detailsValid(details)) { setError(t('errors.details')); return; }
    if (mode === 'buy') {
      const paid = bill.partyId && bill.paidNow ? bill.paidNow : '';
      if (!isMoney(bill.amount) || (!bill.partyId && !bill.financialAccountId) || (paid && (!isMoney(paid) || !bill.financialAccountId))) {
        setError(t('errors.bill')); return;
      }
      await send(() => api<Asset>(`${base}/purchases`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: {
        docDate: bill.docDate, amount: bill.amount, referenceNo: bill.referenceNo || null,
        partyId: bill.partyId ? Number(bill.partyId) : null,
        financialAccountId: !bill.partyId && bill.financialAccountId ? Number(bill.financialAccountId) : null,
        paidNow: paid ? { financialAccountId: Number(bill.financialAccountId), amount: paid } : null,
        asset: detailsBody(details) } }));
      setKey(newIdempotencyKey());
    } else if (typeof mode === 'number') {
      await send(() => api<Asset>(base, { method: 'POST', body: { sourceLineId: mode, asset: detailsBody(details) } }));
    }
  }

  async function send(action: () => Promise<Asset>) {
    setBusy(true); setError('');
    try { await action(); setMode(''); await load(); } catch (e) { setError(describeError(e)); } finally { setBusy(false); }
  }

  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('register')}</h2><p>{t('registerHelp')}</p></div>
        <div className="quick-actions">
          {canBuy && mode === '' && <button type="button" className="primary" onClick={() => start('buy')}><Plus size={16} aria-hidden />{t('buyAsset')}</button>}
          <button type="button" className="secondary" onClick={() => download(`${base}/reports/register.csv`, `asset-register-${today()}.csv`).catch(e => setError(describeError(e)))}>
            <Download size={16} aria-hidden />{t('exportCsv')}</button>
        </div>
      </div>
      <ErrorBanner message={error} />
      {mode !== '' && (
        <form className="form-card form-grid" noValidate onSubmit={e => void submit(e)}>
          <h3 className="full">{mode === 'buy' ? t('buyAsset') : t('registerLine')}</h3>
          {mode === 'buy' && (
            <>
              <label>{tf('date')}<input type="date" value={bill.docDate} onChange={e => setBill({ ...bill, docDate: e.target.value })} /></label>
              <label>{t('cost')}<input inputMode="decimal" value={bill.amount} onChange={e => setBill({ ...bill, amount: e.target.value })} /></label>
              <label>{t('boughtFrom')} <small>{tc('optional')}</small><select value={bill.partyId} onChange={e => setBill({ ...bill, partyId: e.target.value })}>
                <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
              <label>{bill.partyId ? t('paidNowFrom') : t('paidFrom')}<select value={bill.financialAccountId} onChange={e => setBill({ ...bill, financialAccountId: e.target.value })}>
                <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
              {bill.partyId && <label>{t('paidNow')} <small>{tc('optional')}</small><input inputMode="decimal" value={bill.paidNow} onChange={e => setBill({ ...bill, paidNow: e.target.value })} /></label>}
              <label>{t('referenceNo')} <small>{tc('optional')}</small><input maxLength={100} value={bill.referenceNo} onChange={e => setBill({ ...bill, referenceNo: e.target.value })} /></label>
            </>
          )}
          <DetailsFields value={details} onChange={setDetails} />
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setMode('')}>{tc('cancel')}</button>
            <button className="primary" disabled={busy}>{tc('save')}</button>
          </div>
        </form>
      )}

      <label className="check"><input type="checkbox" checked={showDisposed} onChange={e => setShowDisposed(e.target.checked)} />{t('showDisposed')}</label>
      {!assets ? <Loading /> : assets.length === 0 ? <p className="hint">{t('noAssets')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{t('name')}</th><th>{t('kindLabel')}</th><th className="num">{t('cost')}</th><th className="num">{t('accumulated')}</th>
              <th className="num">{t('bookValue')}</th><th className="num">{t('perMonth')}</th></tr></thead>
            <tbody>
              {assets.map(a => (
                <tr key={a.id}>
                  <td><Link to={`/t/${tenant.id}/assets/${a.id}`}>{a.name}</Link>{a.status !== 'IN_USE' && <small> · {t(`status.${a.status}`)}</small>}</td>
                  <td>{t(`kind.${a.kind}`)}</td>
                  <td className="num">{formatMoney(a.cost, lang)}</td><td className="num">{formatMoney(a.accumulated, lang)}</td>
                  <td className="num">{formatMoney(a.bookValue, lang)}</td>
                  <td className="num">{a.monthlyDepreciation != null ? formatMoney(a.monthlyDepreciation, lang) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h3 className="section-space">{t('unregistered')}</h3>
      <p className="hint">{t('unregisteredHelp')}</p>
      {unregistered.length === 0 ? <p className="hint">{t('noUnregistered')}</p> : (
        <ul className="plain-list">
          {unregistered.map(l => (
            <li key={l.lineId}>{formatDate(l.date, lang)} · {l.number} · {l.description ?? tf(`type.${l.documentType}`)} · <b>{formatMoney(l.amount, lang)}</b>
              {canWrite && mode === '' && <button type="button" className="text-action" onClick={() => start(l.lineId, l)}>{t('registerThis')}</button>}</li>
          ))}
        </ul>
      )}
    </>
  );
}
