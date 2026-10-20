import { ApiError, apiRequest } from '../services/api';
import type { EntryKind, FarmEntry } from '../storage/entries';

/** Mobile quick-entry categories mapped to the system finance categories (shared/i18n `financeCategory.*`). */
export const CATEGORY_CODES: Record<EntryKind, Record<string, string>> = {
  expense: { seed: 'SEEDS', fertilizer: 'FERTILIZER', labour: 'LABOUR', feed: 'ANIMAL_FEED', veterinary: 'VETERINARY',
    irrigation: 'ELECTRICITY_WATER', transport: 'TRANSPORT', other: 'OTHER_EXPENSE' },
  income: { milk: 'MILK_SALE', cropSale: 'CROP_SALE', eggs: 'EGG_SALE', livestockSale: 'LIVESTOCK_SALE', other: 'OTHER_INCOME' },
};

export type FinanceSummary = { monthRevenue: number; monthCosts: number; cashTotal: number; receivableTotal: number; payableTotal: number };
type Category = { id: number; code: string | null; active: boolean };
type Account = { id: number; kind: string; active: boolean };
export type FinanceContext = { summary: FinanceSummary; categories: Category[]; cashAccountId: number | null };

const base = (tenantId: number) => `/api/v1/tenants/${tenantId}/finance`;

export async function loadFinance(tenantId: number, today: string): Promise<FinanceContext> {
  const [summary, categories, accounts] = await Promise.all([
    apiRequest<FinanceSummary>(`${base(tenantId)}/reports/summary?asOf=${today}`),
    apiRequest<Category[]>(`${base(tenantId)}/categories`),
    apiRequest<Account[]>(`${base(tenantId)}/accounts`),
  ]);
  const cash = accounts.find(a => a.active && a.kind === 'CASH') ?? accounts.find(a => a.active);
  return { summary, categories, cashAccountId: cash?.id ?? null };
}

/**
 * Posts a quick entry as a cash expense or sale. The entry id is the Idempotency-Key, so retrying the same entry
 * (after a timeout or from the offline queue) can never record it twice.
 */
export async function postEntry(tenantId: number, entry: FarmEntry, finance: FinanceContext): Promise<void> {
  const code = CATEGORY_CODES[entry.kind][entry.category] ?? (entry.kind === 'expense' ? 'OTHER_EXPENSE' : 'OTHER_INCOME');
  const category = finance.categories.find(c => c.code === code && c.active);
  if (!category || !finance.cashAccountId) throw new ApiError(400, 'FINANCIAL_ACCOUNT_REQUIRED', '');
  await apiRequest(`${base(tenantId)}/${entry.kind === 'expense' ? 'bills' : 'sales'}`, {
    method: 'POST',
    headers: { 'Idempotency-Key': entry.id },
    body: {
      docDate: entry.date,
      financialAccountId: finance.cashAccountId,
      lines: [{ categoryId: category.id, description: entry.description, amount: entry.amount.toFixed(2) }],
      notes: entry.description,
    },
  });
}

/** True when the request never reached the server, so the entry should wait on the phone. */
export function isOffline(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'NETWORK';
}

/** Retries entries saved while offline. Returns the ids that are now recorded in the accounts. */
export async function syncPending(tenantId: number, pending: FarmEntry[], finance: FinanceContext): Promise<string[]> {
  const done: string[] = [];
  for (const entry of pending) {
    try {
      await postEntry(tenantId, entry, finance);
      done.push(entry.id);
    } catch (e) {
      if (isOffline(e)) break; // still offline: try again later
      // Rejected (for example a locked period): keep it on the phone for the user to review.
    }
  }
  return done;
}
