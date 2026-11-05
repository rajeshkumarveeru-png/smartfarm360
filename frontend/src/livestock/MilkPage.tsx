import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import { Toast } from '../components/Toast';
import type { FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError, isStale } from '../lib/errors';
import { formatDate, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import { useGroups } from './LivestockLayout';
import type { Animal, MilkDay, RecordRefLike, Session } from './milkTypes';
import { SESSIONS } from './types';

type Entry = { total: string; fat: string; snf: string; perAnimal: boolean; yields: Record<number, string> };
const litresOk = (v: string) => v === '' || /^\d{1,6}(\.\d{1,2})?$/.test(v.trim());
const key = (s: Session, g: number) => `${s}|${g}`;

/** The day's milk (I5): each group's morning and evening, as a total or per animal; where it went (I6); selling it (I8). */
export default function MilkPage() {
  const tenant = useTenant();
  const t = useNs('livestock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/livestock`;
  const canWrite = can(tenant, 'LIVESTOCK_WRITE');
  const canSell = canWrite && can(tenant, 'FINANCE_WRITE');
  const { groups } = useGroups();
  const [date, setDate] = useState(today());
  const [day, setDay] = useState<MilkDay | null>(null);
  const [animals, setAnimals] = useState<Animal[]>([]);
  const [entries, setEntries] = useState<Record<string, Entry>>({});
  const [uses, setUses] = useState({ homeUse: '', fedLitres: '', fedGroupId: '', discarded: '', discardReason: '' });
  const [sale, setSale] = useState({ partyId: '', financialAccountId: '', litres: '', amount: '', groupId: '', periodFrom: '', periodTo: today() });
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [keys, setKeys] = useState({ uses: newIdempotencyKey(), sale: newIdempotencyKey() });
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [stale, setStale] = useState(false);
  const tc = useNs('common');

  const milkGroups = useMemo(() => groups.filter(g => g.purpose === 'MILKING' || animals.some(a => a.groupId === g.id && a.breeding.inMilk)), [groups, animals]);
  const load = useCallback(async () => {
    setError(''); setMessage(''); setStale(false);
    try {
      const [d, a] = await Promise.all([api<MilkDay>(`${base}/milk/${date}`), api<Animal[]>(`${base}/animals`)]);
      setDay(d); setAnimals(a.filter(x => x.sex === 'FEMALE'));
      const next: Record<string, Entry> = {};
      for (const s of d.sessions) {
        next[key(s.session, s.groupId)] = { total: String(s.litres), fat: s.fat != null ? String(s.fat) : '', snf: s.snf != null ? String(s.snf) : '',
          perAnimal: s.yields.length > 0, yields: Object.fromEntries(s.yields.map(y => [y.animalId, String(y.litres)])) };
      }
      setEntries(next);
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, date]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!canSell) return;
    const fin = `/api/v1/tenants/${tenant.id}/finance`;
    Promise.all([api<Party[]>(`${fin}/parties`), api<FinancialAccount[]>(`${fin}/accounts`)])
      .then(([p, a]) => { setParties(p.filter(x => x.active && x.customer)); setAccounts(a.filter(x => x.active)); }).catch(() => undefined);
  }, [canSell, tenant.id]);

  const entry = (s: Session, g: number): Entry => entries[key(s, g)] ?? { total: '', fat: '', snf: '', perAnimal: false, yields: {} };
  const update = (s: Session, g: number, patch: Partial<Entry>) => setEntries(e => ({ ...e, [key(s, g)]: { ...entry(s, g), ...patch } }));

  async function save() {
    const sessions = milkGroups.flatMap(g => SESSIONS.map(s => {
      const e = entry(s, g.id);
      const yields = e.perAnimal ? Object.entries(e.yields).filter(([, v]) => v !== '').map(([id, v]) => ({ animalId: Number(id), litres: v })) : [];
      // Each session's version as loaded ("0" when none was stored), so another person's change is not overwritten (N7)
      const baseVersion = day?.sessions.find(x => x.session === s && x.groupId === g.id)?.version ?? '0';
      return { session: s, groupId: g.id, litres: !e.perAnimal && e.total !== '' ? e.total : null, fat: e.fat || null, snf: e.snf || null, yields, baseVersion, _touched: key(s, g.id) in entries };
    })).filter(x => x._touched || x.litres != null || x.yields.length > 0).map(({ _touched, ...rest }) => { void _touched; return rest; });
    if (sessions.length === 0) { setError(t('errors.noMilk')); return; }
    const values = sessions.flatMap(s => [s.litres ?? '', ...s.yields.map(y => y.litres)]);
    if (!values.every(v => litresOk(String(v)))) { setError(t('errors.litres')); return; }
    try {
      setDay(await api<MilkDay>(`${base}/milk/${date}`, { method: 'PUT', body: { sessions } }));
      setMessage(t('milkSaved', { date: formatDate(date, lang) }));
    } catch (e) {
      setError(describeError(e));
      setStale(isStale(e));
    }
  }

  async function post<T>(path: string, body: unknown, k: 'uses' | 'sale', done: (r: T) => void) {
    setError(''); setMessage('');
    try {
      const result = await api<T>(`${base}${path}`, { method: 'POST', body, headers: { 'Idempotency-Key': keys[k] } });
      setKeys(x => ({ ...x, [k]: newIdempotencyKey() }));
      await load();   // reloading clears messages, so the confirmation is set after it
      done(result);
    } catch (e) {
      setError(describeError(e));
    }
  }

  if (!day) return error ? <ErrorBanner message={error} /> : <Loading />;
  const fedGroups = groups.filter(g => g.purpose === 'CALF' || g.purpose === 'HEIFER' || g.purpose === 'OTHER');
  return (
    <>
      <div className="crop-page-head"><div><h2>{t('milk')}</h2><p>{t('milkHelp')}</p></div></div>
      <ErrorBanner message={error} onRetry={stale ? () => void load() : undefined} retryLabel={tc('loadLatest')} />
      <Toast message={message} onClose={() => setMessage('')} />
      <div className="form-card form-grid filters">
        <label>{tf('date')}<input type="date" max={today()} value={date} onChange={e => setDate(e.target.value)} /></label>
        <p className="hint">{t('dayTotal', { litres: formatNumber(day.total, lang, 2) })} · {t('inStockNow', { litres: formatNumber(day.inStock, lang, 2) })}</p>
      </div>
      {day.withheld.length > 0 && (
        <p className="error-banner" role="alert">{t('withheldWarning', { animals: day.withheld.map(w => `${w.tag} (${formatDate(w.until, lang)})`).join(', ') })}</p>
      )}
      {milkGroups.length === 0 ? <p className="hint">{t('noMilkingGroups')}</p> : milkGroups.map(g => {
        const herd = animals.filter(a => a.groupId === g.id);
        return (
          <div key={g.id} className="form-card">
            <h3>{g.name}</h3>
            {SESSIONS.map(s => {
              const e = entry(s, g.id);
              return (
                <div key={s} className="milk-session">
                  <div className="inline-fields">
                    <b>{t(`session.${s}`)}</b>
                    {herd.length > 0 && canWrite && <label className="check"><input type="checkbox" checked={e.perAnimal} onChange={ev => update(s, g.id, { perAnimal: ev.target.checked })} />{t('perAnimal')}</label>}
                  </div>
                  {e.perAnimal ? (
                    <div className="form-grid">
                      {herd.map(a => (
                        <label key={a.id}>{a.tag}{a.name ? ` ${a.name}` : ''}{a.breeding.withheldUntil && <small className="low-stock-label"> · {t('withheld')}</small>}
                          <input aria-label={t('litresFor', { animal: a.tag, session: t(`session.${s}`) })} inputMode="decimal" disabled={!canWrite} value={e.yields[a.id] ?? ''}
                                 onChange={ev => update(s, g.id, { yields: { ...e.yields, [a.id]: ev.target.value } })} /></label>
                      ))}
                    </div>
                  ) : (
                    <div className="form-grid">
                      <label>{t('groupLitres')}<input aria-label={t('litresFor', { animal: g.name, session: t(`session.${s}`) })} inputMode="decimal" disabled={!canWrite}
                                                     value={e.total} onChange={ev => update(s, g.id, { total: ev.target.value })} /></label>
                    </div>
                  )}
                  <div className="form-grid">
                    <label>{t('fat')}<input inputMode="decimal" disabled={!canWrite} value={e.fat} onChange={ev => update(s, g.id, { fat: ev.target.value })} /></label>
                    <label>{t('snf')}<input inputMode="decimal" disabled={!canWrite} value={e.snf} onChange={ev => update(s, g.id, { snf: ev.target.value })} /></label>
                  </div>
                </div>
              );
            })}
          </div>
        );
      })}
      {canWrite && milkGroups.length > 0 && <div className="form-actions"><button type="button" className="primary" onClick={() => void save()}>{t('saveMilk')}</button></div>}

      {canWrite && (
        <>
          <h3 className="section-space">{t('whereMilkWent')}</h3>
          <form className="form-card form-grid" onSubmit={e => {
            e.preventDefault();
            if (![uses.homeUse, uses.fedLitres, uses.discarded].every(litresOk)) { setError(t('errors.litres')); return; }
            void post<RecordRefLike[]>('/milk-uses', { date, homeUse: uses.homeUse || null, fedLitres: uses.fedLitres || null,
              fedGroupId: uses.fedGroupId ? Number(uses.fedGroupId) : null, discarded: uses.discarded || null, discardReason: uses.discardReason || null }, 'uses',
            r => { setMessage(t('usesRecorded', { count: String(r.length) })); setUses({ homeUse: '', fedLitres: '', fedGroupId: uses.fedGroupId, discarded: '', discardReason: '' }); });
          }}>
            <label>{t('homeUse')}<input inputMode="decimal" value={uses.homeUse} onChange={e => setUses({ ...uses, homeUse: e.target.value })} /></label>
            <label>{t('fedToAnimals')}<input inputMode="decimal" value={uses.fedLitres} onChange={e => setUses({ ...uses, fedLitres: e.target.value })} /></label>
            <label>{t('fedTo')}<select value={uses.fedGroupId} onChange={e => setUses({ ...uses, fedGroupId: e.target.value })}>
              <option value="">—</option>{fedGroups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
            <label>{t('discarded')}<input inputMode="decimal" value={uses.discarded} onChange={e => setUses({ ...uses, discarded: e.target.value })} /></label>
            {uses.discarded && <label className="full">{t('discardReason')}<input maxLength={300} value={uses.discardReason} onChange={e => setUses({ ...uses, discardReason: e.target.value })} /></label>}
            <p className="hint full">{t('usesHelp')}</p>
            <div className="form-actions full"><button className="secondary">{t('recordUses')}</button></div>
          </form>
        </>
      )}

      {canSell && (
        <>
          <h3 className="section-space">{t('sellMilk')}</h3>
          <form className="form-card form-grid" onSubmit={e => {
            e.preventDefault();
            if (!sale.groupId || !/^\d+(\.\d{1,2})?$/.test(sale.litres) || !/^\d+(\.\d{1,2})?$/.test(sale.amount) || (!sale.partyId && !sale.financialAccountId)) {
              setError(t('errors.sale')); return;
            }
            void post<RecordRefLike>('/milk-sales', { date: sale.periodTo, partyId: sale.partyId ? Number(sale.partyId) : null,
              financialAccountId: !sale.partyId && sale.financialAccountId ? Number(sale.financialAccountId) : null, litres: sale.litres, amount: sale.amount,
              groupId: Number(sale.groupId), periodFrom: sale.periodFrom || null, periodTo: sale.periodTo }, 'sale',
            r => { setMessage(t('saleRecorded', { number: r.number })); setSale({ ...sale, litres: '', amount: '', periodFrom: '' }); });
          }}>
            <label>{t('buyer')} <small>{t('buyerHelp')}</small><select value={sale.partyId} onChange={e => setSale({ ...sale, partyId: e.target.value })}>
              <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
            {!sale.partyId && <label>{tf('receivedInto')}<select value={sale.financialAccountId} onChange={e => setSale({ ...sale, financialAccountId: e.target.value })}>
              <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
            <label>{t('periodFrom')}<input type="date" value={sale.periodFrom} onChange={e => setSale({ ...sale, periodFrom: e.target.value })} /></label>
            <label>{t('periodTo')}<input type="date" max={today()} value={sale.periodTo} onChange={e => setSale({ ...sale, periodTo: e.target.value })} /></label>
            <label>{t('litres')}<input inputMode="decimal" value={sale.litres} onChange={e => setSale({ ...sale, litres: e.target.value })} /></label>
            <label>{t('billAmountTotal')}<input inputMode="decimal" value={sale.amount} onChange={e => setSale({ ...sale, amount: e.target.value })} /></label>
            <label>{t('fromGroup')}<select value={sale.groupId} onChange={e => setSale({ ...sale, groupId: e.target.value })}>
              <option value="">—</option>{milkGroups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
            <p className="hint full">{t('saleHelp')}</p>
            <div className="form-actions full"><button className="secondary">{t('recordSale')}</button></div>
          </form>
        </>
      )}
    </>
  );
}
