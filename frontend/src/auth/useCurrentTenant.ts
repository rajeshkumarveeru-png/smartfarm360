import { useParams } from 'react-router-dom';
import type { TenantSummary } from '../api/types';
import { useAuth } from './AuthContext';

/** The tenant named in the URL, if the signed-in user is a member of it. */
export function useCurrentTenant(): TenantSummary | undefined {
  const { tenantId } = useParams();
  const { tenants } = useAuth();
  return tenants.find(t => String(t.id) === tenantId);
}

/** Like useCurrentTenant, for pages that are only rendered inside a validated tenant route. */
export function useTenant(): TenantSummary {
  const tenant = useCurrentTenant();
  if (!tenant) throw new Error('useTenant used outside a tenant route');
  return tenant;
}
