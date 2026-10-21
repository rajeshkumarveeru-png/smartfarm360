import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, today } from '../lib/format';
import { can } from '../lib/nav';
import { ExportBar } from '../reports/ReportTools';
import { useGroups } from './LivestockLayout';
import type { Animal } from './types';

const money = (v: string) => /^\d{1,12}(\.\d{1,2})?$/.test(v.trim());
const empty = () => ({ tag: '', name: '', species: 'COW', breed: '', sex: 'FEMALE', birthDate: '', origin: 'BORN', arrivedOn: today(), groupId: '', sire: '',
  amount: '', partyId: '', financialAccountId: '', life: '72', cull: '' });

/** One line of breeding state: "Pregnant · calving 19 Apr 2027", "Milking · 30 days in milk", "Milk withheld until …". */
export function BreedingLine({ a }: { a: Animal }) {
  const t = useNs('livestock');
  const lang = currentLanguage();
  const b = a.breeding;
  const parts: string[] = [];
  if (b.inMilk) parts.push(b.daysInMilk != null ? t('inMilkDays', { days: String(b.daysInMilk) }) : t('inMilk'));
  if (b.repro && b.repro !== 'OPEN') parts.push(t(`repro.${b.repro}`));
  if (b.expectedCalving) parts.push(t('calvingOn', { date: formatDate(b.expectedCalving, lang) }));
  return (
    <>
      {parts.join(' · ')}
      {b.withheldUntil && <b className="low-stock-label"> · {t('withheldUntilDate', { date: formatDate(b.withheldUntil, lang) })}</b>}
    </>
  );
}

/** The herd register (I1): animals by group with breeding state, and adding an animal (bought animals go on a bill, I3). */
export default function HerdPage() {
  const tenant = useTenant();
  const t = useNs('livestock');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/livestock`;
  const canWrite = can(tenant, 'LIVESTOCK_WRITE');
  const canBuy = canWrite && can(tenant, 'FINANCE_WRITE');
  const { groups } = useGroups();
  const [showGone, setShowGone] = useState(false);
  const [animals, setAnimals] = useState<Animal[] | null>(null);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(empty);
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState('');

  const load = useCallback(() => api<Animal[]>(`${base}/animals?includeGone=${showGone}`).then(setAnimals).catch(e => setError(describeError(e))), [base, showGone]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!canBuy) return;
    const fin = `/api/v1/tenants/${tenant.id}/finance`;
    Promise.all([api<Party[]>(`${fin}/parties`), api<FinancialAccount[]>(`${fin}/accounts`)])
      .then(([p, a]) => { setParties(p.filter(x => x.active)); setAccounts(a.filter(x => x.active)); }).catch(() => undefined);
  }, [canBuy, tenant.id]);

  const set = (patch: Partial<ReturnType<typeof empty>>) => setForm(f => ({ ...f, ...patch }));
  async function create(e: React.FormEvent) {
    e.preventDefault();
    if (!form.tag.trim() || !form.groupId) { setError(t('errors.tagAndGroup')); return; }
    const bought = form.origin === 'BOUGHT';
    if (bought && (!money(form.amount) || (!form.partyId && !form.financialAccountId))) { setError(t('errors.purchase')); return; }
    setError('');
    try {
      await api(`${base}/animals`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: {
        tag: form.tag.trim(), name: form.name || null, species: form.species, breed: form.breed || null, sex: form.sex, birthDate: form.birthDate || null,
        origin: form.origin, arrivedOn: form.arrivedOn, groupId: Number(form.groupId), sire: form.sire || null,
        purchase: bought ? { amount: form.amount, partyId: form.partyId ? Number(form.partyId) : null,
          financialAccountId: !form.partyId && form.financialAccountId ? Number(form.financialAccountId) : null,
          usefulLifeMonths: form.life ? Number(form.life) : null, cullValue: form.cull || null } : null } });
      setOpen(false); setForm(empty()); setKey(newIdempotencyKey());
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t('herd')}</h2><p>{t('herdHelp')}</p></div>
        <div className="quick-actions">
          {canWrite && !open && groups.length > 0 && <button type="button" className="primary" onClick={() => setOpen(true)}><Plus size={16} aria-hidden />{t('addAnimal')}</button>}
          <ExportBar path={`${base}/animals?includeGone=${showGone}`} name={`herd-register-${today()}`} onError={setError} />
        </div>
      </div>
      <ErrorBanner message={error} />
      {groups.length === 0 && canWrite && <p className="hint">{t('createGroupFirst')} <Link to={`/t/${tenant.id}/livestock/groups`}>{t('groups')}</Link></p>}
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => void create(e)}>
          <label>{t('tag')}<input required maxLength={40} value={form.tag} onChange={e => set({ tag: e.target.value })} /></label>
          <label>{t('name')} <small>{tc('optional')}</small><input maxLength={80} value={form.name} onChange={e => set({ name: e.target.value })} /></label>
          <label>{t('speciesLabel')}<select value={form.species} onChange={e => set({ species: e.target.value })}>
            <option value="COW">{t('species.COW')}</option><option value="BUFFALO">{t('species.BUFFALO')}</option></select></label>
          <label>{t('sexLabel')}<select value={form.sex} onChange={e => set({ sex: e.target.value })}>
            <option value="FEMALE">{t('sex.FEMALE')}</option><option value="MALE">{t('sex.MALE')}</option></select></label>
          <label>{t('breed')} <small>{tc('optional')}</small><input maxLength={80} value={form.breed} onChange={e => set({ breed: e.target.value })} /></label>
          <label>{t('group')}<select value={form.groupId} onChange={e => set({ groupId: e.target.value })}>
            <option value="">—</option>{groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
          <label>{t('originLabel')}<select value={form.origin} onChange={e => set({ origin: e.target.value })}>
            <option value="BORN">{t('origin.BORN')}</option>{canBuy && <option value="BOUGHT">{t('origin.BOUGHT')}</option>}<option value="RECEIVED">{t('origin.RECEIVED')}</option></select></label>
          <label>{form.origin === 'BORN' ? t('birthDate') : t('arrivedOn')}<input type="date" max={today()} value={form.arrivedOn} onChange={e => set({ arrivedOn: e.target.value })} /></label>
          {form.origin !== 'BORN' && <label>{t('birthDate')} <small>{tc('optional')}</small><input type="date" value={form.birthDate} onChange={e => set({ birthDate: e.target.value })} /></label>}
          <label>{t('sire')} <small>{tc('optional')}</small><input maxLength={120} value={form.sire} onChange={e => set({ sire: e.target.value })} /></label>
          {form.origin === 'BOUGHT' && <>
            <label>{t('price')}<input inputMode="decimal" value={form.amount} onChange={e => set({ amount: e.target.value })} /></label>
            <label>{t('boughtFrom')} <small>{tc('optional')}</small><select value={form.partyId} onChange={e => set({ partyId: e.target.value })}>
              <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
            {!form.partyId && <label>{tf('paidFrom')}<select value={form.financialAccountId} onChange={e => set({ financialAccountId: e.target.value })}>
              <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
            <label>{t('productiveLife')}<input inputMode="numeric" value={form.life} onChange={e => set({ life: e.target.value })} /></label>
            <label>{t('cullValue')} <small>{tc('optional')}</small><input inputMode="decimal" value={form.cull} onChange={e => set({ cull: e.target.value })} /></label>
            <p className="hint full">{t('boughtHelp')}</p>
          </>}
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}
      <label className="check"><input type="checkbox" checked={showGone} onChange={e => setShowGone(e.target.checked)} />{t('showGone')}</label>
      {!animals ? <Loading /> : animals.length === 0 ? <p className="hint">{t('noAnimals')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{t('tag')}</th><th>{t('group')}</th><th>{t('reproLabel')}</th><th className="num">{t('lactation')}</th>
              {can(tenant, 'FINANCE_READ') && <th className="num">{t('bookValue')}</th>}</tr></thead>
            <tbody>
              {animals.map(a => (
                <tr key={a.id}>
                  <td><Link to={`/t/${tenant.id}/livestock/animals/${a.id}`}>{a.tag}{a.name ? ` ${a.name}` : ''}</Link>
                    <small> · {t(`species.${a.species}`)} · {t(`sex.${a.sex}`)}{a.status !== 'ACTIVE' ? ` · ${t(`status.${a.status}`)}` : ''}</small></td>
                  <td>{a.groupName ?? '—'}</td>
                  <td>{a.sex === 'FEMALE' ? <BreedingLine a={a} /> : ''}</td>
                  <td className="num">{a.breeding.lactation ?? ''}</td>
                  {can(tenant, 'FINANCE_READ') && <td className="num">{a.bookValue != null ? formatMoney(a.bookValue, lang) : '—'}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
