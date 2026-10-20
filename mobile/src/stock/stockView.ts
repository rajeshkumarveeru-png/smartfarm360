import { apiRequest } from '../services/api';
import { tr, type Language } from '../i18n/translations';

/** Stock as the web app sees it (stock movement design, section 9); read-only on the phone for now. */
export type StockItem = {
  id: number; name: string; kind: 'INPUT' | 'PRODUCE' | 'RESALE'; baseUnit: string; customUnitName: string | null;
  quantity: number; value: number; unitCost: number; reorderLevel: number; lowStock: boolean; packUnits: { name: string; factor: number }[];
};
export type ExpiringLot = { lotId: number; itemName: string; lotNo: string; expiryDate: string; quantity: number; expired: boolean };
export type StockView = { items: StockItem[]; totalValue: number; lowStock: StockItem[]; expiringLots: ExpiringLot[] };

const base = (tenantId: number) => `/api/v1/tenants/${tenantId}/stock`;

export async function loadStock(tenantId: number): Promise<StockView> {
  const [onHand, alerts] = await Promise.all([
    apiRequest<{ items: { item: StockItem }[]; totalValue: number }>(`${base(tenantId)}/reports/on-hand`),
    apiRequest<{ lowStock: StockItem[]; expiringLots: ExpiringLot[] }>(`${base(tenantId)}/reports/alerts`),
  ]);
  return { items: onHand.items.map(r => r.item), totalValue: Number(onHand.totalValue), lowStock: alerts.lowStock, expiringLots: alerts.expiringLots };
}

/** "348 kg (7.73 bag)": the base quantity, with the first pack unit when the item has one. */
export function quantityLabel(language: Language, item: StockItem): string {
  const unit = item.customUnitName ?? tr(language, `stock.unit.${item.baseUnit}`);
  const qty = Number(item.quantity);
  const text = `${qty.toLocaleString('en-IN', { maximumFractionDigits: 4 })} ${unit}`;
  const pack = item.packUnits[0];
  return pack ? `${text} (${(qty / Number(pack.factor)).toLocaleString('en-IN', { maximumFractionDigits: 2 })} ${pack.name})` : text;
}
