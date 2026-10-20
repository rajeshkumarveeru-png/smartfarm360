import AsyncStorage from '@react-native-async-storage/async-storage';
import { apiRequest } from '../services/api';

export type TenantSummary = {
  id: number; name: string; active: boolean; preferredLanguage: 'en' | 'ta';
  roles: string[]; permissions: string[]; enabledModules: string[];
};

const SELECTED_TENANT_KEY = 'sf360.selectedTenant';

export function loadTenants(): Promise<TenantSummary[]> {
  return apiRequest<TenantSummary[]>('/api/v1/tenants');
}

/** The previously chosen tenant, if the user is still an active member of it. */
export async function restoreSelectedTenant(userId: number, tenants: TenantSummary[]): Promise<TenantSummary | null> {
  const stored = await AsyncStorage.getItem(`${SELECTED_TENANT_KEY}/${userId}`);
  const match = tenants.find(t => String(t.id) === stored && t.active);
  if (match) return match;
  const active = tenants.filter(t => t.active);
  return active.length === 1 ? active[0] : null;
}

export async function rememberSelectedTenant(userId: number, tenantId: number) {
  await AsyncStorage.setItem(`${SELECTED_TENANT_KEY}/${userId}`, String(tenantId));
}
