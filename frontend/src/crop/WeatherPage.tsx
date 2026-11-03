import { useCallback, useEffect, useState } from 'react';
import { Trash2 } from 'lucide-react';
import { api } from '../api/client';
import type { Farm } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Weather } from './types';

const monthStart = () => `${today().slice(0, 7)}-01`;

/** Rainfall and weather notes the farmer records, per farm and day (F10). */
export default function WeatherPage() {
  const tenant = useTenant();
  const t = useNs('crop');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const [farms, setFarms] = useState<Farm[]>([]);
  const [farmId, setFarmId] = useState('');
  const [range, setRange] = useState({ from: monthStart(), to: today() });
  const [weather, setWeather] = useState<Weather | null>(null);
  const [entry, setEntry] = useState({ date: today(), rainfallMm: '', notes: '' });
  const [error, setError] = useState('');
  const canWrite = can(tenant, 'CROP_ACTIVITY_WRITE');

  useEffect(() => {
    api<Farm[]>(`/api/v1/tenants/${tenant.id}/farms`).then(f => { setFarms(f); if (f[0]) setFarmId(String(f[0].id)); }).catch(e => setError(describeError(e)));
  }, [tenant.id]);
  const base = `/api/v1/tenants/${tenant.id}/farms/${farmId}/weather`;
  const load = useCallback(async () => {
    if (!farmId) return;
    try { setWeather(await api<Weather>(`${base}?from=${range.from}&to=${range.to}`)); } catch (e) { setError(describeError(e)); }
  }, [base, farmId, range]);
  useEffect(() => { void load(); }, [load]);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (entry.rainfallMm && !/^\d{1,4}(\.\d)?$/.test(entry.rainfallMm)) { setError(t('errors.rainfall')); return; }
    setError('');
    try {
      await api(`${base}/${entry.date}`, { method: 'PUT', body: { rainfallMm: entry.rainfallMm || null, notes: entry.notes || null } });
      setEntry({ date: today(), rainfallMm: '', notes: '' });
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  return (
    <>
      <div className="crop-page-head"><div><h2>{t('weather')}</h2><p>{t('weatherHelp')}</p></div></div>
      <ErrorBanner message={error} />
      <div className="form-card form-grid filters">
        <label>{t('farm')}<select value={farmId} onChange={e => setFarmId(e.target.value)}>{farms.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}</select></label>
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      {canWrite && farmId && (
        <form className="form-card form-grid" onSubmit={e => void save(e)}>
          <label>{t('date')}<input type="date" max={today()} value={entry.date} onChange={e => setEntry({ ...entry, date: e.target.value })} /></label>
          <label>{t('rainfall')}<input inputMode="decimal" value={entry.rainfallMm} onChange={e => setEntry({ ...entry, rainfallMm: e.target.value })} /></label>
          <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={500} value={entry.notes} onChange={e => setEntry({ ...entry, notes: e.target.value })} /></label>
          <div className="form-actions full"><button className="primary">{tc('save')}</button></div>
        </form>
      )}
      {!farmId ? <p className="hint">{t('selectFarm')}</p> : !weather ? <Loading /> : (
        <>
          <p className="hint">{t('totalRainfall', { mm: formatNumber(weather.totalRainfallMm, lang, 1) })}</p>
          {weather.days.length === 0 ? <p className="hint">{t('noWeather')}</p> : (
            <ul className="plain-list">
              {weather.days.map(d => (
                <li key={d.id}><b>{formatDate(d.date, lang)}</b>{d.rainfallMm != null ? ` · ${t('rainMm', { mm: formatNumber(d.rainfallMm, lang, 1) })}` : ''}{d.notes ? ` · ${d.notes}` : ''}
                  {canWrite && <button type="button" className="text-action" aria-label={tc('remove')}
                    onClick={() => void api(`${base}/${d.date}`, { method: 'DELETE' }).then(load).catch(e => setError(describeError(e)))}><Trash2 size={15} /></button>}</li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  );
}
