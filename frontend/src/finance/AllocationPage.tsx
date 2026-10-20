import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import type { AllocationPreview } from '../crop/types';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatMoney, formatNumber, today } from '../lib/format';
import type { FinanceDocument } from './types';
import { newIdempotencyKey, useCategoryLabel } from './useFinanceData';

const lastMonth = () => {
  const d = new Date();
  const first = new Date(d.getFullYear(), d.getMonth() - 1, 1);
  const last = new Date(d.getFullYear(), d.getMonth(), 0);
  const iso = (x: Date) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
  return { from: iso(first), to: iso(last) };
};

/** Spreads farm overhead of a period to the crops growing in it, by area × days (P16). Preview first, then post. */
export default function AllocationPage() {
  const tenant = useTenant();
  const t = useNs('fin');
  const tc = useNs('common');
  const lang = currentLanguage();
  const label = useCategoryLabel();
  const navigate = useNavigate();
  const base = `/api/v1/tenants/${tenant.id}/finance/overhead-allocations`;
  const [range, setRange] = useState(lastMonth);
  const [preview, setPreview] = useState<AllocationPreview | null>(null);
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function show() {
    setError(''); setPreview(null);
    try { setPreview(await api<AllocationPreview>(`${base}/preview`, { method: 'POST', body: range })); setKey(newIdempotencyKey()); }
    catch (e) { setError(describeError(e)); }
  }

  async function post() {
    setBusy(true); setError('');
    try {
      const doc = await api<FinanceDocument>(base, { method: 'POST', body: range, headers: { 'Idempotency-Key': key } });
      navigate(`/t/${tenant.id}/finance/records/${doc.summary.id}`);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="crop-page-head"><div><h2>{t('allocation.title')}</h2><p>{t('allocation.help')}</p></div></div>
      <ErrorBanner message={error} />
      <form className="form-card form-grid filters" onSubmit={e => { e.preventDefault(); void show(); }}>
        <label>{t('from')}<input type="date" max={today()} value={range.from} onChange={e => { setRange({ ...range, from: e.target.value }); setPreview(null); }} /></label>
        <label>{t('to')}<input type="date" min={range.from} value={range.to} onChange={e => { setRange({ ...range, to: e.target.value }); setPreview(null); }} /></label>
        <div className="form-actions"><button className="secondary">{t('allocation.preview')}</button></div>
      </form>
      {preview && (
        <>
          {preview.groups.length === 0 ? <p className="hint">{t('allocation.nothing')}</p> : preview.groups.map(g => (
            <div key={`${g.accountCode}-${g.categoryId}-${g.farmId}`} className="section-space">
              <h3>{g.categoryId ? label({ code: g.categoryCode, name: g.categoryName }) : t(`ledger.${g.accountCode}`)}: {formatMoney(g.amount, lang)}</h3>
              {g.shares.length === 0 ? <p className="hint">{t('allocation.noCrops')}</p> : (
                <table className="data-table">
                  <thead><tr><th>{t('activity')}</th><th className="num">{t('allocation.acres')}</th><th className="num">{t('allocation.days')}</th><th className="num">{t('amount')}</th></tr></thead>
                  <tbody>{g.shares.map(s => (
                    <tr key={s.costObjectId}><td>{s.name}</td><td className="num">{formatNumber(s.areaAcres, lang, 2)}</td><td className="num">{s.days}</td>
                      <td className="num">{formatMoney(s.amount, lang)}</td></tr>
                  ))}</tbody>
                </table>
              )}
            </div>
          ))}
          <p className="total-line">{t('allocation.total', { amount: formatMoney(preview.allocated, lang) })}
            {preview.notAllocated > 0 ? ` · ${t('allocation.stays', { amount: formatMoney(preview.notAllocated, lang) })}` : ''}</p>
          {preview.allocated > 0 && (
            <div className="form-actions">
              <button type="button" className="primary" disabled={busy} onClick={() => void post()}>{busy ? tc('loading') : t('allocation.post')}</button>
            </div>
          )}
        </>
      )}
    </>
  );
}
