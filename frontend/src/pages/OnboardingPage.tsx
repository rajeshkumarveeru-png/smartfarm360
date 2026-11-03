import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { TenantSummary } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AuthCard, ErrorBanner } from '../components/common';
import { currentLanguage } from '../i18n';
import { describeError } from '../lib/errors';

export default function OnboardingPage() {
  const { t } = useTranslation();
  const { reloadTenants, tenants } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) { setError(t('common.required')); return; }
    setBusy(true); setError('');
    try {
      const created = await api<TenantSummary>('/api/v1/tenants', {
        method: 'POST', body: { name: name.trim(), preferredLanguage: currentLanguage(), modules: ['CROP'] },
      });
      await reloadTenants();
      navigate(`/t/${created.id}/farms`, { replace: true });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard title={t('tenant.title')} subtitle={t('tenant.intro')}>
      <form onSubmit={submit} noValidate>
        <label>{t('tenant.name')}<input maxLength={140} value={name} onChange={e => setName(e.target.value)} placeholder={t('tenant.namePlaceholder')} /></label>
        <ErrorBanner message={error} />
        <button className="primary" disabled={busy}>{t('tenant.create')}</button>
        {tenants.length > 0 && <button type="button" className="secondary" onClick={() => navigate(-1)}>{t('common.cancel')}</button>}
      </form>
    </AuthCard>
  );
}
