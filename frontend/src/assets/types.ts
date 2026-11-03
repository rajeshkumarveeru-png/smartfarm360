export type AssetKind = 'EQUIPMENT' | 'VEHICLE' | 'IRRIGATION' | 'STRUCTURE' | 'BREEDING_ANIMAL' | 'OTHER';
export const ASSET_KINDS: AssetKind[] = ['EQUIPMENT', 'VEHICLE', 'IRRIGATION', 'STRUCTURE', 'BREEDING_ANIMAL', 'OTHER'];

export type Asset = {
  id: number; name: string; kind: AssetKind; farmId: number | null; farmName: string | null; serialNo: string | null; sourceDocumentId: number;
  sourceNumber: string; cost: number; salvageValue: number; usefulLifeMonths: number | null; inUseFrom: string | null; depreciationBefore: number;
  accumulated: number; bookValue: number; monthlyDepreciation: number | null; lastDepreciatedMonth: string | null; status: 'IN_USE' | 'SOLD' | 'SCRAPPED';
  disposalDocumentId: number | null; costObjectId: number | null; costObjectName: string | null; notes: string | null;
};
export type UnregisteredLine = { lineId: number; documentId: number; documentType: string; number: string; date: string; description: string | null;
  categoryId: number | null; categoryCode: string | null; categoryName: string | null; amount: number };
export type RunLine = { assetId: number; name: string; amount: number; bookValueAfter: number };
export type RunPreview = { month: string; assets: RunLine[]; total: number };
export type RunSummary = { month: string; documentId: number; number: string; status: string; total: number };
export type ScheduleLine = { month: string; amount: number; bookValueAfter: number; posted: boolean };
export type Usage = { id: number; date: string; hours: number | null; km: number | null; acres: number | null; costObjectId: number | null;
  costObjectName: string | null; notes: string | null };
export type Plan = { id: number; assetId: number; assetName: string; name: string; everyDays: number | null; everyHours: number | null;
  lastDoneOn: string | null; lastDoneHours: number | null; nextDate: string | null; hoursLeft: number | null; status: 'OK' | 'DUE' | 'OVERDUE'; active: boolean };
export type Maintenance = { id: number; doneOn: string; planId: number | null; planName: string | null; hoursReading: number | null; description: string;
  documentId: number | null; documentNumber: string | null };
export type CostRow = { accountCode: string; categoryId: number | null; categoryCode: string | null; categoryName: string | null; amount: number };
export type Costs = { assetId: number; from: string; to: string; depreciation: number; runningCosts: CostRow[]; runningTotal: number; hours: number;
  costPerHour: number | null };
export type AssetReconciliation = { registerCost: number; registerAccumulated: number; ledgerCost: number; ledgerAccumulated: number;
  unregisteredCost: number; manualDepreciation: number; unexplained: number };

/** Form state for an asset's details, shared by buying and registering. */
export type DetailsForm = { name: string; kind: AssetKind; serialNo: string; usefulLifeMonths: string; salvageValue: string; inUseFrom: string;
  depreciationBefore: string; notes: string };
export const emptyDetails = (inUseFrom: string): DetailsForm => ({ name: '', kind: 'EQUIPMENT', serialNo: '', usefulLifeMonths: '', salvageValue: '',
  inUseFrom, depreciationBefore: '', notes: '' });
export const detailsBody = (d: DetailsForm) => ({
  name: d.name.trim(), kind: d.kind, serialNo: d.serialNo || null, notes: d.notes || null, inUseFrom: d.inUseFrom || null,
  usefulLifeMonths: d.kind === 'BREEDING_ANIMAL' || !d.usefulLifeMonths ? null : Number(d.usefulLifeMonths),
  salvageValue: d.salvageValue || null, depreciationBefore: d.depreciationBefore || null,
});
export const isMoney = (v: string) => /^\d{1,12}(\.\d{1,2})?$/.test(v.trim());
