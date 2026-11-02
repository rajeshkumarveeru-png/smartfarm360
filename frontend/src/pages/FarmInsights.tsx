import { type ReactNode, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Coins, ExternalLink, Layers, Receipt, Sprout, TrendingDown, TrendingUp } from 'lucide-react';
import { api } from '../api/client';
import type { Page } from '../api/types';
import { useTenant } from '../auth/useCurrentTenant';
import { InsightList, InsightModal, InsightState, type InsightRow, type Tone } from '../components/InsightModal';
import type { CostObject, DocumentSummary } from '../finance/types';
import { useCategoryLabel } from '../finance/useFinanceData';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney } from '../lib/format';
import type { CategoryBreakdown, CategoryRow } from '../reports/types';

export type Range = { from: string; to: string };
/** A row of the activity report: one crop cycle, herd, pond crop or batch with its money in the period. */
export type CyclePnl = { costObjectId: number; kind: string; name: string; revenue: number; costs: number; contribution: number; cropCycleId?: number };
/** What the farm page's pop-up shows. */
export type Insight =
  | { type: 'income' | 'costs' | 'profit' }
  | { type: 'category'; categoryId: number; label: string; kind: 'COSTS' | 'INCOME' }
  | { type: 'cycle'; cycle: CyclePnl };

/**
 * The farm's crop cycles, herds and ponds with their money in the period: the tenant's activity report kept to the
 * cost objects of this farm, with cycles that have no money yet shown at zero.
 */
export function useFarmCycles(farmId: number, range: Range, enabled: boolean) {
  const tenant = useTenant();
  const [cycles, setCycles] = useState<CyclePnl[] | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!enabled) return;
    let alive = true;
    const base = `/api/v1/tenants/${tenant.id}/finance`;
    setCycles(null);
    Promise.all([api<CostObject[]>(`${base}/cost-objects`), api<{ rows: CyclePnl[] }>(`${base}/reports/activity?from=${range.from}&to=${range.to}`)])
      .then(([objects, report]) => {
        if (!alive) return;
        const mine = objects.filter(o => o.farmId === farmId && o.kind !== 'OVERHEAD');
        setCycles(mine.map(o => {
          const row = report.rows.find(r => r.costObjectId === o.id);
          return { costObjectId: o.id, kind: o.kind, name: o.name, cropCycleId: o.cropCycleId, revenue: Number(row?.revenue ?? 0), costs: Number(row?.costs ?? 0),
            contribution: Number(row?.contribution ?? 0) };
        }).sort((a, b) => b.contribution - a.contribution || a.name.localeCompare(b.name)));
      })
      .catch(e => alive && setError(describeError(e)));
    return () => { alive = false; };
  }, [tenant.id, farmId, range.from, range.to, enabled]);
  return { cycles, error };
}

const pct = (part: number, whole: number) => (whole > 0 ? Math.round((part / whole) * 1000) / 10 : 0);

/** The farm page's pop-up: income, costs or profit by cycle, a category's records, or one cycle's breakdown. */
export function FarmInsight({ insight, farmId, farmName, range, cycles, onOpen, onClose }: {
  insight: Insight; farmId: number; farmName: string; range: Range; cycles: CyclePnl[] | null; onOpen: (next: Insight) => void; onClose: () => void;
}) {
  const tenant = useTenant();
  const t = useNs('farm');
  const tf = useNs('fin');
  const tc = useNs('common');
  const lang = currentLanguage();
  const categoryLabel = useCategoryLabel();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const money = (v: number) => formatMoney(v, lang);
  const period = `${formatDate(range.from, lang)} – ${formatDate(range.to, lang)}`;
  const [income, setIncome] = useState<CategoryBreakdown | null>(null);
  const [costs, setCosts] = useState<CategoryBreakdown | null>(null);
  const [records, setRecords] = useState<Page<DocumentSummary> | null>(null);
  const [error, setError] = useState('');

  const scope = insight.type === 'cycle' ? `costObjectId=${insight.cycle.costObjectId}` : `farmId=${farmId}`;
  useEffect(() => {
    let alive = true;
    setIncome(null); setCosts(null); setRecords(null); setError('');
    const q = `${scope}&from=${range.from}&to=${range.to}`;
    const fail = (e: unknown) => alive && setError(describeError(e));
    if (insight.type === 'category') {
      api<Page<DocumentSummary>>(`${base}/documents?${scope}&categoryId=${insight.categoryId}&from=${range.from}&to=${range.to}&size=50`)
        .then(p => alive && setRecords(p)).catch(fail);
    } else if (insight.type !== 'profit') {
      Promise.all([api<CategoryBreakdown>(`${base}/reports/categories?${q}&kind=INCOME`), api<CategoryBreakdown>(`${base}/reports/categories?${q}&kind=COSTS`)])
        .then(([i, c]) => { if (alive) { setIncome(i); setCosts(c); } }).catch(fail);
    }
    return () => { alive = false; };
  }, [base, scope, insight, range.from, range.to]);

  const label = (c: CategoryRow) => (c.categoryId ? categoryLabel({ code: c.categoryCode, name: c.categoryName }) : tf(`ledger.${c.accountCode}`));
  const categoryRows = (b: CategoryBreakdown, kind: 'COSTS' | 'INCOME'): InsightRow[] => b.rows.map(c => ({
    key: `${c.accountCode}-${c.categoryId}`, title: label(c), value: money(c.amount), valueSub: c.share != null ? `${c.share}%` : undefined, share: c.share ?? undefined,
    onClick: c.categoryId && insight.type !== 'cycle' ? () => onOpen({ type: 'category', categoryId: c.categoryId!, label: label(c), kind }) : undefined,
  }));
  const loading = <InsightState kind="loading" text={tc('loading')} />;
  const failed = error ? <InsightState kind="error" text={error} /> : null;
  const link = (href: string, content: ReactNode, className: string) => <Link className={className} to={href} onClick={onClose}>{content}</Link>;

  if (insight.type === 'profit') {
    const list = cycles ?? [];
    const revenue = list.reduce((s, c) => s + c.revenue, 0), spent = list.reduce((s, c) => s + c.costs, 0);
    return (
      <InsightModal tone="violet" icon={<Layers size={22} />} eyebrow={farmName} title={t('profitTitle')} subtitle={period} onClose={onClose}
        summary={[{ label: t('income'), value: money(revenue), tone: 'emerald' }, { label: t('costs'), value: money(spent), tone: 'amber' },
          { label: t('profit'), value: money(revenue - spent), tone: revenue - spent < 0 ? 'rose' : 'violet', hint: `${t('cycles')}: ${list.length}` }]}>
        {!cycles ? loading : <InsightList empty={t('noCycles')} rows={list.map(c => ({
          key: c.costObjectId, title: c.name, sub: `${t(`kind.${c.kind}`)} · ${t('income')} ${money(c.revenue)} · ${t('costs')} ${money(c.costs)}`,
          value: money(c.contribution), valueTone: c.contribution < 0 ? 'negative' : c.contribution > 0 ? 'positive' : undefined,
          badge: c.contribution < 0 ? t('inLoss') : c.contribution > 0 ? t('inProfit') : undefined, tone: c.contribution < 0 ? 'rose' : 'emerald',
          avatar: <Sprout size={16} />, onClick: () => onOpen({ type: 'cycle', cycle: c }),
        }))} />}
      </InsightModal>
    );
  }

  if (insight.type === 'category') {
    const total = records?.items.reduce((s, d) => s + (d.categories?.find(c => c.categoryId === insight.categoryId)?.amount ?? 0), 0) ?? 0;
    return (
      <InsightModal tone={insight.kind === 'INCOME' ? 'emerald' : 'amber'} icon={<Receipt size={22} />} eyebrow={farmName} title={insight.label} subtitle={period} onClose={onClose}
        summary={records ? [{ label: insight.kind === 'INCOME' ? t('income') : t('costs'), value: money(total) }, { label: t('recordsCount'), value: String(records.totalItems), tone: 'sky' }] : undefined}
        footer={<button type="button" className="secondary" onClick={() => onOpen({ type: insight.kind === 'INCOME' ? 'income' : 'costs' })}>{t('byCategory')}</button>}>
        {failed ?? (!records ? loading : <InsightList empty={t('noCategoryRecords')} renderLink={link} rows={records.items.map(d => ({
          key: d.id, title: `${tf(`type.${d.type}`)}${d.number ? ` · ${d.number}` : ''}`, sub: `${formatDate(d.docDate, lang)}${d.partyName ? ` · ${d.partyName}` : d.financialAccountName ? ` · ${d.financialAccountName}` : ''}`,
          value: money(d.categories?.find(c => c.categoryId === insight.categoryId)?.amount ?? d.totalAmount), badge: tf(`status.${d.status}`),
          avatar: d.type === 'SALE' ? <TrendingUp size={16} /> : <TrendingDown size={16} />, href: `/t/${tenant.id}/finance/records/${d.id}`,
        }))} />)}
      </InsightModal>
    );
  }

  // Income, costs, or one cycle: income and costs by category
  const isCycle = insight.type === 'cycle';
  const showIncome = insight.type !== 'costs', showCosts = insight.type !== 'income';
  const inTotal = income?.total ?? 0, outTotal = costs?.total ?? 0;
  const tone: Tone = insight.type === 'income' ? 'emerald' : insight.type === 'costs' ? 'amber' : (inTotal - outTotal < 0 ? 'rose' : 'teal');
  const title = isCycle ? insight.cycle.name : insight.type === 'income' ? t('incomeTitle') : t('costsTitle');
  return (
    <InsightModal tone={tone} icon={isCycle ? <Sprout size={22} /> : insight.type === 'income' ? <TrendingUp size={22} /> : <Coins size={22} />}
      eyebrow={isCycle ? `${farmName} · ${t(`kind.${insight.cycle.kind}`)}` : farmName} title={title} subtitle={period} onClose={onClose}
      summary={income && costs ? [
        ...(showIncome ? [{ label: t('income'), value: money(inTotal), tone: 'emerald' as Tone }] : []),
        ...(showCosts ? [{ label: t('costs'), value: money(outTotal), tone: 'amber' as Tone, hint: `${costs.rows.length} ${t('byCategory').toLowerCase()}` }] : []),
        ...(isCycle ? [{ label: t('profit'), value: money(inTotal - outTotal), tone: (inTotal - outTotal < 0 ? 'rose' : 'violet') as Tone,
          hint: inTotal > 0 ? `${t('margin')} ${pct(inTotal - outTotal, inTotal)}%` : undefined }] : []),
      ] : undefined}
      footer={isCycle && insight.cycle.cropCycleId
        ? <Link className="primary" to={`/t/${tenant.id}/crops/cycles/${insight.cycle.cropCycleId}`} onClick={onClose}><ExternalLink size={15} aria-hidden />{t('openCycle')}</Link>
        : undefined}>
      {failed ?? (!income || !costs ? loading : <>
        {showIncome && <><h4 className="im-section">{t('income')} · {t('byCategory')}</h4><InsightList empty={t('noIncome')} rows={categoryRows(income, 'INCOME')} /></>}
        {showCosts && <><h4 className="im-section">{t('costs')} · {t('byCategory')}</h4><InsightList empty={t('noCosts')} rows={categoryRows(costs, 'COSTS')} /></>}
      </>)}
    </InsightModal>
  );
}
