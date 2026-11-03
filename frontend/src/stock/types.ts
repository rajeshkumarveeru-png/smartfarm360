import type { FinanceDocument } from '../finance/types';

export type ItemKind = 'INPUT' | 'PRODUCE' | 'RESALE';
export const ITEM_KINDS: ItemKind[] = ['INPUT', 'PRODUCE', 'RESALE'];

export type Unit = { code: string; dimension: 'MASS' | 'VOLUME' | 'COUNT' | 'LENGTH' | 'AREA'; factor: number };
export type PackUnit = { id: number; name: string; factor: number };
export type Item = {
  id: number; name: string; kind: ItemKind; baseUnit: string; customUnitName: string | null; dimension: Unit['dimension'];
  expenseCategoryId: number | null; saleCategoryId: number | null; reorderLevel: number; trackLots: boolean; quantity: number; value: number;
  unitCost: number; lowStock: boolean; active: boolean; needsReview: boolean; notes: string | null; packUnits: PackUnit[];
};
export type Location = { id: number; name: string; farmId: number | null; isDefault: boolean; active: boolean };
export type Lot = { id: number; itemId: number; lotNo: string; expiryDate: string | null; receivedOn: string; quantity: number; expired: boolean };

export type LocationQuantity = { locationId: number; locationName: string; lotId: number | null; lotNo: string | null; expiryDate: string | null; quantity: number };
export type OnHand = { asOf: string; items: { item: Item; locations: LocationQuantity[] }[]; totalValue: number };
export type Valuation = { asOf: string; items: { itemId: number; name: string; baseUnit: string; customUnitName: string | null; quantity: number; value: number }[]; totalValue: number };
export type MovementRow = {
  id: number; date: string; documentId: number; documentType: string; documentNumber: string; movementType: string; locationName: string;
  lotNo: string | null; quantity: number; value: number; unitCost: number; balanceQuantity: number; balanceValue: number; costObjectName: string | null; reversal: boolean;
};
export type Reconciliation = { ledgerBalance: number; stockValue: number; difference: number };
export type ExpiringLot = { lotId: number; itemId: number; itemName: string; lotNo: string; expiryDate: string; quantity: number; expired: boolean };
export type Alerts = { lowStock: Item[]; expiringLots: ExpiringLot[]; expiryWarningDays: number };
export type StockRecordResult = { document: FinanceDocument; warnings: string[] };

/** The unit an item is counted in, as shown to people: the farmer's own unit name, or the translated catalog unit. */
export function baseUnitLabel(item: Pick<Item, 'baseUnit' | 'customUnitName'>, t: (key: string) => string): string {
  return item.customUnitName ?? t(`unit.${item.baseUnit}`);
}

/** Units a quantity of this item can be entered in: its base unit, units of the same kind, and its pack units. */
export function unitChoices(item: Item, units: Unit[]): { value: string; label?: string }[] {
  if (item.customUnitName) return [{ value: item.baseUnit, label: item.customUnitName }, ...item.packUnits.map(p => ({ value: p.name, label: p.name }))];
  const sameKind = units.filter(u => u.dimension === item.dimension).map(u => ({ value: u.code }));
  const base = sameKind.filter(u => u.value === item.baseUnit);
  return [...base, ...sameKind.filter(u => u.value !== item.baseUnit), ...item.packUnits.map(p => ({ value: p.name, label: p.name }))];
}

/** A positive quantity with at most four decimals. */
export function isQuantity(value: string, allowZero = false): boolean {
  return /^\d{1,14}(\.\d{1,4})?$/.test(value.trim()) && (allowZero || Number(value) > 0);
}
