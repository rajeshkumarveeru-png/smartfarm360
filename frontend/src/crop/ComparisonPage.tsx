import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Download } from 'lucide-react';
import { api, download } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import type { Comparison } from './types';

/** Every cycle of one crop side by side: plan against actual, yield, cost per acre and profit (F8). */
export default function ComparisonPage() {
  const tenant = useTenant();
  const { cropId } = useParams();
  const t = useNs('crop');
  const tc = useNs('common');
  const ts = useNs('stock');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/crops/${cropId}`;
  const [data, setData] = useState<Comparison | null>(null);
  const [error, setError] = useState('');
  useEffect(() => { api<Comparison>(`${base}/comparison`).then(setData).catch(e => setError(describeError(e))); }, [base]);
  if (!data) return error ? <ErrorBanner message={error} /> : <Loading />;
  const date = (d: string | null) => (d ? formatDate(d, lang) : '—');
  const num = (n: number | null, digits = 2) => (n == null ? '—' : formatNumber(n, lang, digits));
  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('comparisonTitle', { crop: data.cropName })}</h2><p>{t('comparisonHelp')}</p></div>
        <div className="form-actions">
          <button type="button" className="secondary" onClick={() => download(`${base}/comparison.csv`, `crop-comparison-${cropId}.csv`).catch(e => setError(describeError(e)))}>
            <Download size={16} aria-hidden />{ts('exportCsv')}</button>
          <Link className="secondary" to={`/t/${tenant.id}/crops/setup`}>{tc('back')}</Link>
        </div>
      </div>
      <ErrorBanner message={error} />
      {data.cycles.length === 0 ? <p className="hint">{t('noCycles')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{t('cycleLabel')}</th><th>{t('sowing')}</th><th>{t('firstHarvest')}</th><th className="num">{t('area')}</th>
              <th className="num">{t('yield')}</th><th className="num">{t('yieldPerAcre')}</th><th className="num">{t('costPerAcre')}</th>
              <th className="num">{t('revenueShort')}</th><th className="num">{t('profit')}</th></tr></thead>
            <tbody>
              {data.cycles.map(r => (
                <tr key={r.cycleId}>
                  <td><Link to={`/t/${tenant.id}/crops/cycles/${r.cycleId}`}>{r.cycleName}</Link>{r.seasonName ? <small> · {r.seasonName}</small> : null}
                    <div><small>{t(`stage.${r.status}`)}</small></div></td>
                  <td>{date(r.plannedSowingDate)}{r.actualSowingDate && r.actualSowingDate !== r.plannedSowingDate ? <div><small>{t('sown')}: {date(r.actualSowingDate)}</small></div> : null}</td>
                  <td>{date(r.firstHarvestDate)}{r.expectedHarvestDate ? <div><small>{t('planned')}: {date(r.expectedHarvestDate)}</small></div> : null}</td>
                  <td className="num">{num(r.areaAcres)}</td>
                  <td className="num">{num(r.harvested)} {r.unit ? ts(`unit.${r.unit}`, { defaultValue: r.unit }) : ''}
                    {r.expectedYield != null ? <div><small>{t('planned')}: {num(r.expectedYield)}</small></div> : null}</td>
                  <td className="num">{num(r.yieldPerAcre)}</td>
                  <td className="num">{r.costPerAcre != null ? formatMoney(r.costPerAcre, lang) : '—'}</td>
                  <td className="num">{formatMoney(r.revenue, lang)}</td>
                  <td className="num">{formatMoney(r.profit, lang)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
