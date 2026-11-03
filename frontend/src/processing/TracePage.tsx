import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber } from '../lib/format';
import type { Origin, TracedLot } from './types';

/** Where a lot came from, in words. */
function OriginLine({ o }: { o: Origin | null }) {
  const t = useNs('processing');
  const lang = currentLanguage();
  if (!o || o.kind === 'OTHER') return <>{t('origin.OTHER')}</>;
  const when = o.date ? formatDate(o.date, lang) : '';
  if (o.kind === 'BATCH') return <>{t('origin.BATCH', { code: o.batchCode ?? '', date: when })}</>;
  if (o.kind === 'BOUGHT') return <>{t('origin.BOUGHT', { party: o.partyName ?? t('cashPurchase'), number: o.documentNumber ?? '', date: when })}</>;
  return <>{t('origin.PRODUCED', { activity: o.activityName ?? t('farmGeneral'), date: when })}</>;
}

/** Backward: the lot, its origin and the lots it was made from, recursively. */
function Backward({ lot }: { lot: TracedLot }) {
  return (
    <li><b>{lot.lotNo}</b> · {lot.itemName} — <OriginLine o={lot.origin} />
      {lot.inputs.length > 0 && <ul className="trace-tree">{lot.inputs.map(i => <Backward key={i.lotId} lot={i} />)}</ul>}
    </li>
  );
}

/** Forward: the batches that used the lot, their output lots, and the sales. */
function Forward({ lot }: { lot: TracedLot }) {
  const tenant = useTenant();
  const t = useNs('processing');
  const lang = currentLanguage();
  return (
    <>
      {lot.sales.length > 0 && <ul className="trace-tree">{lot.sales.map(s => (
        <li key={s.documentNumber}>{t('soldTo', { party: s.partyName ?? t('cashSale'), quantity: formatNumber(s.quantity, lang, 3), unit: lot.baseUnit,
          date: formatDate(s.date, lang), number: s.documentNumber })}</li>))}</ul>}
      {lot.usedIn.length > 0 && <ul className="trace-tree">{lot.usedIn.map(u => (
        <li key={u.batchId}>{t('usedIn', { quantity: formatNumber(u.quantity, lang, 3), unit: lot.baseUnit })}{' '}
          <Link to={`/t/${tenant.id}/processing/batches/${u.batchId}`}>{u.batchCode}</Link> · {u.batchName}
          <ul className="trace-tree">{u.outputs.map(o => <li key={o.lotId}><b>{o.lotNo}</b> · {o.itemName}<Forward lot={o} /></li>)}</ul>
        </li>))}</ul>}
    </>
  );
}

/** The lot trace (processing and trading design, M8): backward to the field or supplier, forward to the customers. */
export default function TracePage() {
  const tenant = useTenant();
  const t = useNs('processing');
  const [params, setParams] = useSearchParams();
  const [lotNo, setLotNo] = useState(params.get('lotNo') ?? '');
  const [lots, setLots] = useState<TracedLot[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const query = params.get('lotNo');

  useEffect(() => {
    if (!query) { setLots(null); return; }
    setLoading(true); setError('');
    api<TracedLot[]>(`/api/v1/tenants/${tenant.id}/processing/trace?lotNo=${encodeURIComponent(query)}`).then(setLots)
      .catch(e => { setLots(null); setError(describeError(e)); }).finally(() => setLoading(false));
  }, [query, tenant.id]);

  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('trace')}</h2><p>{t('traceHelp')}</p></div></div>
      <form className="form-card form-grid filters no-print" onSubmit={e => { e.preventDefault(); if (lotNo.trim()) setParams({ lotNo: lotNo.trim() }); }}>
        <label>{t('lot')}<input maxLength={60} placeholder="RM-2610-01-1" value={lotNo} onChange={e => setLotNo(e.target.value)} /></label>
        <div className="form-actions"><button className="primary">{t('traceLot')}</button>
          {lots && <button type="button" className="secondary" onClick={() => window.print()}>{t('print')}</button>}</div>
      </form>
      <ErrorBanner message={error} />
      {loading && <Loading />}
      {lots && lots.map(l => (
        <div key={l.lotId} className="form-card section-space">
          <h3>{l.lotNo} · {l.itemName}</h3>
          <h4>{t('backward')}</h4>
          <ul className="trace-tree"><Backward lot={l} /></ul>
          <h4>{t('forward')}</h4>
          {l.sales.length === 0 && l.usedIn.length === 0 ? <p className="hint">{t('notUsedYet')}</p> : <Forward lot={l} />}
        </div>
      ))}
    </div>
  );
}
