import { useState } from 'react';
import { currentLanguage, useNs } from '../i18n';
import { formatDate, formatMoney, formatNumber } from '../lib/format';
import type { DayRecord, FlockLine } from './types';

type Row = { key: string; day: string; lineId: number | null; label: string; deaths: number; culls: number; eggs: number; rate: number | null };

/** The batch's daily records in a period, a row for each variety on each day. Clicking a date opens that day in the form above. */
export default function DayRecords({ records, lines, range, onRange, layers, showMoney, onOpen }: {
  records: DayRecord[]; lines: FlockLine[]; range: { from: string; to: string }; onRange: (r: { from: string; to: string }) => void;
  layers: boolean; showMoney: boolean; onOpen: (day: string) => void;
}) {
  const t = useNs('livestock');
  const tf = useNs('fin');
  const lang = currentLanguage();
  const [lineId, setLineId] = useState('');
  const name = (l: { variety: string | null; species: string }) => (l.variety ? `${l.variety} · ${t(`species.${l.species}`)}` : t(`species.${l.species}`));

  const all: Row[] = records.flatMap<Row>(d => (d.lines.length > 0
    ? d.lines.map(l => ({ key: `${d.day}-${l.lineId}`, day: d.day, lineId: l.lineId, label: name(l), deaths: l.deaths, culls: l.culls, eggs: l.eggs, rate: l.eggRate }))
    // Saved as totals for the batch, or with nothing for any variety (feed or notes only)
    : [{ key: d.day, day: d.day, lineId: null, label: t('wholeBatch'), deaths: d.deaths, culls: d.culls, eggs: d.eggsGood + d.eggsCracked + d.eggsBroken, rate: null }]));
  const rows = lineId ? all.filter(r => r.lineId === Number(lineId)) : all;
  const value = (r: Row) => (r.rate == null ? 0 : r.eggs * r.rate);
  const sum = (pick: (r: Row) => number) => rows.reduce((s, r) => s + pick(r), 0);

  return (
    <>
      <h3 className="section-space">{t('dayRecords')}</h3>
      <div className="form-card form-grid day-records-filter">
        <label>{tf('from')}<input type="date" value={range.from} onChange={e => onRange({ ...range, from: e.target.value })} /></label>
        <label>{tf('to')}<input type="date" value={range.to} onChange={e => onRange({ ...range, to: e.target.value })} /></label>
        {lines.length > 1 && <label>{t('variety')}<select value={lineId} onChange={e => setLineId(e.target.value)}>
          <option value="">{t('allVarieties')}</option>{lines.map(l => <option key={l.id} value={l.id}>{name(l)}</option>)}</select></label>}
      </div>
      {rows.length === 0 ? <p className="hint">{t('noDayRecords')}</p> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>{tf('date')}</th><th>{t('variety')}</th><th className="num">{t('deaths')}</th><th className="num">{t('culls')}</th>
              {layers && <><th className="num">{t('eggsLaid')}</th><th className="num">{t('eggRate')}</th></>}
              {layers && showMoney && <th className="num">{t('dayValue')}</th>}</tr></thead>
            <tbody>{rows.map(r => (
              <tr key={r.key}>
                <td><button type="button" className="text-action day-open" onClick={() => onOpen(r.day)}>{formatDate(r.day, lang)}</button></td>
                <td>{r.label}</td>
                <td className="num">{formatNumber(r.deaths, lang, 0)}</td>
                <td className="num">{formatNumber(r.culls, lang, 0)}</td>
                {layers && <><td className="num">{formatNumber(r.eggs, lang, 0)}</td><td className="num">{r.rate == null ? '—' : formatMoney(r.rate, lang)}</td></>}
                {layers && showMoney && <td className="num">{r.rate == null ? '—' : formatMoney(value(r), lang)}</td>}
              </tr>
            ))}</tbody>
            <tfoot><tr><th colSpan={2}>{t('dayTotal')}</th><th className="num">{formatNumber(sum(r => r.deaths), lang, 0)}</th><th className="num">{formatNumber(sum(r => r.culls), lang, 0)}</th>
              {layers && <><th className="num">{formatNumber(sum(r => r.eggs), lang, 0)}</th><th /></>}
              {layers && showMoney && <th className="num">{formatMoney(sum(value), lang)}</th>}</tr></tfoot>
          </table>
        </div>
      )}
      <p className="hint">{t('dayRecordsHelp')}</p>
    </>
  );
}
