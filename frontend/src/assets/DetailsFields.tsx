import { useNs } from '../i18n';
import type { AssetKind, DetailsForm } from './types';
import { ASSET_KINDS } from './types';

/** The asset detail fields shared by buying an asset and registering one already in the accounts. */
export default function DetailsFields({ value, onChange }: { value: DetailsForm; onChange: (v: DetailsForm) => void }) {
  const t = useNs('assets');
  const tc = useNs('common');
  const set = (patch: Partial<DetailsForm>) => onChange({ ...value, ...patch });
  const animal = value.kind === 'BREEDING_ANIMAL';
  return (
    <>
      <label>{t('name')}<input required maxLength={140} value={value.name} onChange={e => set({ name: e.target.value })} /></label>
      <label>{t('kindLabel')}<select value={value.kind} onChange={e => set({ kind: e.target.value as AssetKind })}>
        {ASSET_KINDS.map(k => <option key={k} value={k}>{t(`kind.${k}`)}</option>)}</select></label>
      <label>{t('serialNo')} <small>{tc('optional')}</small><input maxLength={80} value={value.serialNo} onChange={e => set({ serialNo: e.target.value })} /></label>
      <label>{t('inUseFrom')}<input type="date" value={value.inUseFrom} onChange={e => set({ inUseFrom: e.target.value })} /></label>
      {animal ? <p className="hint full">{t('animalNotDepreciated')}</p> : (
        <>
          <label>{t('usefulLifeMonths')}<input inputMode="numeric" value={value.usefulLifeMonths} onChange={e => set({ usefulLifeMonths: e.target.value })} /></label>
          <label>{t('salvageValue')} <small>{tc('optional')}</small><input inputMode="decimal" value={value.salvageValue} onChange={e => set({ salvageValue: e.target.value })} /></label>
          <label>{t('depreciationBefore')} <small>{tc('optional')}</small><input inputMode="decimal" value={value.depreciationBefore}
            onChange={e => set({ depreciationBefore: e.target.value })} /></label>
        </>
      )}
      <label className="full">{t('notes')} <small>{tc('optional')}</small><input maxLength={500} value={value.notes} onChange={e => set({ notes: e.target.value })} /></label>
    </>
  );
}

/** Client-side check before sending: a name, and a useful life for anything that is depreciated. */
export function detailsValid(d: DetailsForm): boolean {
  if (!d.name.trim()) return false;
  if (d.kind !== 'BREEDING_ANIMAL' && !/^\d{1,4}$/.test(d.usefulLifeMonths)) return false;
  return [d.salvageValue, d.depreciationBefore].every(v => !v || /^\d{1,12}(\.\d{1,2})?$/.test(v));
}
