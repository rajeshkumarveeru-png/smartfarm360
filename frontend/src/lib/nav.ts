import type { ModuleCode, Permission, TenantSummary } from '../api/types';

export type NavItem = { key: string; path: string; permission: Permission; module?: ModuleCode };

/** Sections of the tenant workspace. The backend enforces the same permissions; this only hides what cannot be used. */
export const NAV_ITEMS: NavItem[] = [
  { key: 'farms', path: 'farms', permission: 'FARM_READ' },
  { key: 'crops', path: 'crops', permission: 'CROP_READ', module: 'CROP' },
  { key: 'finance', path: 'finance', permission: 'FINANCE_READ' },
  { key: 'members', path: 'members', permission: 'MEMBERS_READ' },
  { key: 'audit', path: 'audit', permission: 'AUDIT_READ' },
  { key: 'settings', path: 'settings', permission: 'TENANT_READ' },
];

export function can(tenant: TenantSummary | undefined, permission: Permission): boolean {
  return !!tenant && tenant.active && tenant.permissions.includes(permission);
}

export function visibleNav(tenant: TenantSummary | undefined): NavItem[] {
  return NAV_ITEMS.filter(item => can(tenant, item.permission) && (!item.module || tenant!.enabledModules.includes(item.module)));
}
