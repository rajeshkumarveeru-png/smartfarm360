import { Link } from 'react-router-dom';
import type { ModuleCode, Permission } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';
import { can } from '../lib/nav';

type Entry = { key: string; path: string; permission: Permission; module?: ModuleCode };
const GROUPS: { key: string; entries: Entry[] }[] = [
  { key: 'money', entries: [
    { key: 'income', path: 'finance/reports', permission: 'FINANCE_READ' },
    { key: 'monthly', path: 'reports/monthly', permission: 'FINANCE_READ' },
    { key: 'categories', path: 'reports/categories', permission: 'FINANCE_READ' },
    { key: 'balanceSheet', path: 'reports/balance-sheet', permission: 'FINANCE_READ' },
    { key: 'cashFlow', path: 'finance/reports?tab=cashflow', permission: 'FINANCE_READ' },
    { key: 'dues', path: 'finance/reports?tab=dues', permission: 'FINANCE_READ' },
    { key: 'activity', path: 'finance/reports?tab=activity', permission: 'FINANCE_READ' },
    { key: 'partyStatement', path: 'finance/parties', permission: 'FINANCE_READ' },
    { key: 'trialBalance', path: 'reports/trial-balance', permission: 'FINANCE_MANAGE' },
  ] },
  { key: 'crops', entries: [
    { key: 'cropCycles', path: 'crops', permission: 'CROP_READ', module: 'CROP' },
  ] },
  { key: 'livestock', entries: [
    { key: 'herdProfit', path: 'livestock/reports', permission: 'FINANCE_READ', module: 'DAIRY' },
    { key: 'milkReport', path: 'livestock/reports', permission: 'LIVESTOCK_READ', module: 'DAIRY' },
    { key: 'breedingCalendar', path: 'livestock/care', permission: 'LIVESTOCK_READ', module: 'DAIRY' },
    { key: 'herdRegister', path: 'livestock', permission: 'LIVESTOCK_READ', module: 'DAIRY' },
  ] },
  { key: 'aquaculture', entries: [
    { key: 'pondCycles', path: 'aquaculture/reports', permission: 'AQUACULTURE_READ', module: 'AQUACULTURE' },
    { key: 'waterLog', path: 'aquaculture/water', permission: 'AQUACULTURE_READ', module: 'AQUACULTURE' },
  ] },
  { key: 'organic', entries: [
    { key: 'organicBatches', path: 'organic/reports', permission: 'ORGANIC_READ', module: 'ORGANIC' },
  ] },
  { key: 'stock', entries: [
    { key: 'stockValue', path: 'stock/reports', permission: 'INVENTORY_READ' },
    { key: 'internalTransfers', path: 'stock/own-produce', permission: 'INVENTORY_READ' },
  ] },
  { key: 'workers', entries: [
    { key: 'wages', path: 'reports/wages', permission: 'WORKFORCE_READ', module: 'WORKFORCE' },
    { key: 'labour', path: 'workforce/reports', permission: 'WORKFORCE_READ', module: 'WORKFORCE' },
  ] },
  { key: 'assets', entries: [
    { key: 'runningCosts', path: 'reports/running-costs', permission: 'ASSETS_READ', module: 'ASSETS' },
    { key: 'assetRegister', path: 'assets', permission: 'ASSETS_READ', module: 'ASSETS' },
    { key: 'depreciation', path: 'assets/depreciation', permission: 'ASSETS_READ', module: 'ASSETS' },
  ] },
];

/** Every report the reader may open, grouped (dashboard and reports design, section 4). */
export default function ReportsCatalogPage() {
  const tenant = useTenant();
  const t = useNs('reports');
  const visible = GROUPS.map(g => ({ ...g, entries: g.entries.filter(e => can(tenant, e.permission) && (!e.module || tenant.enabledModules.includes(e.module))) }))
    .filter(g => g.entries.length > 0);
  return (
    <section className="crop-page">
      <div className="crop-page-head"><div><h2>{t('catalogTitle')}</h2><p>{t('catalogHelp')}</p></div></div>
      {visible.length === 0 && <p className="hint">{t('noReports')}</p>}
      <div className="report-catalog">
        {visible.map(g => (
          <div key={g.key} className="form-card">
            <h3>{t(`group.${g.key}`)}</h3>
            <ul className="plain-list">
              {g.entries.map(e => <li key={e.key}><Link to={`/t/${tenant.id}/${e.path}`}>{t(`catalog.${e.key}`)}</Link><small> · {t(`catalogHint.${e.key}`)}</small></li>)}
            </ul>
          </div>
        ))}
      </div>
    </section>
  );
}
