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
import { formatDate, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { FlockGroup } from './types';

const PURPOSES: Record<'poultry' | 'smallStock', string[]> = { poultry: ['LAYER', 'BROILER', 'COUNTRY'], smallStock: ['BREEDING_HERD', 'FATTENING', 'MIXED'] };
const empty = (kind: 'poultry' | 'smallStock') => ({ name: '', species: kind === 'poultry' ? 'CHICKEN' : 'GOAT', purpose: PURPOSES[kind][0], placedOn: today(),
  ageDays: '', strain: '', heads: '', bought: false, amount: '', partyId: '', financialAccountId: '' });

/** Poultry batches or goat and sheep herds (J1, J2): heads now, and starting a batch or herd with its first count (J3). */
export default function FlocksPage({ kind }: { kind: 'poultry' | 'smallStock' }) {
  const tenant = useTenant();
  const t = useNs('livestock');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/livestock`;
  const canWrite = can(tenant, 'LIVESTOCK_WRITE');
  const canBuy = canWrite && can(tenant, 'FINANCE_WRITE');
  const [groups, setGroups] = useState<FlockGroup[] | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState(() => empty(kind));
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [key, setKey] = useState(newIdempotencyKey);
  const [error, setError] = useState('');

  const load = useCallback(() => api<FlockGroup[]>(`${base}/flocks`)
    .then(g => setGroups(g.filter(x => (kind === 'poultry' ? x.species === 'CHICKEN' : x.species !== 'CHICKEN'))))
    .catch(e => setError(describeError(e))), [base, kind]);
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
    if (!form.name.trim() || !/^\d+$/.test(form.heads) || Number(form.heads) <= 0) { setError(t('errors.nameAndHeads')); return; }
    if (form.bought && (!/^\d+(\.\d{1,2})?$/.test(form.amount) || (!form.partyId && !form.financialAccountId))) { setError(t('errors.purchase')); return; }
    setError('');
    try {
      await api(`${base}/flocks`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: {
        name: form.name.trim(), species: form.species, purpose: form.purpose, placedOn: form.placedOn, heads: Number(form.heads),
        ageAtPlacementDays: form.ageDays ? Number(form.ageDays) : null, strain: form.strain || null,
        bill: form.bought ? { amount: form.amount, partyId: form.partyId ? Number(form.partyId) : null,
          financialAccountId: !form.partyId && form.financialAccountId ? Number(form.financialAccountId) : null } : null } });
      setOpen(false); setForm(empty(kind)); setKey(newIdempotencyKey());
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  const visible = (groups ?? []).filter(g => showClosed || !g.closed);
  return (
    <>
      <div className="crop-page-head">
        <div><h2>{t(kind === 'poultry' ? 'poultry' : 'smallStock')}</h2><p>{t(kind === 'poultry' ? 'poultryHelp' : 'smallStockHelp')}</p></div>
        {canWrite && !open && <button type="button" className="primary" onClick={() => setOpen(true)}><Plus size={16} aria-hidden />{t(kind === 'poultry' ? 'startBatch' : 'startHerd')}</button>}
      </div>
      <ErrorBanner message={error} />
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => void create(e)}>
          <label>{t('groupName')}<input maxLength={140} placeholder={t(kind === 'poultry' ? 'batchExample' : 'herdExample')} value={form.name} onChange={e => set({ name: e.target.value })} /></label>
          {kind === 'smallStock' && <label>{t('speciesLabel')}<select value={form.species} onChange={e => set({ species: e.target.value })}>
            <option value="GOAT">{t('species.GOAT')}</option><option value="SHEEP">{t('species.SHEEP')}</option></select></label>}
          <label>{t('purposeLabel')}<select value={form.purpose} onChange={e => set({ purpose: e.target.value })}>
            {PURPOSES[kind].map(p => <option key={p} value={p}>{t(`purpose.${p}`)}</option>)}</select></label>
          <label>{t(kind === 'poultry' ? 'placedOn' : 'startedOn')}<input type="date" max={today()} value={form.placedOn} onChange={e => set({ placedOn: e.target.value })} /></label>
          <label>{t('heads')}<input inputMode="numeric" value={form.heads} onChange={e => set({ heads: e.target.value })} /></label>
          {kind === 'poultry' && <>
            <label>{t('ageAtPlacement')} <small>{tc('optional')}</small><input inputMode="numeric" value={form.ageDays} onChange={e => set({ ageDays: e.target.value })} /></label>
            <label>{t('strain')} <small>{tc('optional')}</small><input maxLength={80} value={form.strain} onChange={e => set({ strain: e.target.value })} /></label>
          </>}
          {canBuy && <label className="check full"><input type="checkbox" checked={form.bought} onChange={e => set({ bought: e.target.checked })} />{t('boughtThem')}</label>}
          {form.bought && <>
            <label>{t('billAmountTotal')}<input inputMode="decimal" value={form.amount} onChange={e => set({ amount: e.target.value })} /></label>
            <label>{t('boughtFrom')} <small>{tc('optional')}</small><select value={form.partyId} onChange={e => set({ partyId: e.target.value })}>
              <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
            {!form.partyId && <label>{tf('paidFrom')}<select value={form.financialAccountId} onChange={e => set({ financialAccountId: e.target.value })}>
              <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
            <p className="hint full">{t('youngStockHelp')}</p>
          </>}
          <div className="form-actions full">
            <button type="button" className="secondary" onClick={() => setOpen(false)}>{tc('cancel')}</button>
            <button className="primary">{tc('save')}</button>
          </div>
        </form>
      )}
      {kind === 'poultry' && <label className="check"><input type="checkbox" checked={showClosed} onChange={e => setShowClosed(e.target.checked)} />{t('showClosed')}</label>}
      {!groups ? <Loading /> : visible.length === 0 ? <p className="hint">{t(kind === 'poultry' ? 'noBatches' : 'noHerds')}</p> : (
        <table className="data-table">
          <thead><tr><th>{t('groupName')}</th><th>{t('purposeLabel')}</th><th>{t(kind === 'poultry' ? 'placedOn' : 'startedOn')}</th><th className="num">{t('heads')}</th></tr></thead>
          <tbody>{visible.map(g => (
            <tr key={g.id}>
              <td><Link to={`/t/${tenant.id}/livestock/${kind === 'poultry' ? 'poultry' : 'small-stock'}/${g.id}`}>{g.name}</Link>{g.closed && <small> · {t('closed')}</small>}
                {kind === 'smallStock' && <small> · {t(`species.${g.species}`)}</small>}</td>
              <td>{t(`purpose.${g.purpose}`)}</td><td>{g.placedOn ? formatDate(g.placedOn, lang) : '—'}</td>
              <td className="num">{formatNumber(g.animals, lang, 0)}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </>
  );
}
