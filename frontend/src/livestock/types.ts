export type Species = 'COW' | 'BUFFALO';
export type Purpose = 'MILKING' | 'DRY' | 'HEIFER' | 'CALF' | 'BULL' | 'OTHER' | 'LAYER' | 'BROILER' | 'COUNTRY' | 'BREEDING_HERD' | 'FATTENING' | 'MIXED';
export const PURPOSES: Purpose[] = ['MILKING', 'DRY', 'HEIFER', 'CALF', 'BULL', 'OTHER'];
export type Session = 'MORNING' | 'EVENING';
export const SESSIONS: Session[] = ['MORNING', 'EVENING'];
export const HEALTH_KINDS = ['TREATMENT', 'VACCINATION', 'DEWORMING', 'HEALTH_OTHER'] as const;

export type Group = { id: number; costObjectId: number; name: string; purpose: Purpose; farmId: number | null; notes: string | null; active: boolean; animals: number };
export type Breeding = { repro: 'OPEN' | 'INSEMINATED' | 'PREGNANT' | null; lastInsemination: string | null; checkDueFrom: string | null; expectedCalving: string | null;
  dryOffDue: string | null; lactation: number | null; lastCalving: string | null; daysInMilk: number | null; inMilk: boolean; withheldUntil: string | null };
export type Animal = { id: number; tag: string; name: string | null; species: Species; breed: string | null; sex: 'FEMALE' | 'MALE'; birthDate: string | null;
  birthDateEstimated: boolean; origin: 'BORN' | 'BOUGHT' | 'RECEIVED'; arrivedOn: string; damId: number | null; damTag: string | null; sire: string | null;
  farmId: number | null; groupId: number | null; groupName: string | null; assetId: number | null; bookValue: number | null; status: 'ACTIVE' | 'SOLD' | 'DIED' | 'CULLED';
  exitDate: string | null; exitCause: string | null; exitDocumentId: number | null; notes: string | null; breeding: Breeding };
export type AnimalEvent = { id: number; animalId: number | null; animalTag: string | null; groupId: number | null; groupName: string | null; kind: string; date: string;
  description: string | null; result: string | null; service: string | null; bull: string | null; doneBy: string | null; withdrawalUntil: string | null;
  nextDue: string | null; checkDueFrom: string | null; expectedCalving: string | null; dryOffDue: string | null; documentId: number | null; stockDocumentId: number | null };
export type AnimalCard = { animal: Animal; moves: { groupId: number; groupName: string; from: string }[]; events: AnimalEvent[];
  lactations: { number: number; calvingDate: string; dryOffDate: string | null; recordedLitres: number | null }[]; calves: Animal[];
  recentYields: { date: string; session: Session; litres: number }[] };
export type YieldEntry = { animalId: number; litres: number };
export type MilkSession = { id: number; session: Session; groupId: number; groupName: string; litres: number; fat: number | null; snf: number | null;
  yields: YieldEntry[]; notes: string | null; version?: string };
export type Withheld = { animalId: number; tag: string; name: string | null; until: string };
export type MilkDay = { date: string; sessions: MilkSession[]; total: number; withheld: Withheld[]; inStock: number };
export type MilkRow = { date: string; groupId: number; groupName: string; morning: number; evening: number; total: number; fat: number | null; snf: number | null };
export type MilkReport = { from: string; to: string; days: MilkRow[]; produced: number; sold: number; homeUse: number; fedToAnimals: number; discarded: number;
  otherOut: number; fat: number | null; snf: number | null; animals: { animalId: number; tag: string; name: string | null; litres: number; days: number; perDay: number | null }[] };
export type HerdRow = { groupId: number; groupName: string; purpose: Purpose; revenue: number; costs: { accountCode: string; categoryId: number | null;
  categoryCode: string | null; categoryName: string | null; amount: number }[]; costTotal: number; profit: number; litresProduced: number; litresSold: number;
  costPerLitre: number | null; pricePerLitre: number | null };
export type DueItem = { kind: string; animalId: number; tag: string; name: string | null; due: string };
export type BreedingCalendar = { from: string; to: string; due: DueItem[]; openLong: DueItem[]; intervals: { animalId: number; tag: string; previous: string; latest: string; days: number }[] };
export type HealthReport = { from: string; to: string; events: AnimalEvent[]; nextDue: DueItem[]; withheld: Withheld[] };

// ---- Poultry, goats and sheep (Phase 9) ----
export type FlockSpecies = 'CHICKEN' | 'GOAT' | 'SHEEP';
export type FlockGroup = Group & { species: 'CATTLE' | FlockSpecies; placedOn: string | null; ageAtPlacementDays: number | null; strain: string | null;
  closedOn: string | null; closed: boolean };
export type Movement = { id: number; date: string; kind: string; heads: number; kg: number | null; cause: string | null; documentId: number | null;
  batchDayId: number | null; pairGroupId: number | null; pairGroupName: string | null; reversed: boolean; lineId?: number | null; lineSpecies?: string | null;
  lineVariety?: string | null };
/** A variety in a batch or herd, with its heads today. */
export type FlockLine = { id: number; species: string; variety: string | null; heads: number };
/** A variety on a day: deaths, culls, eggs laid and the owner's rate for an egg. */
export type DayLineView = { lineId: number; species: string; variety: string | null; heads: number; deaths: number; culls: number; eggs: number; eggRate: number | null;
  lastEggs?: number | null; lastEggsOn?: string | null; lastEggRate?: number | null };
export type BatchDay = { day: string; deaths: number; deathCause: string | null; culls: number; eggsGood: number; eggsCracked: number; eggsBroken: number;
  feedItemId: number | null; feedKg: number | null; feedDocumentId: number | null; weightBirds: number | null; weightKg: number | null; notes: string | null;
  headsAtEnd: number; warnings: string[]; version?: string; lines?: DayLineView[] };
/** A saved day in the day-wise list, with what was recorded for each variety. */
export type DayRecordLine = { lineId: number; species: string; variety: string | null; deaths: number; culls: number; eggs: number; eggRate: number | null };
export type DayRecord = { day: string; deaths: number; culls: number; eggsGood: number; eggsCracked: number; eggsBroken: number; feedKg: number | null; notes: string | null; lines: DayRecordLine[] };
export type Figures = { from: string; to: string; openingHeads: number; closingHeads: number; arrived: number; born: number; died: number; culled: number;
  sold: number; movedOut: number; soldKg: number; headDays: number; eggsGood: number; eggsCracked: number; eggsBroken: number; eggs: number;
  henDayPercent: number | null; feedKg: number; feedPerDozen: number | null; mortalityPercent: number | null; averageWeight: number | null; fcr: number | null;
  revenue: number | null; costs: number | null; profit: number | null; costPerEgg: number | null; costPerKg: number | null; costPerHeadMonth: number | null;
  eggValue?: number | null; profitWithEggs?: number | null; varieties?: VarietyFigures[] };
/** A variety's part of the figures; money is null without finance access. */
export type VarietyFigures = { lineId: number; species: string; variety: string | null; openingHeads: number; closingHeads: number; arrived: number; born: number; died: number; culled: number;
  sold: number; movedOut: number; soldKg: number; headDays: number; eggs: number; eggRate: number | null; eggValue: number | null; boughtAmount: number | null; soldAmount: number | null };
export type FlockReport = { group: FlockGroup; heads: number; period: Figures; sincePlacement: Figures | null };
export type PlanItem = { id: number; kind: string; description: string; dueOn: string; doneEventId: number | null };
