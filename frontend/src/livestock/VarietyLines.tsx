import { Plus, X } from 'lucide-react';
import { currentLanguage, useNs } from '../i18n';
import { formatMoney } from '../lib/format';
import type { FlockLine } from './types';

/** One row of the editor: an existing variety ({@code lineId}) or a new one (species and name), its heads and price. */
export type VarietyRow = { lineId: string; species: string; variety: string; heads: string; amount: string };
export const emptyVariety = (species: string): VarietyRow => ({ lineId: '', species, variety: '', heads: '', amount: '' });

export const BIRD_SPECIES = ['CHICKEN', 'DUCK', 'QUAIL', 'TURKEY'];
const HERD_SPECIES = ['GOAT', 'SHEEP'];
/** Common names offered while typing; any name can be entered. */
const SUGGESTIONS: Record<string, string[]> = {
  CHICKEN: ['Sonali', 'Kadaknath', 'Peruvidai', 'Aseel', 'Giriraja', 'Vanaraja', 'Gramapriya', 'Country chicken', 'Broiler', 'White Leghorn'],
  DUCK: ['Muscovy', 'Khaki Campbell', 'White Pekin', 'Country duck'],
  QUAIL: ['Japanese quail'],
  TURKEY: ['Broad breasted bronze', 'Country turkey'],
  GOAT: ['Tellicherry', 'Kanni', 'Boer', 'Salem Black'],
  SHEEP: ['Madras Red', 'Mecheri', 'Nellore'],
};

/** Problems with the rows, or null: each needs heads, a variety name when new, and a price when bought. */
export function varietyProblem(rows: VarietyRow[], priced: boolean, t: (k: string) => string): string | null {
  if (rows.length === 0) return t('errors.varietyHeads');
  for (const r of rows) {
    if (!/^\d+$/.test(r.heads) || Number(r.heads) <= 0) return t('errors.varietyHeads');
    if (!r.lineId && !r.variety.trim()) return t('errors.varietyName');
    if (priced && !/^\d+(\.\d{1,2})?$/.test(r.amount)) return t('errors.varietyPrice');
  }
  return null;
}

/** The rows as the API takes them. */
export function varietyLines(rows: VarietyRow[], priced: boolean) {
  return rows.map(r => ({ lineId: r.lineId ? Number(r.lineId) : null, species: r.lineId ? null : r.species, variety: r.lineId ? null : r.variety.trim(),
    heads: Number(r.heads), amount: priced ? r.amount : null }));
}

/**
 * Several varieties at once (bin and batch varieties design, V2, V3): for a new batch, or birds bought for an existing
 * one, where {@code existing} lets a row add to a variety already in the batch.
 */
export default function VarietyLines({ rows, onChange, kind, priced, existing }: {
  rows: VarietyRow[]; onChange: (rows: VarietyRow[]) => void; kind: 'poultry' | 'smallStock'; priced: boolean; existing?: FlockLine[];
}) {
  const t = useNs('livestock');
  const lang = currentLanguage();
  const species = kind === 'poultry' ? BIRD_SPECIES : HERD_SPECIES;
  const update = (i: number, patch: Partial<VarietyRow>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const total = rows.reduce((s, r) => s + (Number(r.amount) || 0), 0);
  const heads = rows.reduce((s, r) => s + (Number(r.heads) || 0), 0);
  return (
    <fieldset className="full lines variety-lines">
      <legend>{t('varieties')}</legend>
      {rows.map((r, i) => (
        <div className="variety-row" key={i}>
          {existing && existing.length > 0 && (
            <label>{t('variety')}<select value={r.lineId} onChange={e => update(i, { lineId: e.target.value })}>
              <option value="">{t('newVariety')}</option>
              {existing.map(l => <option key={l.id} value={l.id}>{l.variety ?? t(`species.${l.species}`)} · {t(`species.${l.species}`)}</option>)}</select></label>
          )}
          {!r.lineId && <>
            {species.length > 1 && <label>{t('birdType')}<select value={r.species} onChange={e => update(i, { species: e.target.value })}>
              {species.map(s => <option key={s} value={s}>{t(`species.${s}`)}</option>)}</select></label>}
            <label>{t('varietyName')}<input maxLength={120} list={`varieties-${r.species}`} placeholder={t('varietyExample')} value={r.variety}
                                              onChange={e => update(i, { variety: e.target.value })} />
              <datalist id={`varieties-${r.species}`}>{(SUGGESTIONS[r.species] ?? []).map(v => <option key={v} value={v} />)}</datalist></label>
          </>}
          <label>{t('heads')}<input inputMode="numeric" value={r.heads} onChange={e => update(i, { heads: e.target.value })} /></label>
          {priced && <label>{t('priceTotal')}<input inputMode="decimal" value={r.amount} onChange={e => update(i, { amount: e.target.value })} /></label>}
          {rows.length > 1 && <button type="button" className="icon-button" aria-label={t('removeVariety')} onClick={() => onChange(rows.filter((_, j) => j !== i))}>
            <X size={16} aria-hidden /></button>}
        </div>
      ))}
      <div className="variety-foot">
        <button type="button" className="secondary" onClick={() => onChange([...rows, emptyVariety(species[0])])}><Plus size={15} aria-hidden />{t('addVariety')}</button>
        <span className="hint">{t('varietyTotals', { heads: String(heads) })}{priced ? ` · ${formatMoney(total, lang)}` : ''}</span>
      </div>
    </fieldset>
  );
}
