import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar, QuickRanges, ReportHeader } from '../reports/ReportTools';

type Row = { itemId: number; itemName: string; baseUnit: string; boughtQuantity: number; boughtValue: number; soldQuantity: number; revenue: number;
  costOfSales: number; margin: number; marginPercent: number | null; stockQuantity: number; stockValue: number };

/** The trading margin of goods bought for resale (processing and trading design, M10). Needs no module. */
export default function TradingPage() {
  const tenant = useTenant();
  const t = useNs('stock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const path = `/api/v1/tenants/${tenant.id}/stock/reports/trading`;
  const [range, setRange] = useState({ from: `${today().slice(0, 8)}01`, to: today() });
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState('');
  const q = `from=${range.from}&to=${range.to}`;

  useEffect(() => {
    if (!can(tenant, 'FINANCE_READ')) return;
    setRows(null); setError('');
    api<Row[]>(`${path}?${q}`).then(setRows).catch(e => setError(describeError(e)));
  }, [path, q, tenant]);

  if (!can(tenant, 'FINANCE_READ')) return <p className="hint">{t('trading.needsFinance')}</p>;
  const m = (v: number) => formatMoney(v, lang);
  const total = (rows ?? []).reduce((a, r) => ({ revenue: a.revenue + r.revenue, cogs: a.cogs + r.costOfSales, margin: a.margin + r.margin }), { revenue: 0, cogs: 0, margin: 0 });
  return (
    <div className="report-page">
      <div className="crop-page-head"><div><h2>{t('trading.title')}</h2><p>{t('trading.help')}</p></div></div>
      <QuickRanges onPick={setRange} />
      <div className="form-card form-grid filters no-print">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <ReportHeader title={t('trading.title')} period={tf('rangeLabel', { from: formatDate(range.from, lang), to: formatDate(range.to, lang) })} />
      <ErrorBanner message={error} />
      <div className="section-head"><h3>{t('trading.title')}</h3><ExportBar path={`${path}?${q}`} name={`trading-margin-${range.from}-${range.to}`} onError={setError} /></div>
      {!rows ? <Loading /> : rows.length === 0 ? <p className="hint">{t('trading.none')}</p> : (
        <div className="table-scroll">
          <table className="data-table report">
            <thead><tr><th>{t('item')}</th><th className="num">{t('trading.bought')}</th><th className="num">{t('trading.sold')}</th><th className="num">{tf('revenue')}</th>
              <th className="num">{t('trading.costOfSales')}</th><th className="num">{t('trading.margin')}</th><th className="num">{t('trading.marginPercent')}</th>
              <th className="num">{t('trading.inStock')}</th></tr></thead>
            <tbody>{rows.map(r => (
              <tr key={r.itemId}>
                <td>{r.itemName}</td>
                <td className="num">{formatNumber(r.boughtQuantity, lang, 3)} {r.baseUnit}<small> · {m(r.boughtValue)}</small></td>
                <td className="num">{formatNumber(r.soldQuantity, lang, 3)} {r.baseUnit}</td><td className="num">{m(r.revenue)}</td><td className="num">{m(r.costOfSales)}</td>
                <td className="num"><b>{m(r.margin)}</b></td><td className="num">{r.marginPercent == null ? '—' : `${formatNumber(r.marginPercent, lang, 1)}%`}</td>
                <td className="num">{formatNumber(r.stockQuantity, lang, 3)} {r.baseUnit}<small> · {m(r.stockValue)}</small></td>
              </tr>
            ))}</tbody>
            <tfoot><tr><th>{t('trading.total')}</th><th /><th /><th className="num">{m(total.revenue)}</th><th className="num">{m(total.cogs)}</th>
              <th className="num">{m(total.margin)}</th><th /><th /></tr></tfoot>
          </table>
          <p className="hint">{t('trading.note')}</p>
        </div>
      )}
    </div>
  );
}
