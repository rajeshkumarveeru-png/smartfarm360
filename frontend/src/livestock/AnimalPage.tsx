import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { BreedingLine } from './HerdPage';
import { useGroups } from './LivestockLayout';
import type { AnimalCard, AnimalEvent } from './types';
import { HEALTH_KINDS } from './types';

type Form = '' | 'move' | 'breeding' | 'calving' | 'health' | 'exit';

/** The animal card (section 8) with its events, and recording moves, breeding, calving, health and leaving the herd. */
export default function AnimalPage() {
  const tenant = useTenant();
  const { animalId } = useParams();
  const t = useNs('livestock');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/livestock`;
  const canWrite = can(tenant, 'LIVESTOCK_WRITE');
  const { groups } = useGroups();
  const [card, setCard] = useState<AnimalCard | null>(null);
  const [form, setForm] = useState<Form>('');
  const [f, setF] = useState<Record<string, string>>({});
  const [calves, setCalves] = useState([{ tag: '', name: '', sex: 'FEMALE' }]);
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [repeatDays, setRepeatDays] = useState('');
  const [customRepeatDays, setCustomRepeatDays] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(() => api<AnimalCard>(`${base}/animals/${animalId}`).then(setCard).catch(e => setError(describeError(e))), [base, animalId]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!can(tenant, 'FINANCE_WRITE')) return;
    const fin = `/api/v1/tenants/${tenant.id}/finance`;
    Promise.all([api<Party[]>(`${fin}/parties`), api<FinancialAccount[]>(`${fin}/accounts`)])
      .then(([p, a]) => { setParties(p.filter(x => x.active)); setAccounts(a.filter(x => x.active)); }).catch(() => undefined);
  }, [tenant]);

  function start(next: Form) { setForm(next); setError(''); setF({ date: today() }); setCalves([{ tag: '', name: '', sex: 'FEMALE' }]); setRepeatDays(''); setCustomRepeatDays(''); }
  function dateAfter(date: string, days: number) { const d = new Date(`${date}T12:00:00`); d.setDate(d.getDate() + days); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
  const set = (k: string, v: string) => setF(x => ({ ...x, [k]: v }));
  const bill = () => (f.billAmount ? { amount: f.billAmount, partyId: f.billParty ? Number(f.billParty) : null,
    financialAccountId: !f.billParty && f.billAccount ? Number(f.billAccount) : null } : null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const id = animalId;
    const call = (path: string, body: unknown, method = 'POST') => api(`${base}${path}`, { method, body, headers: { 'Idempotency-Key': key } });
    try {
      if (form === 'move') await call(`/animals/${id}/moves`, { groupId: Number(f.groupId), date: f.date });
      if (form === 'breeding') await call(`/animals/${id}/breeding`, { kind: f.kind, date: f.date, service: f.kind === 'INSEMINATION' ? f.service || 'AI' : null,
        bull: f.bull || null, doneBy: f.doneBy || null, result: f.kind === 'PREGNANCY_CHECK' ? f.result || null : null, notes: f.notes || null, bill: bill() });
      if (form === 'calving') await call(`/animals/${id}/calving`, { date: f.date, result: f.result || 'LIVE', notes: f.notes || null,
        calves: (f.result || 'LIVE') === 'LIVE' ? calves.filter(c => c.tag.trim()).map(c => ({ tag: c.tag.trim(), name: c.name || null, sex: c.sex })) : [] });
      if (form === 'health') await call('/health', { animalId: Number(id), kind: f.kind || 'TREATMENT', date: f.date, description: f.description,
        doneBy: f.doneBy || null, withdrawalDays: f.withdrawal ? Number(f.withdrawal) : null, nextDue: f.nextDue || null, bill: bill() });
      if (form === 'exit') {
        if (!window.confirm(t('confirmExit'))) return;
        await call(`/animals/${id}/exit`, { date: f.date, kind: f.kind || 'SOLD', cause: f.cause || null, price: f.price || null,
          partyId: f.party ? Number(f.party) : null, financialAccountId: !f.party && f.account ? Number(f.account) : null });
      }
      setForm(''); setKey(newIdempotencyKey());
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (!card) return error ? <ErrorBanner message={error} /> : <Loading />;
  const a = card.animal;
  const active = a.status === 'ACTIVE';
  const female = a.sex === 'FEMALE';
  const accountOptions = <><option value="">—</option>{accounts.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</>;
  const partyOptions = <><option value="">—</option>{parties.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</>;
  const billFields = can(tenant, 'FINANCE_WRITE') && <>
    <label>{t('billAmount')} <small>{tc('optional')}</small><input inputMode="decimal" value={f.billAmount ?? ''} onChange={e => set('billAmount', e.target.value)} /></label>
    {f.billAmount && <label>{t('paidTo')} <small>{tc('optional')}</small><select value={f.billParty ?? ''} onChange={e => set('billParty', e.target.value)}>{partyOptions}</select></label>}
    {f.billAmount && !f.billParty && <label>{tf('paidFrom')}<select value={f.billAccount ?? ''} onChange={e => set('billAccount', e.target.value)}>{accountOptions}</select></label>}
  </>;
  const eventText = (e: AnimalEvent) => [t(`event.${e.kind}`), e.description, e.result && t(`result.${e.result}`), e.bull,
    e.withdrawalUntil && t('withheldUntilDate', { date: formatDate(e.withdrawalUntil, lang) }), e.nextDue && t('nextOn', { date: formatDate(e.nextDue, lang) }),
    e.expectedCalving && t('calvingOn', { date: formatDate(e.expectedCalving, lang) })].filter(Boolean).join(' · ');
  return (
    <>
      <div className="crop-page-head">
        <div>
          <h2>{a.tag}{a.name ? ` ${a.name}` : ''}</h2>
          <p>{t(`species.${a.species}`)} · {t(`sex.${a.sex}`)}{a.breed ? ` · ${a.breed}` : ''} · {t(`origin.${a.origin}`)} {formatDate(a.arrivedOn, lang)}
            {a.groupName ? ` · ${a.groupName}` : ''}{!active ? ` · ${t(`status.${a.status}`)} ${a.exitDate ? formatDate(a.exitDate, lang) : ''}` : ''}</p>
        </div>
        <Link className="secondary" to={`/t/${tenant.id}/livestock`}>{tc('back')}</Link>
      </div>
      <ErrorBanner message={error} />
      <div className="finance-stats">
        {female && <div className="finance-stat"><span>{t('reproLabel')}</span><b>{a.breeding.repro ? t(`repro.${a.breeding.repro}`) : '—'}</b><small><BreedingLine a={a} /></small></div>}
        {a.breeding.checkDueFrom && <div className="finance-stat"><span>{t('due.PREGNANCY_CHECK')}</span><b>{formatDate(a.breeding.checkDueFrom, lang)}</b></div>}
        {a.breeding.dryOffDue && <div className="finance-stat"><span>{t('due.DRY_OFF')}</span><b>{formatDate(a.breeding.dryOffDue, lang)}</b></div>}
        {a.bookValue != null && <div className="finance-stat"><span>{t('bookValue')}</span><b>{formatMoney(a.bookValue, lang)}</b></div>}
        {a.damTag && <div className="finance-stat"><span>{t('dam')}</span><b><Link to={`/t/${tenant.id}/livestock/animals/${a.damId}`}>{a.damTag}</Link></b><small>{a.sire}</small></div>}
      </div>

      {canWrite && active && form === '' && (
        <div className="quick-actions">
          <button type="button" className="secondary" onClick={() => start('move')}>{t('moveGroup')}</button>
          {female && <button type="button" className="secondary" onClick={() => { start('breeding'); set('kind', 'INSEMINATION'); }}>{t('recordBreeding')}</button>}
          {female && <button type="button" className="secondary" onClick={() => start('calving')}>{t('recordCalving')}</button>}
          <button type="button" className="secondary" onClick={() => { start('health'); set('kind', 'TREATMENT'); }}>{t('recordHealth')}</button>
          <button type="button" className="secondary" onClick={() => { start('exit'); set('kind', 'SOLD'); }}>{t('leaveHerd')}</button>
        </div>
      )}

      {form !== '' && (
        <form className="form-card form-grid" noValidate onSubmit={e => void submit(e)}>
          <h3 className="full">{t(`form.${form}`)}</h3>
          <label>{tf('date')}<input type="date" max={today()} value={f.date ?? ''} onChange={e => { const date = e.target.value; set('date', date); if (form === 'health' && repeatDays) { const n = repeatDays === 'CUSTOM' ? Number(customRepeatDays) : Number(repeatDays); if (Number.isInteger(n) && n > 0 && n <= 3650) set('nextDue', dateAfter(date, n)); } }} /></label>
          {form === 'move' && <label>{t('group')}<select value={f.groupId ?? ''} onChange={e => set('groupId', e.target.value)}>
            <option value="">—</option>{groups.filter(g => g.id !== a.groupId).map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>}
          {form === 'breeding' && <>
            <label>{t('kindLabel')}<select value={f.kind} onChange={e => set('kind', e.target.value)}>
              {['HEAT', 'INSEMINATION', 'PREGNANCY_CHECK', 'ABORTION', 'DRY_OFF'].map(k => <option key={k} value={k}>{t(`event.${k}`)}</option>)}</select></label>
            {f.kind === 'INSEMINATION' && <>
              <label>{t('service')}<select value={f.service ?? 'AI'} onChange={e => set('service', e.target.value)}>
                <option value="AI">{t('serviceAi')}</option><option value="NATURAL">{t('serviceNatural')}</option></select></label>
              <label>{t('bull')} <small>{tc('optional')}</small><input maxLength={120} value={f.bull ?? ''} onChange={e => set('bull', e.target.value)} /></label>
              <p className="hint full">{t('datesWorkedOut', { days: a.species === 'BUFFALO' ? '310' : '283' })}</p>
            </>}
            {f.kind === 'PREGNANCY_CHECK' && <label>{t('resultLabel')}<select value={f.result ?? ''} onChange={e => set('result', e.target.value)}>
              <option value="">—</option><option value="POSITIVE">{t('result.POSITIVE')}</option><option value="NEGATIVE">{t('result.NEGATIVE')}</option></select></label>}
            <label>{t('doneBy')} <small>{tc('optional')}</small><input maxLength={120} value={f.doneBy ?? ''} onChange={e => set('doneBy', e.target.value)} /></label>
            {billFields}
          </>}
          {form === 'calving' && <>
            <label>{t('resultLabel')}<select value={f.result ?? 'LIVE'} onChange={e => set('result', e.target.value)}>
              <option value="LIVE">{t('result.LIVE')}</option><option value="STILLBORN">{t('result.STILLBORN')}</option></select></label>
            {(f.result ?? 'LIVE') === 'LIVE' && calves.map((c, i) => (
              <div key={i} className="inline-fields full">
                <label>{t('calfTag')}<input maxLength={40} value={c.tag} onChange={e => setCalves(cs => cs.map((x, j) => (j === i ? { ...x, tag: e.target.value } : x)))} /></label>
                <label>{t('name')} <small>{tc('optional')}</small><input maxLength={80} value={c.name} onChange={e => setCalves(cs => cs.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} /></label>
                <label>{t('sexLabel')}<select value={c.sex} onChange={e => setCalves(cs => cs.map((x, j) => (j === i ? { ...x, sex: e.target.value } : x)))}>
                  <option value="FEMALE">{t('sex.FEMALE')}</option><option value="MALE">{t('sex.MALE')}</option></select></label>
              </div>
            ))}
            {(f.result ?? 'LIVE') === 'LIVE' && calves.length < 3 && <button type="button" className="text-action" onClick={() => setCalves(cs => [...cs, { tag: '', name: '', sex: 'FEMALE' }])}>{t('addCalf')}</button>}
          </>}
          {form === 'health' && <>
            <label>{t('kindLabel')}<select value={f.kind} onChange={e => set('kind', e.target.value)}>{HEALTH_KINDS.map(k => <option key={k} value={k}>{t(`event.${k}`)}</option>)}</select></label>
            <label>{t('description')}<input maxLength={300} value={f.description ?? ''} onChange={e => set('description', e.target.value)} /></label>
            <label>{t('withdrawalDays')} <small>{tc('optional')}</small><input inputMode="numeric" value={f.withdrawal ?? ''} onChange={e => set('withdrawal', e.target.value)} /></label>
            <label>{lang === 'ta' ? 'மீண்டும் செய்ய வேண்டிய இடைவெளி' : 'Repeat after'}<select value={repeatDays} onChange={e => { const v = e.target.value; setRepeatDays(v); setCustomRepeatDays(''); set('nextDue', v && v !== 'CUSTOM' ? dateAfter(f.date || today(), Number(v)) : ''); }}>
              <option value="">{lang === 'ta' ? 'மீண்டும் வேண்டாம்' : 'Do not repeat'}</option><option value="30">30 days</option><option value="60">60 days</option><option value="90">90 days</option><option value="CUSTOM">Custom days</option>
            </select></label>
            {repeatDays === 'CUSTOM' && <label>{lang === 'ta' ? 'நாட்கள்' : 'Number of days'}<input type="number" min="1" max="3650" value={customRepeatDays} onChange={e => { setCustomRepeatDays(e.target.value); const n = Number(e.target.value); if (Number.isInteger(n) && n > 0 && n <= 3650) set('nextDue', dateAfter(f.date || today(), n)); }} /></label>}
            <label>{t('nextDue')} <small>{tc('optional')}</small><input type="date" min={dateAfter(f.date || today(), 1)} value={f.nextDue ?? ''} onChange={e => { set('nextDue', e.target.value); setRepeatDays(''); }} /></label>
            <label>{t('doneBy')} <small>{tc('optional')}</small><input maxLength={120} value={f.doneBy ?? ''} onChange={e => set('doneBy', e.target.value)} /></label>
            {billFields}
            <p className="hint full">{t('noAdvice')}</p>
          </>}
          {form === 'exit' && <>
            <label>{t('exitKind')}<select value={f.kind} onChange={e => set('kind', e.target.value)}>
              {['SOLD', 'CULLED', 'DIED'].map(k => <option key={k} value={k}>{t(`status.${k}`)}</option>)}</select></label>
            {f.kind !== 'DIED' && <>
              <label>{t('price')}<input inputMode="decimal" value={f.price ?? ''} onChange={e => set('price', e.target.value)} /></label>
              <label>{t('soldTo')} <small>{tc('optional')}</small><select value={f.party ?? ''} onChange={e => set('party', e.target.value)}>{partyOptions}</select></label>
              {!f.party && <label>{tf('receivedInto')}<select value={f.account ?? ''} onChange={e => set('account', e.target.value)}>{accountOptions}</select></label>}
            </>}
            <label className="full">{t('cause')} <small>{tc('optional')}</small><input maxLength={300} value={f.cause ?? ''} onChange={e => set('cause', e.target.value)} /></label>
            <p className="hint full">{a.assetId ? t('exitRegisteredHelp') : t('exitHomeBredHelp')}</p>
          </>}
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setForm('')}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}

      <h3 className="section-space">{t('history')}</h3>
      {card.events.length === 0 ? <p className="hint">{t('noEvents')}</p> : (
        <ul className="plain-list">{card.events.map(e => <li key={e.id}>{formatDate(e.date, lang)} · {eventText(e)}{e.groupName && !e.animalTag ? <small> · {e.groupName}</small> : null}</li>)}</ul>
      )}
      {card.lactations.length > 0 && <>
        <h3 className="section-space">{t('lactations')}</h3>
        <div className="table-scroll"><table className="data-table report">
          <thead><tr><th>{t('lactation')}</th><th>{t('calved')}</th><th>{t('driedOff')}</th><th className="num">{t('recordedLitres')}</th></tr></thead>
          <tbody>{card.lactations.map(l => <tr key={l.number}><td>{l.number}</td><td>{formatDate(l.calvingDate, lang)}</td>
            <td>{l.dryOffDate ? formatDate(l.dryOffDate, lang) : t('inMilk')}</td><td className="num">{l.recordedLitres != null ? formatNumber(l.recordedLitres, lang, 2) : '—'}</td></tr>)}</tbody>
        </table></div>
      </>}
      {card.calves.length > 0 && <>
        <h3 className="section-space">{t('calves')}</h3>
        <ul className="plain-list">{card.calves.map(c => <li key={c.id}><Link to={`/t/${tenant.id}/livestock/animals/${c.id}`}>{c.tag}{c.name ? ` ${c.name}` : ''}</Link>
          <small> · {t(`sex.${c.sex}`)} · {c.birthDate ? formatDate(c.birthDate, lang) : ''}</small></li>)}</ul>
      </>}
      <h3 className="section-space">{t('groupHistory')}</h3>
      <ul className="plain-list">{card.moves.map(m => <li key={`${m.groupId}-${m.from}`}>{formatDate(m.from, lang)} · {m.groupName}</li>)}</ul>
    </>
  );
}
