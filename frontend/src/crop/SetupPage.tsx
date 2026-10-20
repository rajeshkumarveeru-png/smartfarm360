import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Item } from '../stock/types';
import type { Crop, Season, Variety } from './types';

/** Crops (with their varieties and the stock item their harvest goes into) and seasons. */
export default function SetupPage() {
  const tenant = useTenant();
  const t = useNs('crop');
  const tc = useNs('common');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}`;
  const canWrite = can(tenant, 'CROP_WRITE');
  const [crops, setCrops] = useState<Crop[] | null>(null);
  const [seasons, setSeasons] = useState<Season[]>([]);
  const [produce, setProduce] = useState<Item[]>([]);
  const [varieties, setVarieties] = useState<Record<number, Variety[]>>({});
  const [crop, setCrop] = useState({ name: '', localName: '', category: '' });
  const [variety, setVariety] = useState({ cropId: '', name: '', durationDays: '' });
  const [season, setSeason] = useState({ name: '', startDate: today(), endDate: today(), seasonType: '' });
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const [c, s] = await Promise.all([api<Crop[]>(`${base}/crops`), api<Season[]>(`${base}/seasons`)]);
      setCrops(c); setSeasons(s);
      const v = await Promise.all(c.map(x => api<Variety[]>(`${base}/crops/${x.id}/varieties`)));
      setVarieties(Object.fromEntries(c.map((x, i) => [x.id, v[i]])));
      if (can(tenant, 'INVENTORY_READ')) setProduce((await api<Item[]>(`${base}/stock/items`)).filter(i => i.kind === 'PRODUCE'));
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, tenant]);
  useEffect(() => { void load(); }, [load]);

  async function run(action: () => Promise<unknown>) {
    setError('');
    try { await action(); await load(); } catch (e) { setError(describeError(e)); }
  }

  if (!crops) return error ? <ErrorBanner message={error} /> : <Loading />;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('setup')}</h2><p>{t('setupHelp')}</p></div></div>
      <ErrorBanner message={error} />
      <h3>{t('crops')}</h3>
      {canWrite && (
        <form className="form-card form-grid" onSubmit={e => { e.preventDefault(); if (!crop.name.trim()) { setError(tc('required')); return; }
          void run(async () => { await api(`${base}/crops`, { method: 'POST', body: { name: crop.name.trim(), localName: crop.localName || null, category: crop.category || null } });
            setCrop({ name: '', localName: '', category: '' }); }); }}>
          <label>{t('cropName')}<input required maxLength={120} value={crop.name} onChange={e => setCrop({ ...crop, name: e.target.value })} /></label>
          <label>{t('localName')}<input maxLength={120} value={crop.localName} onChange={e => setCrop({ ...crop, localName: e.target.value })} /></label>
          <label>{t('category')}<input maxLength={60} value={crop.category} placeholder={t('categoryPlaceholder')} onChange={e => setCrop({ ...crop, category: e.target.value })} /></label>
          <div className="form-actions full"><button className="primary">{t('addCrop')}</button></div>
        </form>
      )}
      {crops.length === 0 ? <p className="hint">{t('noCrops')}</p> : (
        <div className="activity-list">
          {crops.map(c => (
            <article className="activity-row" key={c.id}>
              <div className="cycle-main"><b>{c.name}</b>{c.localName && <small> · {c.localName}</small>}{c.category && <small> · {c.category}</small>}
                <div><small>{t('varieties')}: {(varieties[c.id] ?? []).map(v => v.name + (v.durationDays ? ` (${t('days', { days: String(v.durationDays) })})` : '')).join(', ') || '—'}</small></div>
                {produce.length > 0 && <div><label className="inline-fields"><small>{t('harvestGoesTo')}</small>
                  <select disabled={!canWrite} value={c.produceItemId ?? ''} onChange={e => void run(() => api(`${base}/crops/${c.id}/produce-item`, { method: 'PUT', body: { itemId: Number(e.target.value) } }))}>
                    <option value="">{t('createOnFirstHarvest')}</option>{produce.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}</select></label></div>}
              </div>
              <div className="value-side"><Link className="text-link" to={`/t/${tenant.id}/crops/compare/${c.id}`}>{t('compareSeasons')}</Link></div>
            </article>
          ))}
        </div>
      )}
      {canWrite && crops.length > 0 && (
        <form className="form-card form-grid" onSubmit={e => { e.preventDefault(); if (!variety.cropId || !variety.name.trim()) { setError(tc('required')); return; }
          void run(async () => { await api(`${base}/crops/${variety.cropId}/varieties`, { method: 'POST', body: { name: variety.name.trim(),
            durationDays: variety.durationDays ? Number(variety.durationDays) : null } }); setVariety({ cropId: variety.cropId, name: '', durationDays: '' }); }); }}>
          <label>{t('crop')}<select value={variety.cropId} onChange={e => setVariety({ ...variety, cropId: e.target.value })}>
            <option value="">—</option>{crops.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
          <label>{t('varietyName')}<input maxLength={120} value={variety.name} onChange={e => setVariety({ ...variety, name: e.target.value })} /></label>
          <label>{t('duration')}<input inputMode="numeric" value={variety.durationDays} onChange={e => setVariety({ ...variety, durationDays: e.target.value })} /></label>
          <div className="form-actions full"><button className="secondary">{t('addVariety')}</button></div>
        </form>
      )}

      <h3 className="section-space">{t('seasons')}</h3>
      {canWrite && (
        <form className="form-card form-grid" onSubmit={e => { e.preventDefault(); if (!season.name.trim()) { setError(tc('required')); return; }
          void run(async () => { await api(`${base}/seasons`, { method: 'POST', body: { ...season, name: season.name.trim(), seasonType: season.seasonType || null } });
            setSeason({ ...season, name: '', seasonType: '' }); }); }}>
          <label>{t('seasonName')}<input maxLength={120} value={season.name} onChange={e => setSeason({ ...season, name: e.target.value })} /></label>
          <label>{t('seasonType')}<input maxLength={40} value={season.seasonType} placeholder={t('seasonTypePlaceholder')} onChange={e => setSeason({ ...season, seasonType: e.target.value })} /></label>
          <label>{t('start')}<input type="date" value={season.startDate} onChange={e => setSeason({ ...season, startDate: e.target.value })} /></label>
          <label>{t('end')}<input type="date" min={season.startDate} value={season.endDate} onChange={e => setSeason({ ...season, endDate: e.target.value })} /></label>
          <div className="form-actions full"><button className="secondary">{t('addSeason')}</button></div>
        </form>
      )}
      {seasons.length === 0 ? <p className="hint">{t('noSeasons')}</p> : (
        <ul className="plain-list">{seasons.map(s => <li key={s.id}><b>{s.name}</b>{s.seasonType ? ` · ${s.seasonType}` : ''} · {formatDate(s.startDate, lang)} – {formatDate(s.endDate, lang)}</li>)}</ul>
      )}
    </>
  );
}
