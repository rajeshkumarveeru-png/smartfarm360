import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { describeError } from '../lib/errors';
import type { Category, CostObject, FinancialAccount, Loan, Party } from './types';

export type FinanceData = {
  categories: Category[]; accounts: FinancialAccount[]; parties: Party[]; costObjects: CostObject[]; loans: Loan[];
  loading: boolean; error: string; reload: () => Promise<void>;
};

/** Master data most finance screens need, loaded together for the current tenant. */
export function useFinanceData(): FinanceData {
  const tenant = useTenant();
  const base = `/api/v1/tenants/${tenant.id}/finance`;
  const [state, setState] = useState<Omit<FinanceData, 'reload' | 'loading' | 'error'>>({ categories: [], accounts: [], parties: [], costObjects: [], loans: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [categories, accounts, parties, costObjects, loans] = await Promise.all([
        api<Category[]>(`${base}/categories`), api<FinancialAccount[]>(`${base}/accounts`), api<Party[]>(`${base}/parties`),
        api<CostObject[]>(`${base}/cost-objects`), api<Loan[]>(`${base}/loans`),
      ]);
      setState({ categories, accounts, parties, costObjects, loans });
    } catch (e) {
      setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, [base]);

  useEffect(() => { void reload(); }, [reload]);
  return { ...state, loading, error, reload };
}

/** Translated label for a category: system categories by code, tenant categories by their own name. */
export function useCategoryLabel() {
  const { t } = useTranslation();
  return (c: { code?: string | null; name?: string | null } | undefined | null) =>
    !c ? '' : c.code ? t(`financeCategory.${c.code}`) : (c.name ?? '');
}

/** Stable per-form key so a double tap or retry never records the same money twice. */
export function newIdempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
