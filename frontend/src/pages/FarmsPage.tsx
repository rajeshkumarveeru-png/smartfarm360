import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Leaf, MapPin, Plus, Sprout, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import { useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { can } from '../lib/nav';

const FARM_TYPES = ['MIXED', 'CROP', 'DAIRY', 'POULTRY', 'GOAT_SHEEP', 'AQUACULTURE', 'ORGANIC', 'OTHER'] as const;
/** Catalog key (farm.*) for each farming type. */
export const FARM_TYPE_KEYS: Record<string, string> = { MIXED: 'mixed', CROP: 'crop', DAIRY: 'dairy', POULTRY: 'poultry', GOAT_SHEEP: 'goat', AQUACULTURE: 'fish', ORGANIC: 'organic', OTHER: 'other' };

/** The tenant's farms. Each card opens the farm's workspace, where its money, records and land are managed. */
export default function FarmsPage() {
  const tenant = useTenant();
  const t = useNs('farm');
  const tc = useNs('common');
  const canWrite = can(tenant, 'FARM_WRITE');
  const base = `/api/v1/tenants/${tenant.id}`;

  const [farms, setFarms] = useState<Farm[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [showFarm, setShowFarm] = useState(false);
  const [farmForm, setFarmForm] = useState({ name: '', farmType: 'MIXED', village: '', district: '' });

  const loadFarms = useCallback(async () => {
    setLoading(true); setError('');
    try {
      setFarms(await api<Farm[]>(`${base}/farms`));
    } catch (e) {
      setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, [base]);

  useEffect(() => { void loadFarms(); }, [loadFarms]);

  async function submitFarm(e: React.FormEvent) {
    e.preventDefault();
    if (!farmForm.name.trim()) { setError(tc('required')); return; }
    setBusy(true); setError('');
    try {
      const created = await api<Farm>(`${base}/farms`, { method: 'POST', body: {
        name: farmForm.name.trim(), farmType: farmForm.farmType, village: farmForm.village.trim() || null, district: farmForm.district.trim() || null,
      } });
      setFarms(old => [...old, created].sort((a, b) => a.name.localeCompare(b.name)));
      setFarmForm({ name: '', farmType: 'MIXED', village: '', district: '' });
      setShowFarm(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function deleteFarm(f: Farm) {
    if (!window.confirm(t('confirmDeleteFarm', { farm: f.name }))) return;
    setError('');
    try {
      await api(`${base}/farms/${f.id}`, { method: 'DELETE' });
      setFarms(old => old.filter(x => x.id !== f.id));
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (loading) return <Loading />;
  return (
    <>
      <ErrorBanner message={error} onRetry={() => void loadFarms()} />
      <section className="welcome">
        <div><h2>{t('setupTitle')}</h2><p>{t('openHint')}</p></div>
        <div className="welcome-icon" aria-hidden><Sprout size={44} /><Leaf size={22} /></div>
      </section>

      <section className="section-head">
        <h2>{t('farms')} {farms.length > 0 && <span className="count">{farms.length}</span>}</h2>
        {canWrite && <button className="primary" onClick={() => setShowFarm(x => !x)}><Plus size={17} aria-hidden />{t('createFarm')}</button>}
      </section>
      {showFarm && (
        <form className="form-card form-grid" onSubmit={submitFarm} noValidate>
          <label>{t('farmName')}<input maxLength={140} value={farmForm.name} onChange={e => setFarmForm({ ...farmForm, name: e.target.value })} /></label>
          <label>{t('type')}
            <select value={farmForm.farmType} onChange={e => setFarmForm({ ...farmForm, farmType: e.target.value })}>
              {FARM_TYPES.map(v => <option key={v} value={v}>{t(FARM_TYPE_KEYS[v])}</option>)}
            </select>
          </label>
          <label>{t('village')}<input maxLength={120} value={farmForm.village} onChange={e => setFarmForm({ ...farmForm, village: e.target.value })} /></label>
          <label>{t('district')}<input maxLength={120} value={farmForm.district} onChange={e => setFarmForm({ ...farmForm, district: e.target.value })} /></label>
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setShowFarm(false)}>{tc('cancel')}</button>
            <button className="primary" disabled={busy}>{t('save')}</button>
          </div>
        </form>
      )}
      <div className="farm-grid">
        {farms.map(f => {
          const place = [f.village, f.district, f.state].filter(Boolean).join(', ');
          return (
            <div key={f.id} className="farm-tile-wrap">
            <Link className="farm-card farm-tile" to={`/t/${tenant.id}/farms/${f.id}`}>
              <div className="farm-icon"><Leaf size={22} aria-hidden /></div>
              <div className="farm-info">
                <strong>{f.name}</strong>
                <span className="pill">{t(FARM_TYPE_KEYS[f.farmType] ?? 'other')}</span>
                {place && <small><MapPin size={13} aria-hidden />{place}</small>}
              </div>
              <span className="farm-open">{t('open')}<ArrowRight size={16} aria-hidden /></span>
            </Link>
            {canWrite && <button type="button" className="rp-btn danger-btn farm-delete" title={t('deleteFarm')} aria-label={`${t('deleteFarm')} ${f.name}`}
              onClick={() => void deleteFarm(f)}><Trash2 size={15} aria-hidden /></button>}
            </div>
          );
        })}
        {!farms.length && <EmptyState>{t('emptyFarms')}</EmptyState>}
      </div>
    </>
  );
}
