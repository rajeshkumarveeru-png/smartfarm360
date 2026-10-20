import React, { useCallback, useEffect, useState } from 'react';
import { Leaf, MapPinned, Plus, Sprout, Tractor } from 'lucide-react';
import { api } from '../api/client';
import type { Farm, Parcel } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { EmptyState, ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatNumber } from '../lib/format';
import { can } from '../lib/nav';

const FARM_TYPES = ['MIXED', 'CROP', 'DAIRY', 'POULTRY', 'GOAT_SHEEP', 'AQUACULTURE', 'ORGANIC', 'OTHER'] as const;
const TYPE_KEYS: Record<string, string> = { MIXED: 'mixed', CROP: 'crop', DAIRY: 'dairy', POULTRY: 'poultry', GOAT_SHEEP: 'goat', AQUACULTURE: 'fish', ORGANIC: 'organic', OTHER: 'other' };
const AREA_UNITS = ['ACRE', 'HECTARE', 'CENT', 'SQFT'] as const;

export default function FarmsPage() {
  const tenant = useTenant();
  const t = useNs('farm');
  const tc = useNs('common');
  const tu = useNs('units');
  const lang = currentLanguage();
  const canWrite = can(tenant, 'FARM_WRITE');
  const base = `/api/v1/tenants/${tenant.id}`;

  const [farms, setFarms] = useState<Farm[]>([]);
  const [farmId, setFarmId] = useState<number | null>(null);
  const [parcels, setParcels] = useState<Parcel[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [showFarm, setShowFarm] = useState(false);
  const [showParcel, setShowParcel] = useState(false);
  const [farmForm, setFarmForm] = useState({ name: '', farmType: 'MIXED', village: '', district: '' });
  const [parcelForm, setParcelForm] = useState({ name: '', area: '', areaUnit: 'ACRE', soilType: '', irrigationType: '' });

  const loadFarms = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const list = await api<Farm[]>(`${base}/farms`);
      setFarms(list);
      setFarmId(old => (list.some(f => f.id === old) ? old : list[0]?.id ?? null));
    } catch (e) {
      setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, [base]);

  useEffect(() => { void loadFarms(); }, [loadFarms]);
  useEffect(() => {
    if (!farmId) { setParcels([]); return; }
    let alive = true;
    api<Parcel[]>(`${base}/farms/${farmId}/land-parcels`).then(list => alive && setParcels(list)).catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [base, farmId]);

  async function submitFarm(e: React.FormEvent) {
    e.preventDefault();
    if (!farmForm.name.trim()) { setError(tc('required')); return; }
    setBusy(true); setError('');
    try {
      const created = await api<Farm>(`${base}/farms`, { method: 'POST', body: {
        name: farmForm.name.trim(), farmType: farmForm.farmType, village: farmForm.village.trim() || null, district: farmForm.district.trim() || null,
      } });
      setFarms(old => [...old, created].sort((a, b) => a.name.localeCompare(b.name)));
      setFarmId(created.id);
      setFarmForm({ name: '', farmType: 'MIXED', village: '', district: '' });
      setShowFarm(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function submitParcel(e: React.FormEvent) {
    e.preventDefault();
    if (!farmId || !parcelForm.name.trim() || !(Number(parcelForm.area) > 0)) { setError(tc('required')); return; }
    setBusy(true); setError('');
    try {
      const created = await api<Parcel>(`${base}/farms/${farmId}/land-parcels`, { method: 'POST', body: {
        name: parcelForm.name.trim(), area: parcelForm.area, areaUnit: parcelForm.areaUnit,
        soilType: parcelForm.soilType.trim() || null, irrigationType: parcelForm.irrigationType.trim() || null,
      } });
      setParcels(old => [...old, created].sort((a, b) => a.name.localeCompare(b.name)));
      setParcelForm({ name: '', area: '', areaUnit: 'ACRE', soilType: '', irrigationType: '' });
      setShowParcel(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  const selectedFarm = farms.find(f => f.id === farmId);
  if (loading) return <Loading />;
  return (
    <>
      <ErrorBanner message={error} onRetry={() => void loadFarms()} />
      <section className="welcome">
        <div><h2>{t('setupTitle')}</h2><p>{t('setupIntro')}</p></div>
        <div className="welcome-icon" aria-hidden><Sprout size={56} /><Leaf size={26} /></div>
      </section>

      <section className="section-head">
        <h2>{t('farms')}</h2>
        {canWrite && <button className="primary" onClick={() => setShowFarm(x => !x)}><Plus size={17} aria-hidden />{t('createFarm')}</button>}
      </section>
      {showFarm && (
        <form className="form-card form-grid" onSubmit={submitFarm} noValidate>
          <label>{t('farmName')}<input maxLength={140} value={farmForm.name} onChange={e => setFarmForm({ ...farmForm, name: e.target.value })} /></label>
          <label>{t('type')}
            <select value={farmForm.farmType} onChange={e => setFarmForm({ ...farmForm, farmType: e.target.value })}>
              {FARM_TYPES.map(v => <option key={v} value={v}>{t(TYPE_KEYS[v])}</option>)}
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
        {farms.map(f => (
          <button key={f.id} className={`farm-card ${farmId === f.id ? 'chosen' : ''}`} onClick={() => setFarmId(f.id)} aria-pressed={farmId === f.id}>
            <div className="farm-icon"><Tractor size={22} aria-hidden /></div>
            <div className="farm-info">
              <strong>{f.name}</strong>
              <span>{t(TYPE_KEYS[f.farmType] ?? 'other')}</span>
              <small>{[f.village, f.district, f.state].filter(Boolean).join(', ')}</small>
            </div>
          </button>
        ))}
        {!farms.length && <EmptyState>{t('emptyFarms')}</EmptyState>}
      </div>

      {selectedFarm && (
        <>
          <section className="section-head section-space">
            <h2>{t('land')} <span className="count">{parcels.length}</span></h2>
            {canWrite && <button className="primary" onClick={() => setShowParcel(x => !x)}><Plus size={17} aria-hidden />{t('addLand')}</button>}
          </section>
          {showParcel && (
            <form className="form-card form-grid" onSubmit={submitParcel} noValidate>
              <label>{t('parcelName')}<input maxLength={120} value={parcelForm.name} onChange={e => setParcelForm({ ...parcelForm, name: e.target.value })} /></label>
              <div className="inline-fields">
                <label>{t('area')}<input type="number" min="0.001" step="0.001" inputMode="decimal" value={parcelForm.area} onChange={e => setParcelForm({ ...parcelForm, area: e.target.value })} /></label>
                <label>{t('unit')}
                  <select value={parcelForm.areaUnit} onChange={e => setParcelForm({ ...parcelForm, areaUnit: e.target.value })}>
                    {AREA_UNITS.map(u => <option key={u} value={u}>{tu(u)}</option>)}
                  </select>
                </label>
              </div>
              <label>{t('soil')}<input maxLength={80} value={parcelForm.soilType} onChange={e => setParcelForm({ ...parcelForm, soilType: e.target.value })} /></label>
              <label>{t('irrigation')}<input maxLength={80} value={parcelForm.irrigationType} onChange={e => setParcelForm({ ...parcelForm, irrigationType: e.target.value })} /></label>
              <div className="form-actions full">
                <button type="button" className="secondary" onClick={() => setShowParcel(false)}>{tc('cancel')}</button>
                <button className="primary" disabled={busy}>{t('save')}</button>
              </div>
            </form>
          )}
          <div className="parcel-list">
            {parcels.map(p => (
              <div className="parcel-row" key={p.id}>
                <div className="parcel-icon"><Leaf size={20} aria-hidden /></div>
                <div className="parcel-info"><strong>{p.name}</strong><span>{[p.soilType, p.irrigationType].filter(Boolean).join(' · ')}</span></div>
                <div className="parcel-area"><strong>{formatNumber(p.area, lang)}</strong><span>{tu(p.areaUnit)}</span></div>
              </div>
            ))}
            {!parcels.length && <EmptyState><MapPinned size={18} aria-hidden /> {t('emptyLand')}</EmptyState>}
          </div>
        </>
      )}
    </>
  );
}
