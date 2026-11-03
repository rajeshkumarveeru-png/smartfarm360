import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { RotateCcw, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner, Loading } from '../components/common';
import type { FinancialAccount, Party } from '../finance/types';
import RecordsPanel from '../finance/RecordsPanel';
import VarietyLines, { BIRD_SPECIES, emptyVariety, type VarietyRow, varietyLines, varietyProblem } from './VarietyLines';
import { newIdempotencyKey } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError, isStale } from '../lib/errors';
import { formatDate, formatMoney, formatNumber, today } from '../lib/format';
import { can } from '../lib/nav';
import type { Item } from '../stock/types';
import type { RecordRefLike } from './milkTypes';
import type { BatchDay, DayLineView, Figures, FlockGroup, FlockLine, FlockReport, Movement, PlanItem } from './types';
import { HEALTH_KINDS } from './types';

const whole = (v: string) => v === '' || /^\d{1,7}$/.test(v.trim());
const decimal = (v: string) => v === '' || /^\d{1,9}(\.\d{1,3})?$/.test(v.trim());
const money = (v: string) => /^\d{1,12}(\.\d{1,2})?$/.test(v.trim());
const num = (v: string) => (v.trim() === '' ? null : Number(v));
const str = (v: number | null | undefined) => (v == null || v === 0 ? '' : String(v));
const monthStart = () => `${today().slice(0, 7)}-01`;
/** A variety on the day's form: deaths, culls, eggs laid and the rate for an egg (bin and batch varieties design, V5). */
type DayLineForm = { lineId: number; label: string; heads: number; deaths: string; culls: string; eggs: string; rate: string };
type DayForm = { deaths: string; deathCause: string; culls: string; eggsGood: string; eggsCracked: string; eggsBroken: string; feedItemId: string; feedKg: string;
  weightBirds: string; weightKg: string; notes: string; lines: DayLineForm[] };
const toForm = (d: BatchDay, label: (l: DayLineView) => string): DayForm => ({ deaths: str(d.deaths), deathCause: d.deathCause ?? '', culls: str(d.culls), eggsGood: str(d.eggsGood),
  eggsCracked: str(d.eggsCracked), eggsBroken: str(d.eggsBroken), feedItemId: d.feedItemId ? String(d.feedItemId) : '', feedKg: str(d.feedKg),
  weightBirds: str(d.weightBirds), weightKg: str(d.weightKg), notes: d.notes ?? '',
  lines: (d.lines ?? []).map(l => ({ lineId: l.lineId, label: label(l), heads: l.heads, deaths: str(l.deaths), culls: str(l.culls), eggs: str(l.eggs), rate: str(l.eggRate) })) });

/** One poultry batch or goat and sheep herd: figures (J8), the daily record (J4), head movements (J7), sales (J6), eggs (J5) and the health plan (J9). */
type FlockTab = 'summary' | 'daily' | 'heads' | 'money' | 'health';

export default function FlockPage() {
  const { groupId } = useParams();
  const tenant = useTenant();
  const t = useNs('livestock');
  const tc = useNs('common');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/livestock`;
  const flock = `${base}/flocks/${groupId}`;
  const canWrite = can(tenant, 'LIVESTOCK_WRITE');
  const canMoney = canWrite && can(tenant, 'FINANCE_WRITE');
  const showMoney = can(tenant, 'FINANCE_READ');
  const canReverse = canWrite && can(tenant, 'FINANCE_REVERSE');
  const varietyLabel = useCallback((l: { variety: string | null; species: string }) => (l.variety ? `${l.variety} · ${t(`species.${l.species}`)}` : t(`species.${l.species}`)), [t]);
  // The day loads with the latest labels, without reloading when the translator changes
  const labelRef = useRef(varietyLabel);
  labelRef.current = varietyLabel;
  const [range, setRange] = useState({ from: monthStart(), to: today() });
  const [report, setReport] = useState<FlockReport | null>(null);
  const [moves, setMoves] = useState<Movement[]>([]);
  const [plan, setPlan] = useState<PlanItem[]>([]);
  const [others, setOthers] = useState<FlockGroup[]>([]);
  const [feeds, setFeeds] = useState<Item[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [accounts, setAccounts] = useState<FinancialAccount[]>([]);
  const [date, setDate] = useState(today());
  const [day, setDay] = useState<BatchDay | null>(null);
  const [tab, setTab] = useState<FlockTab>('summary');
  const navigate = useNavigate();
  const [dayForm, setDayForm] = useState<DayForm | null>(null);
  const [dayLoads, setDayLoads] = useState(0);
  const [stale, setStale] = useState(false);
  const [move, setMove] = useState({ date: today(), kind: 'DIED', heads: '', toGroupId: '', countedHeads: '', cause: '', lineId: '', newSpecies: 'CHICKEN', newVariety: '' });
  const [buy, setBuy] = useState({ date: today(), partyId: '', financialAccountId: '', rows: [emptyVariety('CHICKEN')] as VarietyRow[] });
  const [sale, setSale] = useState({ date: today(), heads: '', kg: '', rateBasis: 'KG', rate: '', partyId: '', financialAccountId: '', lineId: '' });
  const [lines, setLines] = useState<FlockLine[]>([]);
  const [birth, setBirth] = useState({ date: today(), alive: '', dead: '' });
  const [eggUse, setEggUse] = useState({ date: today(), homeUse: '', damaged: '', reason: '' });
  const [eggSale, setEggSale] = useState({ date: today(), quantity: '', unit: 'tray', amount: '', partyId: '', financialAccountId: '' });
  const [planForm, setPlanForm] = useState({ kind: 'VACCINATION', description: '', dueOn: today(), copyFrom: '' });
  const [keys, setKeys] = useState({ buy: newIdempotencyKey(), sale: newIdempotencyKey(), eggUse: newIdempotencyKey(), eggSale: newIdempotencyKey(), health: newIdempotencyKey() });
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const group = report?.group;
  const several = lines.length > 1;
  const poultry = group?.species === 'CHICKEN';
  const layers = poultry && group?.purpose !== 'BROILER';

  const load = useCallback(async () => {
    setError(''); setMessage('');
    try {
      const [r, m, p, all, ls] = await Promise.all([api<FlockReport>(`${flock}/report?from=${range.from}&to=${range.to}`), api<Movement[]>(`${flock}/movements`),
        api<PlanItem[]>(`${flock}/plan`), api<FlockGroup[]>(`${base}/flocks`), api<FlockLine[]>(`${flock}/lines`)]);
      setReport(r); setMoves(m); setPlan(p); setOthers(all.filter(g => g.id !== r.group.id && g.species === r.group.species)); setLines(ls ?? []);
    } catch (e) {
      setError(describeError(e));
    }
  }, [base, flock, range.from, range.to]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!poultry) return;
    setStale(false);
    api<BatchDay>(`${flock}/days/${date}`).then(d => { setDay(d); setDayForm(toForm(d, labelRef.current)); }).catch(e => setError(describeError(e)));
  }, [flock, date, poultry, dayLoads]);
  useEffect(() => {
    if (poultry && can(tenant, 'INVENTORY_READ')) api<Item[]>(`/api/v1/tenants/${tenant.id}/stock/items`)
      .then(i => setFeeds(i.filter(x => x.active && x.kind === 'INPUT' && x.dimension === 'MASS'))).catch(() => undefined);
  }, [poultry, tenant]);
  useEffect(() => {
    if (!canMoney) return;
    const fin = `/api/v1/tenants/${tenant.id}/finance`;
    Promise.all([api<Party[]>(`${fin}/parties`), api<FinancialAccount[]>(`${fin}/accounts`)])
      .then(([p, a]) => { setParties(p.filter(x => x.active)); setAccounts(a.filter(x => x.active)); }).catch(() => undefined);
  }, [canMoney, tenant.id]);

  /** Sends a change, reloads and then shows the confirmation (reloading clears messages). */
  async function send<T>(path: string, method: 'POST' | 'PUT' | 'DELETE', body: unknown, k: keyof typeof keys | null, done: (r: T) => string) {
    setError(''); setMessage('');
    try {
      const result = await api<T>(path, { method, body, headers: k ? { 'Idempotency-Key': keys[k] } : undefined });
      if (k) setKeys(x => ({ ...x, [k]: newIdempotencyKey() }));
      await load();
      setMessage(done(result));
    } catch (e) {
      setError(describeError(e));
      setStale(isStale(e));
    }
  }
  const payer = (f: { partyId: string; financialAccountId: string }) => ({ partyId: f.partyId ? Number(f.partyId) : null,
    financialAccountId: !f.partyId && f.financialAccountId ? Number(f.financialAccountId) : null });
  const payerOk = (f: { partyId: string; financialAccountId: string }) => Boolean(f.partyId || f.financialAccountId);
  if (!report || !group) return error ? <ErrorBanner message={error} /> : <Loading />;
  const open = canWrite && !group.closed;
  const listPath = `/t/${tenant.id}/livestock/${poultry ? 'poultry' : 'small-stock'}`;

  function saveDay(e: React.FormEvent) {
    e.preventDefault();
    if (!dayForm) return;
    const f = dayForm;
    const byLine = f.lines.length > 0;
    if (![f.deaths, f.culls, f.eggsGood, f.eggsCracked, f.eggsBroken, f.weightBirds, ...f.lines.flatMap(l => [l.deaths, l.culls, l.eggs])].every(whole)
        || ![f.feedKg, f.weightKg].every(decimal)) { setError(t('errors.wholeNumbers')); return; }
    if (!f.lines.every(l => !l.rate || /^\d{1,8}(\.\d{1,2})?$/.test(l.rate))) { setError(t('errors.eggRate')); return; }
    if (Boolean(f.feedItemId) !== Boolean(f.feedKg)) { setError(t('errors.feed')); return; }
    // By variety, the server adds up deaths, culls and eggs; good eggs are those laid less the cracked and broken
    void send<BatchDay>(`${flock}/days/${date}`, 'PUT', { deaths: byLine ? null : num(f.deaths), deathCause: f.deathCause || null, culls: byLine ? null : num(f.culls),
      eggsGood: byLine ? null : num(f.eggsGood), eggsCracked: num(f.eggsCracked), eggsBroken: num(f.eggsBroken), feedItemId: f.feedItemId ? Number(f.feedItemId) : null,
      feedKg: f.feedKg || null, weightBirds: num(f.weightBirds), weightKg: f.weightKg || null, notes: f.notes || null, baseVersion: day?.version ?? null,
      lines: byLine ? f.lines.map(l => ({ lineId: l.lineId, deaths: num(l.deaths), culls: num(l.culls), eggs: layers ? num(l.eggs) : 0, eggRate: layers && l.rate ? l.rate : null })) : null }, null,
    d => { setDay(d); setDayForm(toForm(d, varietyLabel)); return t('dayRecordSaved', { date: formatDate(date, lang) }); });
  }

  function saveMove(e: React.FormEvent) {
    e.preventDefault();
    const counting = move.kind === 'COUNT';
    if (counting ? !/^\d{1,7}$/.test(move.countedHeads) : !/^\d{1,7}$/.test(move.heads) || Number(move.heads) <= 0) { setError(t('errors.heads')); return; }
    if (move.kind === 'MOVED' && !move.toGroupId) { setError(t('errors.toGroup')); return; }
    // A count correction keeps a note of why the number changed
    if (counting && !move.cause.trim()) { setError(t('errors.countReason')); return; }
    const newVariety = move.kind === 'RECEIVED' && move.lineId === 'new';
    if (newVariety && !move.newVariety.trim()) { setError(t('errors.varietyName')); return; }
    if (several && !move.lineId) { setError(t('errors.chooseVariety')); return; }
    void send<Movement[]>(`${flock}/movements`, 'POST', { date: move.date, kind: move.kind, heads: counting ? null : Number(move.heads),
      countedHeads: counting ? Number(move.countedHeads) : null, toGroupId: move.kind === 'MOVED' ? Number(move.toGroupId) : null, cause: move.cause || null,
      lineId: move.lineId && !newVariety ? Number(move.lineId) : null, species: newVariety ? move.newSpecies : null, variety: newVariety ? move.newVariety.trim() : null }, null,
    () => { setMove({ ...move, heads: '', countedHeads: '', cause: '', newVariety: '' }); return t('headsRecorded'); });
  }

  /** Moves the batch to the Bin; money already recorded for it stays in the accounts. */
  async function deleteGroup() {
    if (!group || !window.confirm(t('confirmDeleteGroup', { name: group.name }))) return;
    try {
      await api(`/api/v1/tenants/${tenant.id}/livestock/groups/${group.id}`, { method: 'DELETE' });
      navigate(listPath);
    } catch (e) {
      setError(describeError(e));
    }
  }

  const kinds = ['DIED', 'CULLED', 'RECEIVED', 'MOVED', 'COUNT', ...(poultry ? [] : ['BORN'])];
  // One part of the batch per tab, so the page stays short
  const tabs: FlockTab[] = ['summary', ...(poultry ? ['daily' as const] : []), 'heads', ...(canMoney || (layers && canWrite) ? ['money' as const] : []), 'health'];
  const tabLabel = (x: FlockTab) => ({ summary: t('tabSummary'), daily: t('tabDaily'), heads: t(poultry ? 'tabBirds' : 'tabAnimals'),
    money: t('tabMoney'), health: t('healthPlan') })[x];
  return (
    <>
      <div className="crop-page-head">
        <div>
          <p><Link to={listPath}>{t(poultry ? 'poultry' : 'smallStock')}</Link></p>
          <h2>{group.name}{group.closed && <small> · {t('closed')}</small>}</h2>
          {canWrite && <button type="button" className="danger flock-delete" onClick={() => void deleteGroup()}><Trash2 size={15} aria-hidden />{t(poultry ? 'deleteBatch' : 'deleteHerd')}</button>}
          <p>{t(`species.${group.species}`)} · {t(`purpose.${group.purpose}`)}{group.placedOn && <> · {t(poultry ? 'placedOn' : 'startedOn')} {formatDate(group.placedOn, lang)}</>}
            {group.strain && <> · {group.strain}</>} · <b>{t('headsNow', { count: formatNumber(report.heads, lang, 0) })}</b></p>
        </div>
      </div>
      <ErrorBanner message={error} onRetry={stale ? () => { setError(''); setDayLoads(n => n + 1); } : undefined} retryLabel={tc('loadLatest')} />
      {message && <p className="hint" role="status">{message}</p>}

      <div className="crop-tabs section-space" role="tablist" aria-label={group.name}>
        {tabs.map(x => (
          <button key={x} type="button" role="tab" aria-selected={tab === x} className={tab === x ? 'active' : ''} onClick={() => setTab(x)}>{tabLabel(x)}</button>
        ))}
      </div>

      {tab === 'summary' && <>
      <div className="form-card form-grid filters">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => setRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => setRange({ ...range, to: e.target.value })} /></label>
      </div>
      <FigureCards title={tf('rangeLabel', { from: formatDate(report.period.from, lang), to: formatDate(report.period.to, lang) })} f={report.period}
                   poultry={poultry} layers={layers} showMoney={showMoney} />
      {report.sincePlacement && <FigureCards title={t('sincePlacement')} f={report.sincePlacement} poultry={poultry} layers={layers} showMoney={showMoney} />}
      </>}

      {tab === 'daily' && poultry && dayForm && day && (
        <>
          <h3 className="section-space">{t('dailyRecord')}</h3>
          <form className="form-card form-grid" noValidate onSubmit={saveDay}>
            <label>{tf('date')}<input type="date" max={today()} value={date} onChange={e => setDate(e.target.value)} /></label>
            <p className="hint">{t('headsAtEnd', { count: formatNumber(day.headsAtEnd, lang, 0) })}</p>
            {day.warnings.map(w => <p key={w} className="error-banner full" role="alert">{t(`warning.${w}`)}</p>)}
            {dayForm.lines.length > 0 ? (
              <div className="full day-lines">
                <div className={`day-line day-line-head${layers ? ' with-eggs' : ''}`} aria-hidden>
                  <span>{t('variety')}</span><span>{t('deaths')}</span><span>{t('culls')}</span>{layers && <><span>{t('eggsLaid')}</span><span>{t('eggRate')}</span></>}
                </div>
                {dayForm.lines.map((l, i) => {
                  const put = (patch: Partial<DayLineForm>) => setDayForm({ ...dayForm, lines: dayForm.lines.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
                  return (
                    <div key={l.lineId} className={`day-line${layers ? ' with-eggs' : ''}`} role="group" aria-label={l.label}>
                      <span className="day-line-name"><strong>{l.label}</strong><small>{t('headsAtEnd', { count: formatNumber(l.heads, lang, 0) })}</small></span>
                      <label><span className="sr-only">{t('deaths')}</span><input inputMode="numeric" disabled={!open} aria-label={`${t('deaths')} ${l.label}`} value={l.deaths} onChange={e => put({ deaths: e.target.value })} /></label>
                      <label><span className="sr-only">{t('culls')}</span><input inputMode="numeric" disabled={!open} aria-label={`${t('culls')} ${l.label}`} value={l.culls} onChange={e => put({ culls: e.target.value })} /></label>
                      {layers && <>
                        <label><span className="sr-only">{t('eggsLaid')}</span><input inputMode="numeric" disabled={!open} aria-label={`${t('eggsLaid')} ${l.label}`} value={l.eggs} onChange={e => put({ eggs: e.target.value })} /></label>
                        <label><span className="sr-only">{t('eggRate')}</span><input inputMode="decimal" disabled={!open} aria-label={`${t('eggRate')} ${l.label}`} placeholder="₹" value={l.rate} onChange={e => put({ rate: e.target.value })} /></label>
                      </>}
                    </div>
                  );
                })}
                {layers && <p className="hint">{t('eggRateHelp')}</p>}
              </div>
            ) : <>
              <label>{t('deaths')}<input inputMode="numeric" disabled={!open} value={dayForm.deaths} onChange={e => setDayForm({ ...dayForm, deaths: e.target.value })} /></label>
              <label>{t('culls')}<input inputMode="numeric" disabled={!open} value={dayForm.culls} onChange={e => setDayForm({ ...dayForm, culls: e.target.value })} /></label>
            </>}
            <label>{t('deathCause')} <small>{tc('optional')}</small><input maxLength={120} disabled={!open} value={dayForm.deathCause} onChange={e => setDayForm({ ...dayForm, deathCause: e.target.value })} /></label>
            {layers && <>
              {dayForm.lines.length === 0 && <label>{t('eggsGood')}<input inputMode="numeric" disabled={!open} value={dayForm.eggsGood} onChange={e => setDayForm({ ...dayForm, eggsGood: e.target.value })} /></label>}
              <label>{t('eggsCracked')}<input inputMode="numeric" disabled={!open} value={dayForm.eggsCracked} onChange={e => setDayForm({ ...dayForm, eggsCracked: e.target.value })} /></label>
              <label>{t('eggsBroken')}<input inputMode="numeric" disabled={!open} value={dayForm.eggsBroken} onChange={e => setDayForm({ ...dayForm, eggsBroken: e.target.value })} /></label>
            </>}
            <label>{t('feed')}<select disabled={!open} value={dayForm.feedItemId} onChange={e => setDayForm({ ...dayForm, feedItemId: e.target.value })}>
              <option value="">—</option>{feeds.map(i => <option key={i.id} value={i.id}>{i.name}</option>)}</select></label>
            <label>{t('feedKg')}<input inputMode="decimal" disabled={!open} value={dayForm.feedKg} onChange={e => setDayForm({ ...dayForm, feedKg: e.target.value })} /></label>
            <label>{t('weightBirds')} <small>{tc('optional')}</small><input inputMode="numeric" disabled={!open} value={dayForm.weightBirds} onChange={e => setDayForm({ ...dayForm, weightBirds: e.target.value })} /></label>
            <label>{t('weightKg')} <small>{tc('optional')}</small><input inputMode="decimal" disabled={!open} value={dayForm.weightKg} onChange={e => setDayForm({ ...dayForm, weightKg: e.target.value })} /></label>
            <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={300} disabled={!open} value={dayForm.notes} onChange={e => setDayForm({ ...dayForm, notes: e.target.value })} /></label>
            <p className="hint full">{t('dailyRecordHelp')}</p>
            {open && <div className="form-actions full"><button className="primary">{t('saveDay')}</button></div>}
          </form>
        </>
      )}

      {tab === 'heads' && <>
      {lines.length > 0 && <>
        <h3 className="section-space">{t('varieties')}</h3>
        <div className="variety-cards">
          {lines.map(l => <div key={l.id} className="variety-card"><span>{t(`species.${l.species}`)}</span><strong>{l.variety ?? t(`species.${l.species}`)}</strong>
            <b>{formatNumber(l.heads, lang, 0)}</b><small>{t('headsNowShort')}</small></div>)}
        </div>
      </>}
      <h3 className="section-space">{t('headMovements')}</h3>
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={saveMove}>
          <label>{tf('date')}<input type="date" max={today()} value={move.date} onChange={e => setMove({ ...move, date: e.target.value })} /></label>
          <label>{t('whatHappened')}<select value={move.kind} onChange={e => setMove({ ...move, kind: e.target.value })}>
            {kinds.map(k => <option key={k} value={k}>{t(`moveKind.${k}`)}</option>)}</select></label>
          {(several || move.kind === 'RECEIVED') && <label>{t('variety')}<select value={move.lineId} onChange={e => setMove({ ...move, lineId: e.target.value })}>
            <option value="">—</option>{lines.map(l => <option key={l.id} value={l.id}>{varietyLabel(l)} ({formatNumber(l.heads, lang, 0)})</option>)}
            {move.kind === 'RECEIVED' && <option value="new">{t('newVariety')}</option>}</select></label>}
          {move.kind === 'RECEIVED' && move.lineId === 'new' && <>
            {poultry && <label>{t('birdType')}<select value={move.newSpecies} onChange={e => setMove({ ...move, newSpecies: e.target.value })}>
              {BIRD_SPECIES.map(x => <option key={x} value={x}>{t(`species.${x}`)}</option>)}</select></label>}
            <label>{t('varietyName')}<input maxLength={120} placeholder={t('varietyExample')} value={move.newVariety} onChange={e => setMove({ ...move, newVariety: e.target.value })} /></label>
          </>}
          {move.kind === 'COUNT'
            ? <label>{t('countedHeads')}<input inputMode="numeric" value={move.countedHeads} onChange={e => setMove({ ...move, countedHeads: e.target.value })} /></label>
            : <label>{t('heads')}<input inputMode="numeric" value={move.heads} onChange={e => setMove({ ...move, heads: e.target.value })} /></label>}
          {move.kind === 'MOVED' && <label>{t('toGroup')}<select value={move.toGroupId} onChange={e => setMove({ ...move, toGroupId: e.target.value })}>
            <option value="">—</option>{others.filter(g => !g.closed).map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>}
          <label className="full">{move.kind === 'COUNT' ? t('countReason') : <>{t('cause')} <small>{tc('optional')}</small></>}
            <input maxLength={300} value={move.cause} placeholder={move.kind === 'COUNT' ? t('countReasonExample') : undefined} onChange={e => setMove({ ...move, cause: e.target.value })} /></label>
          {move.kind === 'COUNT' && <p className="hint full">{t('countHelp')}</p>}
          <div className="form-actions full"><button className="secondary">{t('recordHeads')}</button></div>
        </form>
      )}
      {moves.length === 0 ? <p className="hint">{t('noEvents')}</p> : (
        <table className="data-table">
          <thead><tr><th>{tf('date')}</th><th>{t('whatHappened')}</th><th className="num">{t('heads')}</th><th>{t('cause')}</th><th /></tr></thead>
          <tbody>{moves.map(m => (
            <tr key={m.id} className={m.reversed ? 'muted' : undefined}>
              <td>{formatDate(m.date, lang)}</td>
              <td>{t(`movement.${m.kind}`)}{m.lineVariety && <small className="move-variety"> · {m.lineVariety}</small>}{m.pairGroupName && <small> · {m.pairGroupName}</small>}{m.reversed && <small> · {t('reversed')}</small>}
                {m.documentId && <small> · <Link to={`/t/${tenant.id}/finance/records/${m.documentId}`}>{t('record')}</Link></small>}</td>
              <td className="num">{formatNumber(m.heads, lang, 0)}{m.kg != null && <small> · {formatNumber(m.kg, lang, 2)} kg</small>}</td>
              <td>{m.cause ?? ''}</td>
              <td>
                {/* Entered by hand: removed. Bought or sold with a bill: undone by reversing the bill, which also takes the heads back out */}
                {open && !m.documentId && !m.batchDayId && (
                  <button type="button" className="icon-button" aria-label={t('removeMovement')}
                          onClick={() => { if (window.confirm(t('confirmRemoveMovement'))) void send(`${base}/flock-movements/${m.id}`, 'DELETE', undefined, null, () => t('movementRemoved')); }}>
                    <Trash2 size={16} aria-hidden /></button>)}
                {m.documentId && !m.reversed && canReverse && (
                  <button type="button" className="icon-button" aria-label={t('undoMovement')} title={t('undoMovement')}
                          onClick={() => { if (window.confirm(t('confirmUndoMovement'))) void send(`/api/v1/tenants/${tenant.id}/finance/documents/${m.documentId}/reverse`, 'POST',
                            { reversalDate: today() }, null, () => t('movementUndone')); }}>
                    <RotateCcw size={16} aria-hidden /></button>)}
              </td>
            </tr>
          ))}</tbody>
        </table>
      )}
      </>}

      {tab === 'heads' && open && !poultry && (
        <>
          <h3 className="section-space">{t('births')}</h3>
          <form className="form-card form-grid" noValidate onSubmit={e => {
            e.preventDefault();
            if (!/^\d{1,2}$/.test(birth.alive) || !whole(birth.dead)) { setError(t('errors.wholeNumbers')); return; }
            void send(`${flock}/births`, 'POST', { date: birth.date, alive: Number(birth.alive), dead: num(birth.dead) }, null,
              () => { setBirth({ ...birth, alive: '', dead: '' }); return t('birthsRecorded'); });
          }}>
            <label>{tf('date')}<input type="date" max={today()} value={birth.date} onChange={e => setBirth({ ...birth, date: e.target.value })} /></label>
            <label>{t('bornAlive')}<input inputMode="numeric" value={birth.alive} onChange={e => setBirth({ ...birth, alive: e.target.value })} /></label>
            <label>{t('bornDead')}<input inputMode="numeric" value={birth.dead} onChange={e => setBirth({ ...birth, dead: e.target.value })} /></label>
            <div className="form-actions full"><button className="secondary">{t('recordBirths')}</button></div>
          </form>
        </>
      )}

      {tab === 'heads' && open && canMoney && (
        <>
          <h3 className="section-space">{t('buyMore')}</h3>
          <form className="form-card form-grid" noValidate onSubmit={e => {
            e.preventDefault();
            const problem = varietyProblem(buy.rows, true, t);
            if (problem) { setError(problem); return; }
            if (!payerOk(buy)) { setError(t('errors.purchase')); return; }
            const total = buy.rows.reduce((sum, row) => sum + Number(row.amount), 0).toFixed(2);
            void send(`${flock}/purchases`, 'POST', { date: buy.date, bill: { amount: total, ...payer(buy) }, lines: varietyLines(buy.rows, true) }, 'buy',
              () => { setBuy({ ...buy, rows: [emptyVariety(poultry ? 'CHICKEN' : group.species)] }); return t('purchaseRecorded'); });
          }}>
            <label>{tf('date')}<input type="date" max={today()} value={buy.date} onChange={e => setBuy({ ...buy, date: e.target.value })} /></label>
            <VarietyLines kind={poultry ? 'poultry' : 'smallStock'} rows={buy.rows} priced existing={lines} onChange={rows => setBuy({ ...buy, rows })} />
            <PayerFields parties={parties} accounts={accounts} f={buy} set={p => setBuy({ ...buy, ...p })} partyLabel={t('boughtFrom')} accountLabel={tf('paidFrom')} />
            <div className="form-actions full"><button className="secondary">{t('recordPurchase')}</button></div>
          </form>
        </>
      )}

      {tab === 'money' && open && canMoney && (
        <>
          <h3 className="section-space">{t(poultry ? 'sellBirds' : 'sellAnimals')}</h3>
          <form className="form-card form-grid" noValidate onSubmit={e => {
            e.preventDefault();
            if (!/^\d{1,7}$/.test(sale.heads) || Number(sale.heads) <= 0 || !decimal(sale.kg) || !money(sale.rate) || !payerOk(sale)) { setError(t('errors.sale')); return; }
            if (sale.rateBasis === 'KG' && !sale.kg) { setError(t('errors.saleKg')); return; }
            if (several && !sale.lineId) { setError(t('errors.chooseVariety')); return; }
            void send<Movement>(`${flock}/sales`, 'POST', { date: sale.date, heads: Number(sale.heads), kg: sale.kg || null, rateBasis: sale.rateBasis, rate: sale.rate, ...payer(sale),
              lineId: sale.lineId ? Number(sale.lineId) : null }, 'sale',
              () => { setSale({ ...sale, heads: '', kg: '', rate: '' }); return t('saleOfHeadsRecorded'); });
          }}>
            <label>{tf('date')}<input type="date" max={today()} value={sale.date} onChange={e => setSale({ ...sale, date: e.target.value })} /></label>
            {several && <label>{t('variety')}<select value={sale.lineId} onChange={e => setSale({ ...sale, lineId: e.target.value })}>
              <option value="">—</option>{lines.map(l => <option key={l.id} value={l.id}>{varietyLabel(l)} ({formatNumber(l.heads, lang, 0)})</option>)}</select></label>}
            <label>{t('heads')}<input inputMode="numeric" value={sale.heads} onChange={e => setSale({ ...sale, heads: e.target.value })} /></label>
            <label>{t('liveWeightKg')}<input inputMode="decimal" value={sale.kg} onChange={e => setSale({ ...sale, kg: e.target.value })} /></label>
            <label>{t('rateBasis')}<select value={sale.rateBasis} onChange={e => setSale({ ...sale, rateBasis: e.target.value })}>
              <option value="KG">{t('perKg')}</option><option value="HEAD">{t('perHead')}</option></select></label>
            <label>{t('rate')}<input inputMode="decimal" value={sale.rate} onChange={e => setSale({ ...sale, rate: e.target.value })} /></label>
            <PayerFields parties={parties} accounts={accounts} f={sale} set={p => setSale({ ...sale, ...p })} partyLabel={t('buyer')} accountLabel={tf('receivedInto')} />
            <div className="form-actions full"><button className="secondary">{t('recordSale')}</button></div>
          </form>
        </>
      )}

      {tab === 'money' && layers && canWrite && (
        <>
          <h3 className="section-space">{t('eggsOut')}</h3>
          <form className="form-card form-grid" noValidate onSubmit={e => {
            e.preventDefault();
            if (![eggUse.homeUse, eggUse.damaged].every(whole) || (!eggUse.homeUse && !eggUse.damaged)) { setError(t('errors.wholeNumbers')); return; }
            void send<RecordRefLike[]>(`${base}/egg-uses`, 'POST', { date: eggUse.date, homeUse: num(eggUse.homeUse), damaged: num(eggUse.damaged), reason: eggUse.reason || null }, 'eggUse',
              r => { setEggUse({ ...eggUse, homeUse: '', damaged: '', reason: '' }); return t('usesRecorded', { count: String(r.length) }); });
          }}>
            <label>{tf('date')}<input type="date" max={today()} value={eggUse.date} onChange={e => setEggUse({ ...eggUse, date: e.target.value })} /></label>
            <label>{t('eggsHomeUse')}<input inputMode="numeric" value={eggUse.homeUse} onChange={e => setEggUse({ ...eggUse, homeUse: e.target.value })} /></label>
            <label>{t('eggsDamaged')}<input inputMode="numeric" value={eggUse.damaged} onChange={e => setEggUse({ ...eggUse, damaged: e.target.value })} /></label>
            {eggUse.damaged && <label>{t('discardReason')}<input maxLength={300} value={eggUse.reason} onChange={e => setEggUse({ ...eggUse, reason: e.target.value })} /></label>}
            <p className="hint full">{t('eggUsesHelp')}</p>
            <div className="form-actions full"><button className="secondary">{t('recordUses')}</button></div>
          </form>
          {canMoney && (
            <form className="form-card form-grid section-space" noValidate onSubmit={e => {
              e.preventDefault();
              if (!/^\d{1,9}(\.\d{1,2})?$/.test(eggSale.quantity) || !money(eggSale.amount) || !payerOk(eggSale)) { setError(t('errors.sale')); return; }
              void send<RecordRefLike>(`${base}/egg-sales`, 'POST', { date: eggSale.date, groupId: group.id, quantity: eggSale.quantity, unit: eggSale.unit,
                amount: eggSale.amount, ...payer(eggSale) }, 'eggSale', r => { setEggSale({ ...eggSale, quantity: '', amount: '' }); return t('saleRecorded', { number: r.number }); });
            }}>
              <h4 className="full">{t('sellEggs')}</h4>
              <label>{tf('date')}<input type="date" max={today()} value={eggSale.date} onChange={e => setEggSale({ ...eggSale, date: e.target.value })} /></label>
              <label>{t('quantity')}<input inputMode="decimal" value={eggSale.quantity} onChange={e => setEggSale({ ...eggSale, quantity: e.target.value })} /></label>
              <label>{t('eggUnit')}<select value={eggSale.unit} onChange={e => setEggSale({ ...eggSale, unit: e.target.value })}>
                {['piece', 'dozen', 'tray'].map(u => <option key={u} value={u}>{t(`eggUnits.${u}`)}</option>)}</select></label>
              <label>{t('billAmountTotal')}<input inputMode="decimal" value={eggSale.amount} onChange={e => setEggSale({ ...eggSale, amount: e.target.value })} /></label>
              <PayerFields parties={parties} accounts={accounts} f={eggSale} set={p => setEggSale({ ...eggSale, ...p })} partyLabel={t('buyer')} accountLabel={tf('receivedInto')} />
              <div className="form-actions full"><button className="secondary">{t('recordSale')}</button></div>
            </form>
          )}
        </>
      )}

      {tab === 'money' && canMoney && group.costObjectId && (
        <div className="section-space">
          <RecordsPanel filter={`costObjectId=${group.costObjectId}`} prefill={`${group.farmId ? `farm=${group.farmId}&` : ''}activity=${group.costObjectId}`}
            title={t(poultry ? 'batchMoney' : 'herdMoney')} />
        </div>
      )}

      {tab === 'health' && <>
      <h3 className="section-space">{t('healthPlan')}</h3>
      <p className="hint">{t('healthPlanHelp')}</p>
      {plan.length === 0 ? <p className="hint">{t('noPlan')}</p> : (
        <table className="data-table">
          <thead><tr><th>{t('dueLabel')}</th><th>{t('kindLabel')}</th><th>{t('description')}</th><th /></tr></thead>
          <tbody>{plan.map(p => (
            <tr key={p.id}>
              <td>{formatDate(p.dueOn, lang)}</td><td>{t(`due.${p.kind}`)}</td><td>{p.description}</td>
              <td>{p.doneEventId ? t('done') : open && (
                <button type="button" className="secondary" onClick={() => void send(`${base}/health`, 'POST', { groupId: group.id, kind: p.kind, date: today(),
                  description: p.description, planItemId: p.id }, 'health', () => t('healthRecorded'))}>{t('markDoneToday')}</button>)}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {open && (
        <form className="form-card form-grid" noValidate onSubmit={e => {
          e.preventDefault();
          if (!planForm.description.trim()) { setError(t('errors.planDescription')); return; }
          void send(`${flock}/plan`, 'POST', { kind: planForm.kind, description: planForm.description.trim(), dueOn: planForm.dueOn }, null,
            () => { setPlanForm({ ...planForm, description: '' }); return t('planSaved'); });
        }}>
          <label>{t('kindLabel')}<select value={planForm.kind} onChange={e => setPlanForm({ ...planForm, kind: e.target.value })}>
            {HEALTH_KINDS.map(k => <option key={k} value={k}>{t(`due.${k}`)}</option>)}</select></label>
          <label>{t('description')}<input maxLength={300} placeholder={t(poultry ? 'planExample' : 'vaccineExample')} value={planForm.description}
                                          onChange={e => setPlanForm({ ...planForm, description: e.target.value })} /></label>
          <label>{t('dueLabel')}<input type="date" value={planForm.dueOn} onChange={e => setPlanForm({ ...planForm, dueOn: e.target.value })} /></label>
          <div className="form-actions full"><button className="secondary">{t('addPlanItem')}</button></div>
          {poultry && others.length > 0 && <>
            <label>{t('copyPlanFrom')}<select value={planForm.copyFrom} onChange={e => setPlanForm({ ...planForm, copyFrom: e.target.value })}>
              <option value="">—</option>{others.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
            <p className="hint">{t('copyPlanHelp')}</p>
            <div className="form-actions full"><button type="button" className="secondary" disabled={!planForm.copyFrom}
              onClick={() => void send<PlanItem[]>(`${flock}/plan/copy`, 'POST', { fromGroupId: Number(planForm.copyFrom) }, null, r => t('planCopied', { count: String(r.length) }))}>
              {t('copyPlan')}</button></div>
          </>}
        </form>
      )}
      </>}
    </>
  );
}

/** The batch's or herd's figures for a period (J8); money only with FINANCE_READ. */
function FigureCards({ title, f, poultry, layers, showMoney }: { title: string; f: Figures; poultry: boolean; layers: boolean; showMoney: boolean }) {
  const t = useNs('livestock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const n = (v: number | null, d = 2) => (v == null ? '—' : formatNumber(v, lang, d));
  const m = (v: number | null) => (v == null ? '—' : formatMoney(v, lang));
  return (
    <>
      <h3 className="section-space">{title}</h3>
      <div className="finance-stats">
        <div className="finance-stat"><span>{t('openingHeads')}</span><b>{n(f.openingHeads, 0)}</b><small>{t('closingHeads')} {n(f.closingHeads, 0)}</small></div>
        <div className="finance-stat"><span>{t('died')}</span><b>{n(f.died, 0)}</b><small>{t('mortality')} {n(f.mortalityPercent)}</small></div>
        <div className="finance-stat"><span>{t('soldHeads')}</span><b>{n(f.sold, 0)}</b>{f.soldKg > 0 && <small>{n(f.soldKg)} kg</small>}</div>
        {!poultry && <div className="finance-stat"><span>{t('bornHeads')}</span><b>{n(f.born, 0)}</b></div>}
        {layers && <div className="finance-stat"><span>{t('eggs')}</span><b>{n(f.eggs, 0)}</b><small>{t('henDay')} {n(f.henDayPercent, 1)}</small></div>}
        {poultry && <div className="finance-stat"><span>{t('feedKg')}</span><b>{n(f.feedKg)}</b>
          <small>{layers ? `${t('feedPerDozen')} ${n(f.feedPerDozen)}` : `${t('fcr')} ${n(f.fcr)} · ${t('averageWeight')} ${n(f.averageWeight, 3)}`}</small></div>}
        {showMoney && <div className="finance-stat"><span>{t('profit')}</span><b>{m(f.profit)}</b>
          <small>{tf('revenue')} {m(f.revenue)} · {tf('costs')} {m(f.costs)}</small></div>}
        {showMoney && <div className="finance-stat"><span>{t(layers ? 'costPerEgg' : poultry ? 'costPerKg' : 'costPerHeadMonth')}</span>
          <b>{m(layers ? f.costPerEgg : poultry ? f.costPerKg : f.costPerHeadMonth)}</b></div>}
        {showMoney && f.eggValue != null && <div className="finance-stat"><span>{t('eggValue')}</span><b>{m(f.eggValue)}</b><small>{t('eggValueHelp')}</small></div>}
        {showMoney && f.profitWithEggs != null && <div className={`finance-stat${f.profitWithEggs > 0 ? ' positive' : f.profitWithEggs < 0 ? ' negative' : ''}`}>
          <span>{t('profitWithEggs')}</span><b>{m(f.profitWithEggs)}</b><small>{t('profitWithEggsHelp')}</small></div>}
      </div>
    </>
  );
}

type Payer = { partyId: string; financialAccountId: string };
/** Who paid or was paid: a party (on credit) or, without one, the cash or bank account. */
function PayerFields({ f, set, partyLabel, accountLabel, parties, accounts }: { f: Payer; set: (p: Partial<Payer>) => void; partyLabel: string;
  accountLabel: string; parties: Party[]; accounts: FinancialAccount[] }) {
  const tc = useNs('common');
  return <>
    <label>{partyLabel} <small>{tc('optional')}</small><select value={f.partyId} onChange={e => set({ partyId: e.target.value })}>
      <option value="">—</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
    {!f.partyId && <label>{accountLabel}<select value={f.financialAccountId} onChange={e => set({ financialAccountId: e.target.value })}>
      <option value="">—</option>{accounts.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>}
  </>;
}
