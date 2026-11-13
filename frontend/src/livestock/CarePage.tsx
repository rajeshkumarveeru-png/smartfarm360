import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import type { FinancialAccount } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar } from '../reports/ReportTools';
import { useGroups } from './LivestockLayout';
import type { BreedingCalendar, DueItem, HealthReport } from './types';
import { HEALTH_KINDS } from './types';

const plusDays = (d: string, n: number) => { const x = new Date(`${d}T00:00:00`); x.setDate(x.getDate() + n);
  return `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`; };

/** Breeding calendar (I10), what is due and withheld (I9), and health events for a whole group. */
export default function CarePage() {
  const tenant = useTenant();
  const t = useNs('livestock');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/livestock`;
  const canWrite = can(tenant, 'LIVESTOCK_WRITE');
  const { groups } = useGroups();
  const [calendar, setCalendar] = useState<BreedingCalendar | null>(null);
  const [health, setHealth] = useState<HealthReport | null>(null);
  const [form, setForm] = useState({ groupId: '', kind: 'VACCINATION', date: today(), description: '', nextDue: '', billAmount: '', billAccount: '' });
  const [repeatDays, setRepeatDays] = useState('');
  const [customRepeatDays, setCustomRepeatDays] = useState('');
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  const from = plusDays(today(), -90), to = plusDays(today(), 90);
  const setRepeat = (value: string) => {
    setRepeatDays(value); setCustomRepeatDays('');
    if (!value) { setForm(f => ({ ...f, nextDue: '' })); return; }
    setForm(f => ({ ...f, nextDue: value !== 'CUSTOM' ? plusDays(f.date, Number(value)) : '' }));
  };
  const load = useCallback(() => Promise.all([api<BreedingCalendar>(`${base}/reports/breeding?from=${today()}&to=${to}`),
    api<HealthReport>(`${base}/reports/health?from=${from}&to=${today()}`)]).then(([c, h]) => { setCalendar(c); setHealth(h); })
    .catch(e => setError(describeError(e))), [base, from, to]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (can(tenant, 'FINANCE_WRITE')) api<FinancialAccount[]>(`/api/v1/tenants/${tenant.id}/finance/accounts`).then(a => setAccounts(a.filter(x => x.active))).catch(() => undefined);
  }, [tenant]);

  const dueLine = (d: DueItem) => <li key={`${d.kind}-${d.animalId}-${d.due}`}>
    {d.animalId ? <Link to={`/t/${tenant.id}/livestock/animals/${d.animalId}`}>{d.tag}{d.name ? ` ${d.name}` : ''}</Link> : d.tag}
    {' · '}{t(`due.${d.kind}`)} · {formatDate(d.due, lang)}</li>;
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('care')}</h2><p>{t('careHelp')}</p></div></div>
      <ErrorBanner message={error} />
      <Toast message={message} onClose={() => setMessage('')} />
      {!calendar || !health ? <Loading /> : (
        <div className="dashboard-grid">
          <div className="form-card">
            <h3>{t('report.breeding')}</h3>
            <ExportBar path={`${base}/reports/breeding?from=${today()}&to=${to}`} name={`breeding-calendar-${today()}`} onError={setError} />
            {calendar.due.length === 0 ? <p className="hint">{t('nothingDue')}</p> : <ul className="plain-list">{calendar.due.map(dueLine)}</ul>}
            {calendar.openLong.length > 0 && <><h4>{t('due.OPEN_LONG')}</h4><ul className="plain-list">{calendar.openLong.map(dueLine)}</ul></>}
            {calendar.intervals.length > 0 && <><h4>{t('calvingIntervals')}</h4><ul className="plain-list">{calendar.intervals.slice(0, 10).map(i =>
              <li key={`${i.animalId}-${i.latest}`}>{i.tag} · {t('intervalDays', { days: String(i.days) })}</li>)}</ul></>}
          </div>
          <div className="form-card">
            <h3>{t('healthDue')}</h3>
            {health.withheld.length > 0 && <p className="error-banner" role="alert">{t('withheldWarning', { animals: health.withheld.map(w => `${w.tag} (${formatDate(w.until, lang)})`).join(', ') })}</p>}
            {health.nextDue.length === 0 ? <p className="hint">{t('nothingDue')}</p> : <ul className="plain-list">{health.nextDue.map(dueLine)}</ul>}
            <h4>{t('report.health')}</h4>
            <ExportBar path={`${base}/reports/health?from=${from}&to=${today()}`} name={`health-record-${today()}`} onError={setError} />
            {health.events.length === 0 ? <p className="hint">{t('noEvents')}</p> : (
              <ul className="plain-list">{health.events.slice(0, 20).map(e => <li key={e.id}>{formatDate(e.date, lang)} · {t(`event.${e.kind}`)} · {e.description}
                <small> · {e.animalTag ?? e.groupName}</small></li>)}</ul>
            )}
          </div>
        </div>
      )}
      {canWrite && groups.length > 0 && (
        <>
          <h3 className="section-space">{t('groupHealth')}</h3>
          <form className="form-card form-grid" onSubmit={e => {
            e.preventDefault();
            if (!form.groupId || !form.description.trim()) { setError(t('errors.groupHealth')); return; }
            setError('');
            api(`${base}/health`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: { groupId: Number(form.groupId), kind: form.kind, date: form.date,
              description: form.description.trim(), nextDue: form.nextDue || null,
              bill: form.billAmount ? { amount: form.billAmount, financialAccountId: form.billAccount ? Number(form.billAccount) : null } : null } })
              .then(() => { setMessage(t('healthRecorded')); setKey(newIdempotencyKey()); setForm({ ...form, description: '', nextDue: '', billAmount: '' }); setRepeatDays(''); setCustomRepeatDays(''); return load(); })
              .catch(err => setError(describeError(err)));
          }}>
            <label>{t('group')}<select value={form.groupId} onChange={e => setForm({ ...form, groupId: e.target.value })}>
              <option value="">—</option>{groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
            <label>{t('kindLabel')}<select value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value })}>
              {HEALTH_KINDS.map(k => <option key={k} value={k}>{t(`event.${k}`)}</option>)}</select></label>
            <label>{tf('date')}<input type="date" max={today()} value={form.date} onChange={e => { const date = e.target.value; const n = repeatDays === 'CUSTOM' ? Number(customRepeatDays) : Number(repeatDays); setForm(f => ({ ...f, date, nextDue: repeatDays && Number.isInteger(n) && n > 0 ? plusDays(date, n) : f.nextDue })); }} /></label>
            <label>{t('description')}<input maxLength={300} placeholder={t('vaccineExample')} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
            <label>{lang === 'ta' ? 'அடுத்த பராமரிப்பு' : 'Repeat after'}<select value={repeatDays} onChange={e => setRepeat(e.target.value)}>
              <option value="">{lang === 'ta' ? 'மீண்டும் வேண்டாம்' : 'Do not repeat'}</option><option value="30">30 days</option><option value="60">60 days</option><option value="90">90 days</option><option value="CUSTOM">Custom days</option>
            </select></label>
            {repeatDays === 'CUSTOM' && <label>{lang === 'ta' ? 'நாட்கள்' : 'Number of days'}<input type="number" min="1" max="3650" value={customRepeatDays} onChange={e => { setCustomRepeatDays(e.target.value); const n = Number(e.target.value); if (Number.isInteger(n) && n > 0 && n <= 3650) setForm(f => ({ ...f, nextDue: plusDays(f.date, n) })); }} /></label>}
            <label>{t('nextDue')} <small>{tc('optional')}</small><input type="date" min={plusDays(form.date, 1)} value={form.nextDue} onChange={e => { setForm({ ...form, nextDue: e.target.value }); setRepeatDays(''); }} /></label>
            {can(tenant, 'FINANCE_WRITE') && <>
              <label>{t('billAmount')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.billAmount} onChange={e => setForm({ ...form, billAmount: e.target.value })} /></label>
              {form.billAmount && <label>{tf('paidFrom')}<select value={form.billAccount} onChange={e => setForm({ ...form, billAccount: e.target.value })}>
                <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
            </>}
            <p className="hint full">{t('noAdvice')}</p>
            <div className="form-actions full"><button className="secondary">{t('recordHealth')}</button></div>
          </form>
        </>
      )}
    </>
  );
}
