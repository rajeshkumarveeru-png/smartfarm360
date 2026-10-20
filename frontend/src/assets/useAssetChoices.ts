import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { can } from '../lib/nav';

/** Assets in use, for tagging fuel, repairs and parts to an asset; empty without asset access. */
export function useAssetChoices(): { id: number; name: string }[] {
  const tenant = useTenant();
  const [list, setList] = useState<{ id: number; name: string }[]>([]);
  useEffect(() => {
    if (!can(tenant, 'ASSETS_READ') || !tenant.enabledModules.includes('ASSETS')) return;
    api<{ id: number; name: string; status: string }[]>(`/api/v1/tenants/${tenant.id}/assets`)
      .then(a => setList(a.filter(x => x.status === 'IN_USE').map(x => ({ id: x.id, name: x.name })))).catch(() => setList([]));
  }, [tenant]);
  return list;
}
