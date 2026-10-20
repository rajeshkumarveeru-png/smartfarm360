import React, { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { useNs } from '../i18n';
import { describeError } from '../lib/errors';
import type { FinanceSettings } from './types';
import { useCategoryLabel, useFinanceData } from './useFinanceData';

const ACCOUNTS_BY_KIND: Record<string, string[]> = {
  EXPENSE: ['5000', '5100', '5200', '5300', '5400', '5500', '5600', '5950'],
  INCOME: ['4000', '4100', '4200'],
  ASSET: ['1300'],
};

/** Lock date, fiscal year and categories (FINANCE_MANAGE). */
export default function FinanceSettingsPage() {
  const tenant = useTenant();
  const t = useNs('fin');
  const tc = useNs('common');
  const label = useCategoryLabel();
  const data = useFinanceData();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const [settings, setSettings] = useState<FinanceSettings | null>(null);
  const [category, setCategory] = useState({ name: '', kind: 'EXPENSE', accountCode: '5600' });
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => { api<FinanceSettings>(`${base}/settings`).then(setSettings).catch(e => setError(describeError(e))); }, [base]);

  async function run(action: () => Promise<unknown>) {
    setError(''); setNotice('');
    try { await action(); setNotice(tc('saved')); } catch (e) { setError(describeError(e)); }
  }

  async function saveSettings(e: React.FormEvent) {
    e.preventDefault();
    if (!settings) return;
    if (settings.lockDate && !window.confirm(t('confirmLock', { date: settings.lockDate }))) return;
    await run(async () => setSettings(await api<FinanceSettings>(`${base}/settings`, { method: 'PUT', body: settings })));
  }

  async function addCategory(e: React.FormEvent) {
    e.preventDefault();
    if (!category.name.trim()) { setError(tc('required')); return; }
    await run(async () => {
      await api(`${base}/categories`, { method: 'POST', body: { ...category, name: category.name.trim() } });
      setCategory({ ...category, name: '' });
      await data.reload();
    });
  }

  if (!settings || data.loading) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('settings')}</h2></div></div>
      <ErrorBanner message={error} />
      {notice && <div className="info-banner" role="status">{notice}</div>}
      <form className="form-card form-grid" onSubmit={saveSettings}>
        <label>{t('lockDate')} <small>{tc('optional')}</small>
          <input type="date" value={settings.lockDate ?? ''} onChange={e => setSettings({ ...settings, lockDate: e.target.value || null })} /></label>
        <label>{t('fiscalYearStart')}
          <select value={settings.fiscalYearStartMonth} onChange={e => setSettings({ ...settings, fiscalYearStartMonth: Number(e.target.value) })}>
            {Array.from({ length: 12 }, (_, i) => i + 1).map(m => <option key={m} value={m}>{t(`month.${m}`)}</option>)}</select></label>
        <p className="hint full">{t('lockHelp')}</p>
        <div className="form-actions full"><button className="primary">{tc('save')}</button></div>
      </form>

      <h3>{t('categories')}</h3>
      <form className="form-card form-grid" onSubmit={addCategory} noValidate>
        <label>{t('name')}<input maxLength={80} value={category.name} onChange={e => setCategory({ ...category, name: e.target.value })} /></label>
        <label>{t('categoryKind')}<select value={category.kind} onChange={e => setCategory({ ...category, kind: e.target.value, accountCode: ACCOUNTS_BY_KIND[e.target.value][0] })}>
          {Object.keys(ACCOUNTS_BY_KIND).map(k => <option key={k} value={k}>{t(`categoryKinds.${k}`)}</option>)}</select></label>
        <label>{t('reportsUnder')}<select value={category.accountCode} onChange={e => setCategory({ ...category, accountCode: e.target.value })}>
          {ACCOUNTS_BY_KIND[category.kind].map(c => <option key={c} value={c}>{t(`ledger.${c}`)}</option>)}</select></label>
        <div className="form-actions full"><button className="primary">{t('addCategory')}</button></div>
      </form>
      <div className="activity-list">
        {data.categories.map(c => (
          <article className="activity-row" key={c.id}>
            <div className="cycle-main"><h4>{label(c)}</h4><p>{t(`categoryKinds.${c.kind}`)} · {t(`ledger.${c.accountCode}`)}{c.system ? ` · ${t('system')}` : ''}</p></div>
            {!c.system && <button className="text-action" onClick={() => void run(async () => {
              await api(`${base}/categories/${c.id}`, { method: 'PATCH', body: { active: !c.active } }); await data.reload();
            })}>{c.active ? t('deactivate') : t('activate')}</button>}
          </article>
        ))}
      </div>
    </>
  );
}
