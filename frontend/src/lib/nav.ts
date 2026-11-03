import type { ModuleCode, Permission, TenantSummary } from '../api/types';

/** {@code module}: the section needs that module; {@code anyModule}: any one of these (livestock covers dairy, poultry, goats and sheep). */
export type NavItem = { key: string; path: string; permission: Permission; module?: ModuleCode; anyModule?: ModuleCode[] };

/** Sections of the tenant workspace. The backend enforces the same permissions; this only hides what cannot be used. */
export const NAV_ITEMS: NavItem[] = [
  { key: 'dashboard', path: 'dashboard', permission: 'TENANT_READ' },
  { key: 'farms', path: 'farms', permission: 'FARM_READ' },
  { key: 'crops', path: 'crops', permission: 'CROP_READ', module: 'CROP' },
  // One entry per animal section: cows and dairy, poultry, and goats and sheep are kept apart
  { key: 'livestock', path: 'livestock', permission: 'LIVESTOCK_READ', module: 'DAIRY' },
  { key: 'poultry', path: 'livestock/poultry', permission: 'LIVESTOCK_READ', module: 'POULTRY' },
  { key: 'smallStock', path: 'livestock/small-stock', permission: 'LIVESTOCK_READ', module: 'GOAT_SHEEP' },
  { key: 'aquaculture', path: 'aquaculture', permission: 'AQUACULTURE_READ', module: 'AQUACULTURE' },
  { key: 'organic', path: 'organic', permission: 'ORGANIC_READ', module: 'ORGANIC' },
  { key: 'processing', path: 'processing', permission: 'PROCESSING_READ', module: 'PROCESSING' },
  { key: 'finance', path: 'finance', permission: 'FINANCE_READ' },
  { key: 'stock', path: 'stock', permission: 'INVENTORY_READ' },
  { key: 'workforce', path: 'workforce', permission: 'WORKFORCE_READ', module: 'WORKFORCE' },
  { key: 'assets', path: 'assets', permission: 'ASSETS_READ', module: 'ASSETS' },
  { key: 'reports', path: 'reports', permission: 'TENANT_READ' },
  { key: 'members', path: 'members', permission: 'MEMBERS_READ' },
  { key: 'audit', path: 'audit', permission: 'AUDIT_READ' },
  { key: 'settings', path: 'settings', permission: 'TENANT_READ' },
];

export function can(tenant: TenantSummary | undefined, permission: Permission): boolean {
  return !!tenant && tenant.active && tenant.permissions.includes(permission);
}

export function visibleNav(tenant: TenantSummary | undefined): NavItem[] {
  return NAV_ITEMS.filter(item => can(tenant, item.permission) && (!item.module || tenant!.enabledModules.includes(item.module))
    && (!item.anyModule || item.anyModule.some(m => tenant!.enabledModules.includes(m))));
}
