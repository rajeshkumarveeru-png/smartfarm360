import { currentLanguage, useNs } from '../i18n';
import { formatMoney } from '../lib/format';
import type { TrendMonth } from './types';

const W = 640, H = 220, PAD = 28;

/**
 * Twelve months of income and costs as columns, operating profit as a line (H3): a small built-in SVG with no chart
 * library. The same figures are in the table below it, for screen readers and printing.
 */
export default function TrendChart({ months }: { months: TrendMonth[] }) {
  const t = useNs('dashboard');
  const lang = currentLanguage();
  const max = Math.max(1, ...months.flatMap(m => [Math.abs(m.revenue), Math.abs(m.costs), Math.abs(m.operatingProfit)]));
  const min = Math.min(0, ...months.map(m => m.operatingProfit));
  const span = max - min;
  const y = (v: number) => PAD + ((max - v) / span) * (H - 2 * PAD);
  const slot = (W - 2 * PAD) / Math.max(1, months.length);
  const bar = Math.max(4, slot / 3);
  const points = months.map((m, i) => `${PAD + slot * i + slot / 2},${y(m.operatingProfit)}`).join(' ');
  return (
    <figure className="trend-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={t('trendLabel')}>
        <line x1={PAD} x2={W - PAD} y1={y(0)} y2={y(0)} className="axis" />
        {months.map((m, i) => {
          const x = PAD + slot * i + slot / 2;
          return (
            <g key={m.month}>
              <rect x={x - bar} y={Math.min(y(m.revenue), y(0))} width={bar - 1} height={Math.abs(y(0) - y(m.revenue))} className="bar-income" />
              <rect x={x + 1} y={Math.min(y(m.costs), y(0))} width={bar - 1} height={Math.abs(y(0) - y(m.costs))} className="bar-costs" />
              <text x={x} y={H - 6} textAnchor="middle" className="tick">{m.month.slice(5)}</text>
            </g>
          );
        })}
        <polyline points={points} className="line-profit" fill="none" />
      </svg>
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
