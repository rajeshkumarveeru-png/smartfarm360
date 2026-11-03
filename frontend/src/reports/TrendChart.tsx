import { useMemo, useState } from 'react';
import { currentLanguage, useNs } from '../i18n';
import { formatMoney, formatMoneyCompact, formatMonth } from '../lib/format';
import type { TrendMonth } from './types';

const W = 720, H = 260, LEFT = 64, RIGHT = 12, TOP = 16, BOTTOM = 34;

/** A round step for about four gridlines, so the axis reads 0, 25K, 50K… rather than odd numbers. */
function niceStep(range: number): number {
  const raw = range / 4;
  const power = 10 ** Math.floor(Math.log10(raw));
  const unit = raw / power;
  return (unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 2.5 ? 2.5 : unit <= 5 ? 5 : 10) * power;
}

/**
 * Twelve months of income and costs as columns, operating profit as a line (H3): a small built-in SVG with no chart
 * library. Each month can be pointed at or focused for its figures. The same figures are in the table below it, for
 * screen readers and printing.
 */
export default function TrendChart({ months }: { months: TrendMonth[] }) {
  const t = useNs('dashboard');
  const lang = currentLanguage();
  const [active, setActive] = useState<number | null>(null);

  const scale = useMemo(() => {
    const values = months.flatMap(m => [m.revenue, m.costs, m.operatingProfit]);
    const rawMax = Math.max(0, ...values), rawMin = Math.min(0, ...values);
    const step = niceStep(Math.max(1, rawMax - rawMin));
    const max = Math.max(step, Math.ceil(rawMax / step) * step), min = Math.floor(rawMin / step) * step;
    const ticks: number[] = [];
    for (let v = min; v <= max + step / 2; v += step) ticks.push(v);
    return { max, min, ticks };
  }, [months]);

  const empty = months.every(m => m.revenue === 0 && m.costs === 0);
  const plotW = W - LEFT - RIGHT, plotH = H - TOP - BOTTOM;
  const y = (v: number) => TOP + ((scale.max - v) / (scale.max - scale.min)) * plotH;
  const slot = plotW / Math.max(1, months.length);
  const bar = Math.min(18, Math.max(4, slot / 3.2));
  const cx = (i: number) => LEFT + slot * i + slot / 2;
  const points = months.map((m, i) => `${cx(i)},${y(m.operatingProfit)}`).join(' ');
  const shown = active != null ? months[active] : null;

  return (
    <figure className="trend-chart">
      <div className="db-chart-frame" onMouseLeave={() => setActive(null)}>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={t('trendLabel')}>
          <defs>
            <linearGradient id="db-income" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="#5fae6f" /><stop offset="1" stopColor="#3d8a4e" /></linearGradient>
            <linearGradient id="db-costs" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="#e9a27a" /><stop offset="1" stopColor="#cf7a4c" /></linearGradient>
          </defs>
          {scale.ticks.map(v => (
            <g key={v}>
              <line x1={LEFT} x2={W - RIGHT} y1={y(v)} y2={y(v)} className={v === 0 ? 'axis' : 'grid'} />
              <text x={LEFT - 8} y={y(v) + 4} textAnchor="end" className="tick">{formatMoneyCompact(v, lang)}</text>
            </g>
          ))}
          {months.map((m, i) => (
            <g key={m.month} className={active === i ? 'db-month active' : 'db-month'}>
              {active === i && <rect x={cx(i) - slot / 2 + 2} y={TOP} width={slot - 4} height={plotH} rx={8} className="db-hover-band" />}
              <rect x={cx(i) - bar - 1} y={Math.min(y(m.revenue), y(0))} width={bar} height={Math.abs(y(0) - y(m.revenue))} rx={3} className="bar-income" />
              <rect x={cx(i) + 1} y={Math.min(y(m.costs), y(0))} width={bar} height={Math.abs(y(0) - y(m.costs))} rx={3} className="bar-costs" />
              <text x={cx(i)} y={H - 14} textAnchor="middle" className="tick">{formatMonth(m.month, lang)}</text>
              {(i === 0 || m.month.endsWith('-01')) && <text x={cx(i)} y={H - 1} textAnchor="middle" className="tick year">{m.month.slice(0, 4)}</text>}
            </g>
          ))}
          <polyline points={points} className="line-profit" fill="none" />
          {months.map((m, i) => <circle key={m.month} cx={cx(i)} cy={y(m.operatingProfit)} r={active === i ? 5 : 3} className="dot-profit" />)}
          {months.map((m, i) => (
            <rect key={m.month} x={cx(i) - slot / 2} y={TOP} width={slot} height={plotH + BOTTOM - 4} className="db-hit" tabIndex={0}
              aria-label={`${formatMonth(m.month, lang, true)}: ${t('income')} ${formatMoney(m.revenue, lang)}, ${t('costs')} ${formatMoney(m.costs, lang)}, ${t('operatingProfit')} ${formatMoney(m.operatingProfit, lang)}`}
              onMouseEnter={() => setActive(i)} onFocus={() => setActive(i)} onBlur={() => setActive(null)} />
          ))}
        </svg>
        {shown && active != null && (
          <div className={`db-tooltip${active > months.length / 2 ? ' left' : ''}`} style={{ left: `${(cx(active) / W) * 100}%` }} aria-hidden>
            <strong>{formatMonth(shown.month, lang, true)}</strong>
            <span className="key-income">{t('income')} <b>{formatMoney(shown.revenue, lang)}</b></span>
            <span className="key-costs">{t('costs')} <b>{formatMoney(shown.costs, lang)}</b></span>
            <span className="key-profit">{t('operatingProfit')} <b className={shown.operatingProfit < 0 ? 'negative' : ''}>{formatMoney(shown.operatingProfit, lang)}</b></span>
          </div>
        )}
        {empty && <p className="db-chart-empty">{t('noTrend')}</p>}
      </div>
      <figcaption className="legend">
        <span className="key-income">{t('income')}</span><span className="key-costs">{t('costs')}</span><span className="key-profit">{t('operatingProfit')}</span>
      </figcaption>
      <details className="trend-table">
        <summary>{t('showTable')}</summary>
        <table className="data-table report">
          <thead><tr><th>{t('month')}</th><th className="num">{t('income')}</th><th className="num">{t('costs')}</th><th className="num">{t('operatingProfit')}</th></tr></thead>
          <tbody>{months.map(m => <tr key={m.month}><td>{m.month}</td><td className="num">{formatMoney(m.revenue, lang)}</td>
            <td className="num">{formatMoney(m.costs, lang)}</td><td className="num">{formatMoney(m.operatingProfit, lang)}</td></tr>)}</tbody>
        </table>
      </details>
    </figure>
  );
}
