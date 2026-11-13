import type { AmountRow } from '../finance/types';

export type CycleStatus = 'PLANNED' | 'GROWING' | 'HARVESTING' | 'CLOSED' | 'CANCELLED';
export const CYCLE_STATUSES: CycleStatus[] = ['PLANNED', 'GROWING', 'HARVESTING', 'CLOSED', 'CANCELLED'];
export const ACTIVITY_TYPES = ['LAND_PREPARATION', 'SOWING', 'TRANSPLANTING', 'FERTILIZER', 'PESTICIDE', 'IRRIGATION', 'WEEDING', 'HARVESTING', 'OTHER'] as const;
export const OBSERVATION_KINDS = ['PEST', 'DISEASE', 'NUTRIENT', 'WEED', 'WATER_STRESS', 'OTHER'] as const;
export const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH'] as const;
export const AREA_UNITS = ['ACRE', 'CENT', 'HECTARE', 'SQFT'] as const;

export type Crop = { id: number; name: string; localName: string | null; category: string | null; description: string | null; produceItemId: number | null; active: boolean };
export type Variety = { id: number; cropId: number; name: string; localName: string | null; durationDays: number | null; notes: string | null; active: boolean };
export type Season = { id: number; name: string; startDate: string; endDate: string; seasonType: string | null; active: boolean };
export type Cycle = {
  id: number; farmId: number; farmName: string; landParcelId: number; parcelName: string; parcelArea: number; parcelAreaUnit: string;
  cropId: number; cropName: string; varietyId: number | null; varietyName: string | null; seasonId: number | null; seasonName: string | null; name: string;
  plannedSowingDate: string; actualSowingDate: string | null; expectedHarvestDate: string | null; closedOn: string | null;
  cultivatedArea: number | null; areaUnit: string; areaAcres: number | null; expectedYield: number | null; status: CycleStatus;
  costObjectId: number | null; daysSinceSowing: number | null; harvestCount: number; warnings: string[];
};
export type Activity = {
  id: number; cycleId: number; cycleName: string; activityType: string; activityDate: string; notes: string | null; workers: number | null; hours: number | null;
  inputName: string | null; quantity: number | null; quantityUnit: string | null; memoCost: number | null; legacyMemo: boolean; status: 'ACTIVE' | 'REVERSED';
  stockDocumentId: number | null; billDocumentId: number | null; createdAt: string;
};
export type Harvest = {
  id: number; cycleId: number; cycleName: string; harvestDate: string; quantity: number; quantityUnit: string; itemId: number | null; baseQuantity: number | null;
  lotId: number | null; lotNo: string | null; grade: string | null; moisturePct: number | null; memoSaleValue: number | null; legacy: boolean;
  status: 'ACTIVE' | 'REVERSED'; documentId: number | null; notes: string | null;
};
export type ActionPlanItem = { id: number; cycleId: number; cycleName: string; cropName: string; actionType: string; description: string;
  dueOn: string; completedOn: string | null; nextItemId: number | null };
export type Observation = { id: number; cycleId: number; observedOn: string; kind: string; severity: string; fieldPart: string | null; notes: string | null;
  actionTaken: string | null; activityId: number | null; createdAt: string };
export type WeatherNote = { id: number; farmId: number; date: string; rainfallMm: number | null; notes: string | null };
export type Weather = { farmId: number; from: string; to: string; totalRainfallMm: number; days: WeatherNote[] };

export type HarvestTotal = { itemId: number; itemName: string; baseUnit: string; quantity: number; unsold: number };
export type CycleReport = {
  cycleId: number; cycleName: string; cropName: string; status: CycleStatus; from: string; to: string; basis: 'ACCRUAL' | 'CASH'; actual: boolean;
  revenue: AmountRow[]; revenueTotal: number; otherIncome: number; directCosts: AmountRow[]; directCostTotal: number; allocatedOverhead: AmountRow[];
  allocatedTotal: number; interest: number; losses: number; totalCost: number; profit: number; areaAcres: number | null; costPerAcre: number | null;
  harvested: HarvestTotal[]; yieldPerAcre: number | null; costPerUnit: number | null; costUnit: string | null; unsold: number;
  estimatePrice: number | null; estimateValue: number | null;
  plan: { plannedSowingDate: string; actualSowingDate: string | null; expectedHarvestDate: string | null; firstHarvestDate: string | null;
    expectedYield: number | null; actualYield: number | null };
  memo: { activityCost: number; harvestValue: number; harvestCount: number };
};
export type ComparisonRow = {
  cycleId: number; cycleName: string; seasonName: string | null; status: CycleStatus; areaAcres: number | null; plannedSowingDate: string;
  actualSowingDate: string | null; expectedHarvestDate: string | null; firstHarvestDate: string | null; expectedYield: number | null; harvested: number | null;
  unit: string | null; yieldPerAcre: number | null; revenue: number; totalCost: number; costPerAcre: number | null; profit: number;
};
export type Comparison = { cropId: number; cropName: string; cycles: ComparisonRow[] };

export type AllocationShare = { costObjectId: number; name: string; areaAcres: number; days: number; weight: number; amount: number };
export type AllocationGroup = { accountCode: string; categoryId: number | null; categoryCode: string | null; categoryName: string | null; farmId: number | null;
  amount: number; shares: AllocationShare[] };
export type AllocationPreview = { from: string; to: string; groups: AllocationGroup[]; allocated: number; notAllocated: number };
