import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { describeError } from '../lib/errors';
import type { Item, Location, Unit } from './types';

export type StockData = { items: Item[]; locations: Location[]; units: Unit[]; loading: boolean; error: string; reload: () => Promise<void> };

/** Items, locations and the unit catalog for the current tenant, loaded together. */
export function useStockData(includeInactive = false): StockData {
  const tenant = useTenant();
  const base = `/api/v1/tenants/${tenant.id}/stock`;
  const [state, setState] = useState<{ items: Item[]; locations: Location[]; units: Unit[] }>({ items: [], locations: [], units: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [items, locations, units] = await Promise.all([
        api<Item[]>(`${base}/items${includeInactive ? '?includeInactive=true' : ''}`), api<Location[]>(`${base}/locations`), api<Unit[]>(`${base}/units`),
      ]);
      setState({ items, locations, units });
    } catch (e) {
      setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, [base, includeInactive]);

  useEffect(() => { void reload(); }, [reload]);
  return { ...state, loading, error, reload };
}
