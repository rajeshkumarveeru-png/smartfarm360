export const SPECIES = ['ROHU', 'CATLA', 'MRIGAL', 'COMMON_CARP', 'GRASS_CARP', 'SILVER_CARP', 'TILAPIA', 'PANGASIUS', 'MURREL', 'VANNAMEI',
  'TIGER_SHRIMP', 'SCAMPI', 'OTHER'] as const;
export const PARAMETERS = ['DISSOLVED_OXYGEN', 'PH', 'TEMPERATURE', 'AMMONIA', 'TRANSPARENCY', 'SALINITY'] as const;
export type Parameter = typeof PARAMETERS[number];
/** Reading field names for each parameter. */
export const FIELD: Record<Parameter, keyof Reading> = { DISSOLVED_OXYGEN: 'dissolvedOxygen', PH: 'ph', TEMPERATURE: 'temperature', AMMONIA: 'ammonia',
  TRANSPARENCY: 'transparency', SALINITY: 'salinity' };

export type Cycle = { id: number; pondId: number; pondName: string; costObjectId: number; name: string; status: 'PREPARING' | 'GROWING' | 'HARVESTED';
  startedOn: string; stockedOn: string | null; plannedHarvest: string | null; harvestedOn: string | null; areaAcres: number; notes: string | null };
export type Reading = { id: number; pondId: number; pondName: string; takenAt: string; dissolvedOxygen: number | null; ph: number | null;
  temperature: number | null; ammonia: number | null; transparency: number | null; salinity: number | null; notes: string | null; outOfLimits: Parameter[] };
export type Pond = { id: number; farmId: number; farmName: string; parcelId: number | null; name: string; kind: 'EARTHEN' | 'LINED' | 'TANK' | 'CAGE';
  waterArea: number; areaUnit: string; areaAcres: number; depthM: number | null; active: boolean; openCycle: Cycle | null; latestReading: Reading | null };
export type Stocking = { id: number; date: string; species: string; otherName: string | null; count: number; kg: number; hatchery: string | null;
  documentId: number | null; reversed: boolean };
export type FeedLine = { itemId: number; kg: number };
export type DeathLine = { species: string; otherName: string | null; count: number; cause: string | null };
export type Day = { day: string; feeds: FeedLine[]; deaths: DeathLine[]; notes: string | null; feedDocumentId: number | null; version?: string };
export type Sample = { id: number; date: string; species: string | null; otherName: string | null; fishWeighed: number; kg: number; averageGrams: number };
export type HarvestLine = { species: string; otherName: string | null; grade: string | null; kg: number; count: number | null; use: 'SOLD' | 'HOME' | 'GIVEN';
  rate: number | null; amount: number | null };
export type Harvest = { id: number; date: string; kind: 'PARTIAL' | 'FINAL'; saleDocumentId: number | null; chargesDocumentId: number | null; reversed: boolean;
  lines: HarvestLine[]; notes: string | null };
export type SpeciesEstimate = { species: string; otherName: string | null; stocked: number; stockedKg: number; deaths: number; harvestedCount: number;
  harvestedKg: number; estimatedCount: number; averageGrams: number | null; sampledOn: string | null; estimatedKg: number | null };
export type Figures = { from: string; to: string; daysOfCulture: number | null; stockedCount: number; stockedKg: number; deaths: number;
  harvestedCount: number | null; harvestedKg: number; soldKg: number; feedKg: number; survivalPercent: number | null; unseenLosses: number | null;
  fcr: number | null; yieldPerAcre: number | null; revenue: number | null; costs: number | null; profit: number | null; costPerKg: number | null;
  profitPerAcre: number | null; averagePrice: number | null; estimatedKg: number | null; estimatedFcr: number | null; estimatedOn: string | null };
export type CycleReport = { cycle: Cycle; species: SpeciesEstimate[]; period: Figures; sinceStocking: Figures | null };
export type Limit = { parameter: Parameter; lowest: number | null; highest: number | null };
export type Limits = { defaults: Limit[]; pond: Limit[] };
