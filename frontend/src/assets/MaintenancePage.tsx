import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber } from '../lib/format';
import type { Plan } from './types';

const ORDER = { OVERDUE: 0, DUE: 1, OK: 2 } as const;

/** Every active maintenance plan, overdue and due first (G13). */
export default function MaintenancePage() {
  const tenant = useTenant();
  const t = useNs('assets');
  const lang = currentLanguage();
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api<Plan[]>(`/api/v1/tenants/${tenant.id}/assets/maintenance-plans`)
      .then(p => setPlans(p.filter(x => x.active).sort((a, b) => ORDER[a.status] - ORDER[b.status]))).catch(e => setError(describeError(e)));
  }, [tenant.id]);

  return (
    <>
      <div className="crop-page-head"><div><h2>{t('maintenance')}</h2><p>{t('maintenanceHelp')}</p></div></div>
      <ErrorBanner message={error} />
      {!plans ? <Loading /> : plans.length === 0 ? <p className="hint">{t('noPlans')}</p> : (
        <table className="data-table">
          <thead><tr><th>{t('name')}</th><th>{t('planName')}</th><th>{t('statusLabel')}</th><th>{t('next')}</th></tr></thead>
          <tbody>{plans.map(p => (
            <tr key={p.id}>
              <td><Link to={`/t/${tenant.id}/assets/${p.assetId}`}>{p.assetName}</Link></td>
              <td>{p.name}</td>
              <td>{p.status === 'OK' ? t('planStatus.OK') : <b className="low-stock-label">{t(`planStatus.${p.status}`)}</b>}</td>
              <td>{[p.nextDate && formatDate(p.nextDate, lang), p.hoursLeft != null && t('hoursLeft', { hours: formatNumber(p.hoursLeft, lang, 1) })].filter(Boolean).join(' · ')}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </>
  );
}
