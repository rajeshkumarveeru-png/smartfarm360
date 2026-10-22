import { ApiError, apiRequest } from '../services/api';
import type { EntryKind, FarmEntry } from '../storage/entries';
import type { OutboxRecord } from '../offline/types';

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

/** The request that records a quick entry as a cash expense or sale. */
export function entryRequest(tenantId: number, entry: FarmEntry, finance: FinanceContext): { path: string; body: Record<string, unknown> } {
  const code = CATEGORY_CODES[entry.kind][entry.category] ?? (entry.kind === 'expense' ? 'OTHER_EXPENSE' : 'OTHER_INCOME');
  const category = finance.categories.find(c => c.code === code && c.active);
  if (!category || !finance.cashAccountId) throw new ApiError(400, 'FINANCIAL_ACCOUNT_REQUIRED', '');
  return {
    path: `${base(tenantId)}/${entry.kind === 'expense' ? 'bills' : 'sales'}`,
    body: {
      docDate: entry.date,
      financialAccountId: finance.cashAccountId,
      lines: [{ categoryId: category.id, description: entry.description, amount: entry.amount.toFixed(2) }],
      notes: entry.description,
    },
  };
}

/**
 * Posts a quick entry as a cash expense or sale. The entry id is the Idempotency-Key, so retrying the same entry
 * (after a timeout or from the offline queue) can never record it twice.
 */
export async function postEntry(tenantId: number, entry: FarmEntry, finance: FinanceContext): Promise<void> {
  const { path, body } = entryRequest(tenantId, entry, finance);
  await apiRequest(path, { method: 'POST', headers: { 'Idempotency-Key': entry.id }, body });
}

/** A quick entry as an outbox record (mobile offline design, section 4): it keeps its id, so it is never recorded twice. */
export function entryRecord(userId: number, tenantId: number, entry: FarmEntry, finance: FinanceContext): OutboxRecord {
  const { path, body } = entryRequest(tenantId, entry, finance);
  return { id: entry.id, userId, tenantId, kind: entry.kind, target: `entry:${entry.id}`, summary: `${entry.date} · ${entry.description} · ${entry.amount.toFixed(2)}`,
    method: 'POST', path, body, createdAt: entry.createdAt, state: 'waiting', attempts: 0 };
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
