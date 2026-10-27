import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import BillingPanel from './BillingPanel';
import TenantOperations from './TenantOperations';
import { api } from '../api/client';
import type { ModuleStatus, Settings, TenantDetail } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { can } from '../lib/nav';

const TIMEZONES = ['Asia/Kolkata', 'Asia/Colombo', 'Asia/Dubai', 'Asia/Singapore', 'UTC'];

export default function SettingsPage() {
  const tenant = useTenant();
  const { reloadTenants, logout } = useAuth();
  const navigate = useNavigate();
  const t = useNs('settings');
  const tc = useNs('common');
  const tu = useNs('units');
  const tm = useNs('modules');
  const tt = useNs('tenant');
  const canManage = can(tenant, 'TENANT_MANAGE');
  const base = `/api/v1/tenants/${tenant.id}`;

  const [detail, setDetail] = useState<TenantDetail | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [name, setName] = useState('');
  const [language, setLanguage] = useState<'en' | 'ta'>('en');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [passwords, setPasswords] = useState({ currentPassword: '', newPassword: '' });

  const load = useCallback(async () => {
    setError('');
    try {
      const d = await api<TenantDetail>(base);
      setDetail(d);
      setSettings(d.settings);
      setName(d.name);
      setLanguage(d.preferredLanguage);
    } catch (e) {
      setError(describeError(e));
    }
  }, [base]);
  useEffect(() => { void load(); }, [load]);

  async function save(action: () => Promise<unknown>) {
    setError(''); setNotice('');
    try { await action(); setNotice(tc('saved')); } catch (e) { setError(describeError(e)); }
  }

  async function saveBusiness(e: React.FormEvent) {
    e.preventDefault();
    await save(async () => {
      await api(base, { method: 'PATCH', body: { name: name.trim(), preferredLanguage: language } });
      await reloadTenants();
    });
  }

  async function saveSettings(e: React.FormEvent) {
    e.preventDefault();
    if (!settings) return;
    await save(async () => setSettings(await api<Settings>(`${base}/settings`, { method: 'PUT', body: settings })));
  }

  async function toggleModule(m: ModuleStatus) {
    await save(async () => {
      const modules = await api<ModuleStatus[]>(`${base}/modules/${m.code}`, { method: 'PUT', body: { enabled: !m.enabled } });
      setDetail(d => (d ? { ...d, modules } : d));
      await reloadTenants();
    });
  }

  async function changePassword(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    try {
      await api('/api/v1/auth/me/password', { method: 'POST', body: passwords });
      await logout();
      navigate('/login');
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (!detail || !settings) return error ? <ErrorBanner message={error} onRetry={() => void load()} /> : <Loading />;
  return (
    <section className="crop-page">
      <div className="crop-page-head"><div><h2>{t('title')}</h2>{!canManage && <p>{t('readOnly')}</p>}</div></div>
      <ErrorBanner message={error} />
      {notice && <div className="info-banner" role="status">{notice}</div>}

      <h3>{t('business')}</h3>
      <form className="form-card form-grid" onSubmit={saveBusiness}>
        <label>{tt('name')}<input value={name} maxLength={140} disabled={!canManage} onChange={e => setName(e.target.value)} /></label>
        <label>{t('businessLanguage')}
          <select value={language} disabled={!canManage} onChange={e => setLanguage(e.target.value as 'en' | 'ta')}>
            <option value="en">{tc('english')}</option><option value="ta">{tc('tamil')}</option>
          </select>
        </label>
        {canManage && <div className="form-actions full"><button className="primary">{tc('save')}</button></div>}
      </form>

      <h3>{t('units')}</h3>
      <form className="form-card form-grid" onSubmit={saveSettings}>
        <label>{t('timezone')}
          <select value={settings.timezone} disabled={!canManage} onChange={e => setSettings({ ...settings, timezone: e.target.value })}>
            {Array.from(new Set([settings.timezone, ...TIMEZONES])).map(z => <option key={z} value={z}>{z}</option>)}
          </select>
        </label>
        <label>{t('currency')}<select value={settings.currencyCode} disabled><option value="INR">{tu('INR')}</option></select></label>
        <label>{t('areaUnit')}
          <select value={settings.areaUnit} disabled={!canManage} onChange={e => setSettings({ ...settings, areaUnit: e.target.value })}>
            {['ACRE', 'HECTARE', 'CENT', 'SQFT'].map(u => <option key={u} value={u}>{tu(u)}</option>)}
          </select>
        </label>
        <label>{t('weightUnit')}
          <select value={settings.weightUnit} disabled={!canManage} onChange={e => setSettings({ ...settings, weightUnit: e.target.value })}>
            {['KG', 'QUINTAL', 'TONNE'].map(u => <option key={u} value={u}>{tu(u)}</option>)}
          </select>
        </label>
        {canManage && <div className="form-actions full"><button className="primary">{tc('save')}</button></div>}
      </form>

      <h3>{t('modules')}</h3>
      <p className="hint">{t('modulesIntro')}</p>
      <div className="activity-list">
        {detail.modules.map(m => (
          <article className="activity-row" key={m.code}>
            <div className="cycle-main"><h4>{tm(m.code)}</h4>{!m.available && <p>{t('notAvailable')}</p>}</div>
            <button type="button" role="switch" aria-checked={m.enabled} className={m.enabled ? 'primary' : 'secondary'}
                    disabled={!canManage || (!m.available && !m.enabled)} onClick={() => void toggleModule(m)}>
              {m.enabled ? t('on') : t('off')}
            </button>
          </article>
        ))}
      </div>

      <BillingPanel />
      <TenantOperations />

      <h3>{t('profile')}</h3>
      <p><Link className="text-link" to="/account">{t('accountLink')}</Link></p>
      <form className="form-card form-grid" onSubmit={changePassword}>
        <label>{t('currentPassword')}<input type="password" autoComplete="current-password" value={passwords.currentPassword}
                                            onChange={e => setPasswords({ ...passwords, currentPassword: e.target.value })} /></label>
        <label>{t('changePassword')}<input type="password" autoComplete="new-password" value={passwords.newPassword}
                                           onChange={e => setPasswords({ ...passwords, newPassword: e.target.value })} /></label>
        <div className="form-actions full"><button className="primary">{t('changePassword')}</button></div>
      </form>
    </section>
  );
}
