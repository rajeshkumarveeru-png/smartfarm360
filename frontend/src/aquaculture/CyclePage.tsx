import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { FinancialAccount, Party } from '../finance/types';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Item } from '../stock/types';
import { useSpeciesName } from './AquaLayout';
import { SPECIES, type CycleReport, type Day, type Figures, type Harvest, type Sample, type Stocking } from './types';

const whole = (v: string) => /^\d{1,9}$/.test(v.trim()) && Number(v) > 0;
const kgOk = (v: string) => /^\d{1,9}(\.\d{1,3})?$/.test(v.trim()) && Number(v) > 0;
const money = (v: string) => /^\d{1,12}(\.\d{1,2})?$/.test(v.trim()) && Number(v) > 0;
const keyOf = (s: { species: string; otherName: string | null }) => `${s.species}|${(s.otherName ?? '').toLowerCase()}`;
const fromKey = (k: string) => { const [species, other] = k.split('|'); return { species, otherName: other || null }; };
type Payer = { partyId: string; financialAccountId: string };
const payer = (f: Payer) => ({ partyId: f.partyId ? Number(f.partyId) : null, financialAccountId: !f.partyId && f.financialAccountId ? Number(f.financialAccountId) : null });
type LineForm = { key: string; grade: string; kg: string; count: string; use: 'SOLD' | 'HOME' | 'GIVEN'; rate: string };
const emptyLine = (key = ''): LineForm => ({ key, grade: '', kg: '', count: '', use: 'SOLD', rate: '' });

/** One culture cycle (aquaculture design): figures (K9), stocking (K3, K4), the daily pond record (K5), samples (K6) and harvests (K8). */
export default function CyclePage() {
  const { cycleId } = useParams();
  const tenant = useTenant();
  const t = useNs('aquaculture');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const name = useSpeciesName();
  const base = `/api/v1/tenants/${tenant.id}/aquaculture`;
  const cycle = `${base}/cycles/${cycleId}`;
  const canWrite = can(tenant, 'AQUACULTURE_WRITE');
  const canMoney = canWrite && can(tenant, 'FINANCE_WRITE');
  const showMoney = can(tenant, 'FINANCE_READ');
  const [range, setRange] = useState({ from: `${today().slice(0, 8)}01`, to: today() });
  const [report, setReport] = useState<CycleReport | null>(null);
  const [stockings, setStockings] = useState<Stocking[]>([]);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [harvests, setHarvests] = useState<Harvest[]>([]);
  const [feeds, setFeeds] = useState<Item[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [date, setDate] = useState(today());
  const [day, setDay] = useState<{ feeds: { itemId: string; kg: string }[]; deaths: { key: string; count: string; cause: string }[]; notes: string } | null>(null);
  const [stock, setStock] = useState({ date: today(), species: 'ROHU', otherName: '', count: '', kg: '', hatchery: '', amount: '', partyId: '', financialAccountId: '' });
  const [sample, setSample] = useState({ date: today(), key: '', fishWeighed: '', kg: '' });
  const [harvest, setHarvest] = useState({ date: today(), kind: 'PARTIAL', lines: [emptyLine()], partyId: '', financialAccountId: '', commission: '', transport: '' });
  const [keys, setKeys] = useState({ stock: newIdempotencyKey(), harvest: newIdempotencyKey() });
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [r, st, sa, h] = await Promise.all([api<CycleReport>(`${cycle}/report?from=${range.from}&to=${range.to}`), api<Stocking[]>(`${cycle}/stockings`),
        api<Sample[]>(`${cycle}/samples`), api<Harvest[]>(`${cycle}/harvests`)]);
      setReport(r); setStockings(st); setSamples(sa); setHarvests(h);
    } catch (e) {
      setError(describeError(e));
    }
  }, [cycle, range.from, range.to]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    api<Day>(`${cycle}/days/${date}`).then(d => setDay({ feeds: d.feeds.map(f => ({ itemId: String(f.itemId), kg: String(f.kg) })),
      deaths: d.deaths.map(x => ({ key: keyOf(x), count: String(x.count), cause: x.cause ?? '' })), notes: d.notes ?? '' })).catch(e => setError(describeError(e)));
  }, [cycle, date]);
  useEffect(() => {
    if (can(tenant, 'INVENTORY_READ')) api<Item[]>(`/api/v1/tenants/${tenant.id}/stock/items`)
      .then(i => setFeeds(i.filter(x => x.active && x.kind === 'INPUT' && x.dimension === 'MASS'))).catch(() => undefined);
  }, [tenant]);
  useEffect(() => {
    if (!canMoney) return;
    const fin = `/api/v1/tenants/${tenant.id}/finance`;
    Promise.all([api<Party[]>(`${fin}/parties`), api<FinancialAccount[]>(`${fin}/accounts`)])
      .then(([p, a]) => { setParties(p.filter(x => x.active)); setAccounts(a.filter(x => x.active)); }).catch(() => undefined);
  }, [canMoney, tenant.id]);

  /** Sends a change, reloads and then shows the confirmation (reloading clears nothing else). */
  async function send<T>(path: string, method: 'POST' | 'PUT' | 'DELETE', body: unknown, k: keyof typeof keys | null, done: (r: T) => string) {
    setError(''); setMessage('');
    try {
      const result = await api<T>(path, { method, body, headers: k ? { 'Idempotency-Key': keys[k] } : undefined });
      if (k) setKeys(x => ({ ...x, [k]: newIdempotencyKey() }));
      await load();
      setMessage(done(result));
    } catch (e) {
      setError(describeError(e));
    }
  }

  if (!report) return error ? <ErrorBanner message={error} /> : <Loading />;
  const c = report.cycle;
  const open = canWrite && c.status !== 'HARVESTED';
  const stocked = Array.from(new Map(stockings.filter(s => !s.reversed).map(s => [keyOf(s), s])).values());

  function saveDay(e: React.FormEvent) {
    e.preventDefault();
    if (!day) return;
    if (!day.feeds.every(f => f.itemId && kgOk(f.kg)) || !day.deaths.every(d => d.key && whole(d.count))) { setError(t('errors.day')); return; }
    void send<Day>(`${cycle}/days/${date}`, 'PUT', { feeds: day.feeds.map(f => ({ itemId: Number(f.itemId), kg: f.kg.trim() })),
      deaths: day.deaths.map(d => ({ ...fromKey(d.key), count: Number(d.count), cause: d.cause.trim() || null })), notes: day.notes.trim() || null }, null,
    () => t('daySaved', { date: formatDate(date, lang) }));
  }

  function saveHarvest(e: React.FormEvent) {
    e.preventDefault();
    const lines = harvest.lines;
    if (!lines.every(l => l.key && kgOk(l.kg) && (l.count.trim() === '' || whole(l.count)) && (l.use !== 'SOLD' || money(l.rate)))) { setError(t('errors.harvest')); return; }
    const sold = lines.some(l => l.use === 'SOLD');
    if (sold && !harvest.partyId && !harvest.financialAccountId) { setError(t('errors.buyer')); return; }
    if (![harvest.commission, harvest.transport].every(v => v.trim() === '' || money(v))) { setError(t('errors.harvest')); return; }
    if (harvest.kind === 'FINAL' && !window.confirm(t('confirmFinal'))) return;
    const charges = [['MARKET_CHARGES', harvest.commission], ['TRANSPORT', harvest.transport]].filter(([, v]) => v.trim() !== '')
      .map(([category, amount]) => ({ category, amount: amount.trim() }));
    void send<Harvest>(`${cycle}/harvests`, 'POST', { date: harvest.date, kind: harvest.kind, ...(sold ? payer(harvest) : {}), charges,
      lines: lines.map(l => ({ ...fromKey(l.key), grade: l.grade.trim() || null, kg: l.kg.trim(), count: l.count.trim() ? Number(l.count) : null, use: l.use,
        rate: l.use === 'SOLD' ? l.rate.trim() : null })) }, 'harvest',
    () => { setHarvest({ ...harvest, lines: [emptyLine()], commission: '', transport: '' }); return t('harvestSaved'); });
  }

  const setLine = (i: number, p: Partial<LineForm>) => setHarvest(h => ({ ...h, lines: h.lines.map((l, j) => (j === i ? { ...l, ...p } : l)) }));
  return (
    <>
      <div className="crop-page-head">
        <div>
          <p><Link to={`/t/${tenant.id}/aquaculture/ponds/${c.pondId}`}>{c.pondName}</Link></p>
          <h2>{c.name} <small>· {t(`status.${c.status}`)}</small></h2>
          <p>{t('startedOn')} {formatDate(c.startedOn, lang)}{c.stockedOn && ` · ${t('stockedOn', { date: formatDate(c.stockedOn, lang) })}`}
            {c.plannedHarvest && ` · ${t('plannedHarvest')} ${formatDate(c.plannedHarvest, lang)}`}
            {c.harvestedOn && ` · ${t('harvestedOn', { date: formatDate(c.harvestedOn, lang) })}`}</p>
        </div>
      </div>
      <ErrorBanner message={error} />
      {message && <p className="hint" role="status">{message}</p>}

      <div className="form-card form-grid filters">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <FigureCards title={tf('rangeLabel', { from: formatDate(report.period.from, lang), to: formatDate(report.period.to, lang) })} f={report.period} showMoney={showMoney} />
      {report.sinceStocking && <FigureCards title={t('wholeCycle')} f={report.sinceStocking} showMoney={showMoney} />}

      {report.species.length > 0 && (
        <>
          <h3 className="section-space">{t('fishInPond')}</h3>
          <p className="hint">{t('estimateHelp')}</p>
          <table className="data-table">
            <thead><tr><th>{t('speciesLabel')}</th><th className="num">{t('stocked')}</th><th className="num">{t('deathsSeen')}</th><th className="num">{t('harvestedCount')}</th>
              <th className="num">{t('estimatedCount')}</th><th className="num">{t('averageGrams')}</th><th className="num">{t('estimatedKg')}</th></tr></thead>
            <tbody>{report.species.map(s => (
              <tr key={keyOf(s)}>
                <td>{name(s)}</td><td className="num">{formatNumber(s.stocked, lang, 0)}</td><td className="num">{formatNumber(s.deaths, lang, 0)}</td>
                <td className="num">{formatNumber(s.harvestedCount, lang, 0)}</td><td className="num">{formatNumber(s.estimatedCount, lang, 0)}</td>
                <td className="num">{s.averageGrams == null ? '—' : <>{formatNumber(s.averageGrams, lang, 1)}<small> · {formatDate(s.sampledOn, lang)}</small></>}</td>
                <td className="num">{s.estimatedKg == null ? '—' : formatNumber(s.estimatedKg, lang, 1)}</td>
              </tr>
            ))}</tbody>
          </table>
        </>
      )}

      <h3 className="section-space">{t('stockings')}</h3>
      {stockings.length > 0 && (
        <table className="data-table">
          <thead><tr><th>{tf('date')}</th><th>{t('speciesLabel')}</th><th className="num">{t('fishCount')}</th><th className="num">{t('kg')}</th><th>{t('hatchery')}</th></tr></thead>
          <tbody>{stockings.map(s => (
            <tr key={s.id} className={s.reversed ? 'muted' : undefined}>
              <td>{formatDate(s.date, lang)}</td><td>{name(s)}{s.reversed && <small> · {t('reversed')}</small>}
                {s.documentId && <small> · <Link to={`/t/${tenant.id}/finance/records/${s.documentId}`}>{t('bill')}</Link></small>}</td>
              <td className="num">{formatNumber(s.count, lang, 0)}</td><td className="num">{formatNumber(s.kg, lang, 3)}</td><td>{s.hatchery ?? ''}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => {
          e.preventDefault();
          if (!whole(stock.count) || !kgOk(stock.kg) || (stock.species === 'OTHER' && !stock.otherName.trim())) { setError(t('errors.stocking')); return; }
          const bill = stock.amount.trim() !== '';
          if (bill && (!money(stock.amount) || (!stock.partyId && !stock.financialAccountId))) { setError(t('errors.seedBill')); return; }
          void send<Stocking>(`${cycle}/stockings`, 'POST', { date: stock.date, species: stock.species, otherName: stock.species === 'OTHER' ? stock.otherName.trim() : null,
            count: Number(stock.count), kg: stock.kg.trim(), hatchery: stock.hatchery.trim() || null,
            bill: bill ? { amount: stock.amount.trim(), ...payer(stock) } : null }, 'stock',
          () => { setStock({ ...stock, count: '', kg: '', amount: '' }); return t('stockingSaved'); });
        }}>
          <label>{tf('date')}<input type="date" max={today()} value={stock.date} onChange={e => setStock({ ...stock, date: e.target.value })} /></label>
          <label>{t('speciesLabel')}<select value={stock.species} onChange={e => setStock({ ...stock, species: e.target.value })}>
            {SPECIES.map(s => <option key={s} value={s}>{t(`species.${s}`)}</option>)}</select></label>
          {stock.species === 'OTHER' && <label>{t('otherName')}<input maxLength={60} value={stock.otherName} onChange={e => setStock({ ...stock, otherName: e.target.value })} /></label>}
          <label>{t('fishCount')}<input inputMode="numeric" value={stock.count} onChange={e => setStock({ ...stock, count: e.target.value })} /></label>
          <label>{t('totalKg')}<input inputMode="decimal" value={stock.kg} onChange={e => setStock({ ...stock, kg: e.target.value })} /></label>
          <label>{t('hatchery')} <small>{tc('optional')}</small><input maxLength={120} value={stock.hatchery} onChange={e => setStock({ ...stock, hatchery: e.target.value })} /></label>
          {canMoney && <>
            <label>{t('seedBill')} <small>{tc('optional')}</small><input inputMode="decimal" value={stock.amount} onChange={e => setStock({ ...stock, amount: e.target.value })} /></label>
            {stock.amount.trim() !== '' && <PayerFields f={stock} set={p => setStock({ ...stock, ...p })} parties={parties} accounts={accounts}
                                                        partyLabel={t('hatcheryParty')} accountLabel={tf('paidFrom')} />}
          </>}
          <p className="hint full">{t('stockingHelp')}</p>
          <div className="form-actions full"><button className="secondary">{t('recordStocking')}</button></div>
        </form>
      )}

      {c.stockedOn && day && (
        <>
          <h3 className="section-space">{t('dailyRecord')}</h3>
          <form className="form-card form-grid" noValidate onSubmit={saveDay}>
            <label>{tf('date')}<input type="date" max={today()} min={c.startedOn} value={date} onChange={e => setDate(e.target.value)} /></label>
            {day.feeds.map((f, i) => (
              <div key={`f${i}`} className="inline-fields full">
                <label>{t('feed')}<select disabled={!open} value={f.itemId} onChange={e => setDay({ ...day, feeds: day.feeds.map((x, j) => (j === i ? { ...x, itemId: e.target.value } : x)) })}>
                  <option value="">—</option>{feeds.map(it => <option key={it.id} value={it.id}>{it.name}</option>)}</select></label>
                <label>{t('kg')}<input inputMode="decimal" disabled={!open} value={f.kg} onChange={e => setDay({ ...day, feeds: day.feeds.map((x, j) => (j === i ? { ...x, kg: e.target.value } : x)) })} /></label>
                {open && <button type="button" className="icon-button" aria-label={t('removeLine')} onClick={() => setDay({ ...day, feeds: day.feeds.filter((_, j) => j !== i) })}><Trash2 size={16} aria-hidden /></button>}
              </div>
            ))}
            {day.deaths.map((d, i) => (
              <div key={`d${i}`} className="inline-fields full">
                <label>{t('deathsOf')}<select disabled={!open} value={d.key} onChange={e => setDay({ ...day, deaths: day.deaths.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)) })}>
                  <option value="">—</option>{stocked.map(s => <option key={keyOf(s)} value={keyOf(s)}>{name(s)}</option>)}</select></label>
                <label>{t('fishCount')}<input inputMode="numeric" disabled={!open} value={d.count} onChange={e => setDay({ ...day, deaths: day.deaths.map((x, j) => (j === i ? { ...x, count: e.target.value } : x)) })} /></label>
                <label>{t('cause')} <small>{tc('optional')}</small><input maxLength={120} disabled={!open} value={d.cause}
                  onChange={e => setDay({ ...day, deaths: day.deaths.map((x, j) => (j === i ? { ...x, cause: e.target.value } : x)) })} /></label>
                {open && <button type="button" className="icon-button" aria-label={t('removeLine')} onClick={() => setDay({ ...day, deaths: day.deaths.filter((_, j) => j !== i) })}><Trash2 size={16} aria-hidden /></button>}
              </div>
            ))}
            {open && <div className="form-actions full">
              <button type="button" className="secondary" onClick={() => setDay({ ...day, feeds: [...day.feeds, { itemId: feeds.length === 1 ? String(feeds[0].id) : '', kg: '' }] })}>
                <Plus size={14} aria-hidden />{t('addFeed')}</button>
              <button type="button" className="secondary" onClick={() => setDay({ ...day, deaths: [...day.deaths, { key: stocked.length === 1 ? keyOf(stocked[0]) : '', count: '', cause: '' }] })}>
                <Plus size={14} aria-hidden />{t('addDeaths')}</button>
            </div>}
            <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={300} disabled={!open} value={day.notes} onChange={e => setDay({ ...day, notes: e.target.value })} /></label>
            <p className="hint full">{t('dailyRecordHelp')}</p>
            {open && <div className="form-actions full"><button className="primary">{t('saveDay')}</button></div>}
          </form>
        </>
      )}

      {c.stockedOn && (
        <>
          <h3 className="section-space">{t('samples')}</h3>
          {samples.length > 0 && (
            <table className="data-table">
              <thead><tr><th>{tf('date')}</th><th>{t('speciesLabel')}</th><th className="num">{t('fishWeighed')}</th><th className="num">{t('kg')}</th><th className="num">{t('averageGrams')}</th><th /></tr></thead>
              <tbody>{samples.map(s => (
                <tr key={s.id}>
                  <td>{formatDate(s.date, lang)}</td><td>{name(s)}</td><td className="num">{s.fishWeighed}</td><td className="num">{formatNumber(s.kg, lang, 3)}</td>
                  <td className="num">{formatNumber(s.averageGrams, lang, 1)}</td>
                  <td>{open && <button type="button" className="icon-button" aria-label={t('removeSample')}
                    onClick={() => { if (window.confirm(t('confirmRemoveSample'))) void send(`${base}/samples/${s.id}`, 'DELETE', undefined, null, () => t('sampleRemoved')); }}>
                    <Trash2 size={16} aria-hidden /></button>}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
          {open && (
            <form className="form-card form-grid" noValidate onSubmit={e => {
              e.preventDefault();
              if (!whole(sample.fishWeighed) || !kgOk(sample.kg)) { setError(t('errors.sample')); return; }
              void send<Sample>(`${cycle}/samples`, 'POST', { date: sample.date, ...(sample.key ? fromKey(sample.key) : { species: null, otherName: null }),
                fishWeighed: Number(sample.fishWeighed), kg: sample.kg.trim() }, null,
              s => { setSample({ ...sample, fishWeighed: '', kg: '' }); return t('sampleSaved', { grams: formatNumber(s.averageGrams, lang, 1) }); });
            }}>
              <label>{tf('date')}<input type="date" max={today()} value={sample.date} onChange={e => setSample({ ...sample, date: e.target.value })} /></label>
              <label>{t('speciesLabel')}<select value={sample.key} onChange={e => setSample({ ...sample, key: e.target.value })}>
                <option value="">{t('mixedSample')}</option>{stocked.map(s => <option key={keyOf(s)} value={keyOf(s)}>{name(s)}</option>)}</select></label>
              <label>{t('fishWeighed')}<input inputMode="numeric" value={sample.fishWeighed} onChange={e => setSample({ ...sample, fishWeighed: e.target.value })} /></label>
              <label>{t('totalKg')}<input inputMode="decimal" value={sample.kg} onChange={e => setSample({ ...sample, kg: e.target.value })} /></label>
              <div className="form-actions full"><button className="secondary">{t('recordSample')}</button></div>
            </form>
          )}

          <h3 className="section-space">{t('harvests')}</h3>
          {harvests.length === 0 ? <p className="hint">{t('noHarvests')}</p> : harvests.map(h => (
            <div key={h.id} className={`form-card${h.reversed ? ' muted' : ''}`}>
              <p><b>{formatDate(h.date, lang)} · {t(`harvestKind.${h.kind}`)}</b>{h.reversed && <small> · {t('reversed')}</small>}
                {h.saleDocumentId && <small> · <Link to={`/t/${tenant.id}/finance/records/${h.saleDocumentId}`}>{t('sale')}</Link></small>}
                {h.chargesDocumentId && <small> · <Link to={`/t/${tenant.id}/finance/records/${h.chargesDocumentId}`}>{t('charges')}</Link></small>}
                {open && (!h.saleDocumentId || h.reversed) && <button type="button" className="icon-button" aria-label={t('removeHarvest')}
                  onClick={() => { if (window.confirm(t('confirmRemoveHarvest'))) void send(`${base}/harvests/${h.id}`, 'DELETE', undefined, null, () => t('harvestRemoved')); }}>
                  <Trash2 size={16} aria-hidden /></button>}</p>
              <table className="data-table">
                <thead><tr><th>{t('speciesLabel')}</th><th>{t('grade')}</th><th className="num">{t('kg')}</th><th className="num">{t('fishCount')}</th><th>{t('use')}</th>
                  <th className="num">{t('ratePerKg')}</th><th className="num">{tf('amount')}</th></tr></thead>
                <tbody>{h.lines.map((l, i) => (
                  <tr key={i}><td>{name(l)}</td><td>{l.grade ?? ''}</td><td className="num">{formatNumber(l.kg, lang, 3)}</td><td className="num">{l.count ?? '—'}</td>
                    <td>{t(`useKind.${l.use}`)}</td><td className="num">{l.rate == null ? '' : formatMoney(l.rate, lang)}</td><td className="num">{l.amount == null ? '' : formatMoney(l.amount, lang)}</td></tr>
                ))}</tbody>
              </table>
            </div>
          ))}
          {open && (
            <form className="form-card form-grid" noValidate onSubmit={saveHarvest}>
              <h4 className="full">{t('recordHarvest')}</h4>
              <label>{tf('date')}<input type="date" max={today()} value={harvest.date} onChange={e => setHarvest({ ...harvest, date: e.target.value })} /></label>
              <label>{t('harvestKindLabel')}<select value={harvest.kind} onChange={e => setHarvest({ ...harvest, kind: e.target.value })}>
                <option value="PARTIAL">{t('harvestKind.PARTIAL')}</option><option value="FINAL">{t('harvestKind.FINAL')}</option></select></label>
              {harvest.lines.map((l, i) => (
                <div key={i} className="inline-fields full">
                  <label>{t('speciesLabel')}<select value={l.key} onChange={e => setLine(i, { key: e.target.value })}>
                    <option value="">—</option>{stocked.map(s => <option key={keyOf(s)} value={keyOf(s)}>{name(s)}</option>)}</select></label>
                  <label>{t('grade')} <small>{tc('optional')}</small><input maxLength={60} placeholder={t('gradeExample')} value={l.grade} onChange={e => setLine(i, { grade: e.target.value })} /></label>
                  <label>{t('kg')}<input inputMode="decimal" value={l.kg} onChange={e => setLine(i, { kg: e.target.value })} /></label>
                  <label>{t('fishCount')} <small>{tc('optional')}</small><input inputMode="numeric" value={l.count} onChange={e => setLine(i, { count: e.target.value })} /></label>
                  <label>{t('use')}<select value={l.use} onChange={e => setLine(i, { use: e.target.value as LineForm['use'] })}>
                    {(['SOLD', 'HOME', 'GIVEN'] as const).map(u => <option key={u} value={u}>{t(`useKind.${u}`)}</option>)}</select></label>
                  {l.use === 'SOLD' && <label>{t('ratePerKg')}<input inputMode="decimal" value={l.rate} onChange={e => setLine(i, { rate: e.target.value })} /></label>}
                  {harvest.lines.length > 1 && <button type="button" className="icon-button" aria-label={t('removeLine')}
                    onClick={() => setHarvest({ ...harvest, lines: harvest.lines.filter((_, j) => j !== i) })}><Trash2 size={16} aria-hidden /></button>}
                </div>
              ))}
              <div className="form-actions full"><button type="button" className="secondary" onClick={() => setHarvest({ ...harvest, lines: [...harvest.lines, emptyLine(harvest.lines.at(-1)?.key)] })}>
                <Plus size={14} aria-hidden />{t('addGrade')}</button></div>
              {harvest.lines.some(l => l.use === 'SOLD') && <>
                {canMoney ? <PayerFields f={harvest} set={p => setHarvest({ ...harvest, ...p })} parties={parties} accounts={accounts}
                                         partyLabel={t('buyer')} accountLabel={tf('receivedInto')} /> : <p className="hint full">{t('saleNeedsFinance')}</p>}
                {canMoney && <>
                  <label>{t('commission')} <small>{tc('optional')}</small><input inputMode="decimal" value={harvest.commission} onChange={e => setHarvest({ ...harvest, commission: e.target.value })} /></label>
                  <label>{t('transport')} <small>{tc('optional')}</small><input inputMode="decimal" value={harvest.transport} onChange={e => setHarvest({ ...harvest, transport: e.target.value })} /></label>
                </>}
              </>}
              <p className="hint full">{t('harvestHelp')}</p>
              <div className="form-actions full"><button className="primary">{t('recordHarvest')}</button></div>
            </form>
          )}
        </>
      )}
    </>
  );
}

/** Who paid or was paid: a party (on credit) or, without one, the cash or bank account. */
function PayerFields({ f, set, parties, accounts, partyLabel, accountLabel }: { f: Payer; set: (p: Partial<Payer>) => void; parties: Party[];
  accounts: FinancialAccount[]; partyLabel: string; accountLabel: string }) {
  const tc = useNs('common');
  return <>
    <label>{partyLabel} <small>{tc('optional')}</small><select value={f.partyId} onChange={e => set({ partyId: e.target.value })}>
      <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
    {!f.partyId && <label>{accountLabel}<select value={f.financialAccountId} onChange={e => set({ financialAccountId: e.target.value })}>
      <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
  </>;
}

/** The cycle's figures for a period (K9); estimates are labelled, money only with FINANCE_READ. */
function FigureCards({ title, f, showMoney }: { title: string; f: Figures; showMoney: boolean }) {
  const t = useNs('aquaculture');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const n = (v: number | null, d = 2) => (v == null ? '—' : formatNumber(v, lang, d));
  const m = (v: number | null) => (v == null ? '—' : formatMoney(v, lang));
  return (
    <>
      <h3 className="section-space">{title}</h3>
      <div className="finance-stats">
        <div className="finance-stat"><span>{t('stocked')}</span><b>{n(f.stockedCount, 0)}</b><small>{n(f.stockedKg, 3)} {t('kg')}
          {f.daysOfCulture != null && ` · ${t('daysOfCultureValue', { days: String(f.daysOfCulture) })}`}</small></div>
        <div className="finance-stat"><span>{t('harvestedKg')}</span><b>{n(f.harvestedKg, 1)}</b>
          <small>{t('yieldPerAcre')} {n(f.yieldPerAcre, 1)}{f.harvestedCount != null && ` · ${t('fishCountValue', { count: n(f.harvestedCount, 0) })}`}</small></div>
        <div className="finance-stat"><span>{t('survival')}</span><b>{f.survivalPercent == null ? '—' : `${n(f.survivalPercent, 1)}%`}</b>
          <small>{t('deathsSeen')} {n(f.deaths, 0)}{f.unseenLosses != null && ` · ${t('unseenLosses')} ${n(f.unseenLosses, 0)}`}</small></div>
        <div className="finance-stat"><span>{t('feedKg')}</span><b>{n(f.feedKg, 1)}</b><small>{t('fcr')} {n(f.fcr)}</small></div>
        {f.estimatedKg != null && <div className="finance-stat"><span>{t('estimatedKg')}</span><b>{n(f.estimatedKg, 1)}</b>
          <small>{t('estimatedFcr')} {n(f.estimatedFcr)}{f.estimatedOn && ` · ${t('sampledOn', { date: formatDate(f.estimatedOn, lang) })}`}</small></div>}
        {showMoney && <div className="finance-stat"><span>{t('profit')}</span><b>{m(f.profit)}</b><small>{tf('revenue')} {m(f.revenue)} · {tf('costs')} {m(f.costs)}</small></div>}
        {showMoney && <div className="finance-stat"><span>{t('costPerKg')}</span><b>{m(f.costPerKg)}</b>
          <small>{t('profitPerAcre')} {m(f.profitPerAcre)} · {t('averagePrice')} {m(f.averagePrice)}</small></div>}
      </div>
    </>
  );
}
