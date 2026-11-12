import { useEffect, useState } from 'react';
import { Bird, Egg, Skull, Tag, Wheat, Wallet } from 'lucide-react';
import { api } from '../api/client';
import { InsightList, InsightModal, InsightState, type InsightRow, type SummaryTile, type Tone } from '../components/InsightModal';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import type { DayRecord, Figures, VarietyFigures } from './types';

export type InsightKind = 'heads' | 'died' | 'sold' | 'born' | 'eggs' | 'feed' | 'profit' | 'cost' | 'eggValue' | 'profitWithEggs';

const TONE: Record<InsightKind, Tone> = { heads: 'emerald', died: 'sky', sold: 'amber', born: 'teal', eggs: 'violet', feed: 'rose', profit: 'teal', cost: 'indigo', eggValue: 'sky', profitWithEggs: 'amber' };
const ICON: Record<InsightKind, React.ReactNode> = { heads: <Bird size={22} />, died: <Skull size={22} />, sold: <Tag size={22} />, born: <Bird size={22} />, eggs: <Egg size={22} />,
  feed: <Wheat size={22} />, profit: <Wallet size={22} />, cost: <Wallet size={22} />, eggValue: <Egg size={22} />, profitWithEggs: <Wallet size={22} /> };

/** The figures whose variety rows open that variety's days: what the daily record holds. */
const DAILY: InsightKind[] = ['heads', 'died', 'eggs', 'eggValue'];

/** The pop-up behind a figure of a batch or herd: that figure for each variety, with what is only known for the whole batch said plainly. */
export default function VarietyInsight({ kind, f, label, period, poultry, layers, showMoney, flock, onClose }: {
  kind: InsightKind; f: Figures; label: string; period: string; poultry: boolean; layers: boolean; showMoney: boolean; flock: string; onClose: () => void;
}) {
  const [variety, setVariety] = useState<VarietyFigures | null>(null);
  const [days, setDays] = useState<DayRecord[] | null>(null);
  const [failed, setFailed] = useState('');
  const drills = poultry && DAILY.includes(kind);
  useEffect(() => {
    if (!variety || days) return;
    api<DayRecord[]>(`${flock}/days?from=${f.from}&to=${f.to}`).then(d => setDays(d ?? [])).catch(e => setFailed(describeError(e)));
  }, [variety, days, flock, f.from, f.to]);
  const t = useNs('livestock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const n = (v: number | null | undefined, d = 0) => (v == null ? '—' : formatNumber(v, lang, d));
  const m = (v: number | null | undefined) => (v == null ? '—' : formatMoney(v, lang));
  const name = (v: VarietyFigures) => v.variety ?? t(`species.${v.species}`);
  const species = (v: VarietyFigures) => t(`species.${v.species}`);
  const percent = (part: number, whole: number) => (whole > 0 ? part * 100 / whole : null);
  const share = (part: number, whole: number) => (whole > 0 ? part * 100 / whole : 0);
  const active = (f.varieties ?? []).filter(v => v.openingHeads || v.closingHeads || v.arrived || v.born || v.died || v.culled || v.sold || v.movedOut || v.eggs);
  const row = (v: VarietyFigures, value: string, sub: string, bar?: number): InsightRow => ({ key: v.lineId, title: name(v), badge: v.variety ? species(v) : undefined, value, sub, share: bar,
    onClick: drills ? () => setVariety(v) : undefined });
  const total = (pick: (v: VarietyFigures) => number) => active.reduce((s, v) => s + pick(v), 0);

  if (variety) {
    const mine = (days ?? []).flatMap(d => d.lines.filter(l => l.lineId === variety.lineId).map(l => ({ day: d.day, ...l })));
    const add = (pick: (l: typeof mine[number]) => number) => mine.reduce((s, l) => s + pick(l), 0);
    const worth = (l: typeof mine[number]) => (l.eggRate == null ? 0 : l.eggs * l.eggRate);
    return (
      <InsightModal tone={TONE[kind]} icon={ICON[kind]} eyebrow={t('insDayWise')} title={name(variety)} subtitle={period} onClose={onClose}
        summary={days ? [...(layers ? [{ label: t('eggsLaid'), value: n(add(l => l.eggs)) }] : []), { label: t('deaths'), value: n(add(l => l.deaths)) }, { label: t('culls'), value: n(add(l => l.culls)) },
          ...(layers && showMoney ? [{ label: t('dayValue'), value: m(add(worth)) }] : [])] : undefined}
        footer={<button type="button" className="secondary" onClick={() => setVariety(null)}>{t('insBack')}</button>}>
        {failed ? <InsightState kind="error" text={failed} /> : !days ? <InsightState kind="loading" text={t('insLoading')} />
          : <InsightList empty={t('insNoDays')} rows={mine.map(l => ({ key: l.day, title: formatDate(l.day, lang), avatar: l.day.slice(8),
            value: layers ? t('insEggCount', { eggs: n(l.eggs) }) : undefined,
            sub: [`${t('deaths')} ${n(l.deaths)}`, `${t('culls')} ${n(l.culls)}`, ...(layers && l.eggRate != null ? [`${t('eggRate')} ${m(l.eggRate)}`] : []),
              ...(layers && showMoney && l.eggRate != null ? [`${t('dayValue')} ${m(worth(l))}`] : [])].join(' · ') }))} />}
        {days && <p className="hint variety-note">{t('insDayNote')}</p>}
      </InsightModal>
    );
  }

  let summary: SummaryTile[] = [];
  let rows: InsightRow[] = [];
  let note: string | null = null;

  if (kind === 'heads') {
    summary = [{ label: t('openingHeads'), value: n(f.openingHeads) }, { label: t('closingHeads'), value: n(f.closingHeads) }];
    rows = active.map(v => row(v, n(v.closingHeads), t('insHeadsSub', { opening: n(v.openingHeads), arrived: n(v.arrived), born: n(v.born), died: n(v.died), culled: n(v.culled), sold: n(v.sold), moved: n(v.movedOut) }),
      share(v.closingHeads, f.closingHeads)));
  } else if (kind === 'died') {
    summary = [{ label: t('died'), value: n(f.died) }, { label: t('mortality'), value: n(f.mortalityPercent, 2) }, { label: t('culled'), value: n(f.culled) }];
    rows = active.map(v => row(v, n(v.died), t('insDiedSub', { percent: n(percent(v.died, v.openingHeads + v.arrived + v.born), 2), culled: n(v.culled) }), share(v.died, f.died)));
  } else if (kind === 'sold') {
    summary = [{ label: t('soldHeads'), value: n(f.sold) }, { label: 'kg', value: n(f.soldKg, 2) }];
    rows = active.map(v => row(v, n(v.sold), v.soldAmount == null ? `${n(v.soldKg, 2)} kg` : `${n(v.soldKg, 2)} kg · ${m(v.soldAmount)}`, share(v.sold, f.sold)));
  } else if (kind === 'born') {
    summary = [{ label: t('bornHeads'), value: n(f.born) }];
    rows = active.map(v => row(v, n(v.born), species(v), share(v.born, f.born)));
  } else if (kind === 'eggs') {
    summary = [{ label: t('eggsLaid'), value: n(f.eggs) }, { label: t('eggsGood'), value: n(f.eggsGood) }, { label: t('eggsCracked'), value: n(f.eggsCracked) },
      { label: t('eggsBroken'), value: n(f.eggsBroken) }];
    rows = active.map(v => row(v, n(v.eggs), `${t('henDay')} ${n(percent(v.eggs, v.headDays), 1)}${v.eggRate != null ? ` · ${t('insEggRate', { rate: m(v.eggRate) })}` : ''}`, share(v.eggs, f.eggs)));
    const notSplit = f.eggs - total(v => v.eggs);
    if (notSplit > 0) rows.push({ key: 'not-split', title: t('insNotSplit'), sub: t('insNotSplitSub'), value: n(notSplit), share: share(notSplit, f.eggs), tone: 'slate' });
  } else if (kind === 'feed') {
    summary = [{ label: t('feedKg'), value: n(f.feedKg, 2) }, { label: layers ? t('feedPerDozen') : t('fcr'), value: n(layers ? f.feedPerDozen : f.fcr, 2) }];
    rows = active.map(v => row(v, t('insFeedAbout', { kg: n(f.headDays > 0 ? Number(f.feedKg) * v.headDays / f.headDays : 0, 1) }), t('insFeedSub', { days: n(v.headDays) }), share(v.headDays, f.headDays)));
    note = t('insFeedNote');
  } else if (kind === 'eggValue') {
    summary = [{ label: t('eggValue'), value: m(f.eggValue), hint: t('eggValueHelp') }, { label: t('eggsLaid'), value: n(f.eggs) }];
    rows = active.map(v => row(v, m(v.eggValue), `${t('insEggCount', { eggs: n(v.eggs) })}${v.eggRate != null ? ` · ${t('insEggRate', { rate: m(v.eggRate) })}` : ''}`, share(Number(v.eggValue ?? 0), Number(f.eggValue ?? 0))));
  } else if (kind === 'cost') {
    const perUnit = layers ? f.costPerEgg : poultry ? f.costPerKg : f.costPerHeadMonth;
    summary = [{ label: label, value: m(perUnit) }, { label: tf('costs'), value: m(f.costs) }];
    rows = active.map(v => (layers ? row(v, n(v.eggs), t('insBoughtFor', { amount: m(v.boughtAmount) }), share(v.eggs, f.eggs))
      : poultry ? row(v, `${n(v.soldKg, 2)} kg`, t('insBoughtFor', { amount: m(v.boughtAmount) }), share(Number(v.soldKg), Number(f.soldKg)))
      : row(v, n(v.headDays), t('insBoughtFor', { amount: m(v.boughtAmount) }), share(v.headDays, f.headDays))));
    note = t('insCostNote');
  } else {
    // Profit: what each variety's birds cost and sold for (and its eggs); the rest belongs to the whole batch, so the rows add up to the batch's profit
    const withEggs = kind === 'profitWithEggs';
    const whole = withEggs ? f.profitWithEggs : f.profit;
    const direct = (v: VarietyFigures) => Number(v.soldAmount ?? 0) - Number(v.boughtAmount ?? 0) + (withEggs ? Number(v.eggValue ?? 0) : 0);
    summary = withEggs ? [{ label: label, value: m(whole), tone: Number(whole) < 0 ? 'rose' : 'emerald' }, { label: t('eggValue'), value: m(f.eggValue) }, { label: tf('costs'), value: m(f.costs) }]
      : [{ label: tf('revenue'), value: m(f.revenue), tone: 'emerald' }, { label: tf('costs'), value: m(f.costs), tone: 'amber' }, { label: label, value: m(whole), tone: Number(whole) < 0 ? 'rose' : 'teal' }];
    rows = active.map(v => {
      const amount = direct(v);
      return { ...row(v, m(amount), withEggs ? t('insMoneyEggsSub', { bought: m(v.boughtAmount), sold: m(v.soldAmount), eggs: m(v.eggValue) }) : t('insMoneySub', { bought: m(v.boughtAmount), sold: m(v.soldAmount) })),
        valueTone: amount < 0 ? 'negative' as const : amount > 0 ? 'positive' as const : undefined };
    });
    const rest = Number(whole ?? 0) - total(direct);
    rows.push({ key: 'whole-batch', title: t('insShared'), sub: t('insSharedSub'), value: m(rest), valueTone: rest < 0 ? 'negative' : rest > 0 ? 'positive' : undefined, tone: 'slate' });
    note = t('insProfitNote');
  }

  return (
    <InsightModal tone={TONE[kind]} icon={ICON[kind]} eyebrow={t('insByVariety')} title={label} subtitle={period} summary={summary} onClose={onClose}>
      <InsightList rows={rows} empty={t('insNoVarieties')} />
      {drills && active.length > 0 && <p className="hint variety-note">{t('insDrillHint')}</p>}
      {note && <p className="hint variety-note">{note}</p>}
    </InsightModal>
  );
}
