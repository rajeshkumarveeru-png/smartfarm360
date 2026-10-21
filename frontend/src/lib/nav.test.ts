import { describe, expect, it } from 'vitest';
import type { Permission, TenantSummary } from '../api/types';
import { can, visibleNav } from './nav';

function tenant(permissions: Permission[], modules: TenantSummary['enabledModules'] = ['CROP'], active = true): TenantSummary {
  return { id: 1, name: 'T', slug: 't', preferredLanguage: 'en', active, roles: ['VIEWER'], permissions, enabledModules: modules, createdAt: '' };
}

describe('navigation visibility', () => {
  it('shows only sections the member has permission for', () => {
    const worker = tenant(['TENANT_READ', 'FARM_READ', 'CROP_READ', 'CROP_ACTIVITY_WRITE']);
    // Every member gets the dashboard first (H1) and the reports list, which shows only reports they may open
    expect(visibleNav(worker).map(i => i.key)).toEqual(['dashboard', 'farms', 'crops', 'reports', 'settings']);
  });

  it('hides modules the tenant has not enabled', () => {
    const owner = tenant(['TENANT_READ', 'FARM_READ', 'CROP_READ', 'FINANCE_READ'], []);
    expect(visibleNav(owner).map(i => i.key)).not.toContain('crops');
  });

  it('treats a suspended tenant as having no permissions', () => {
    const suspended = tenant(['TENANT_READ', 'FARM_READ'], ['CROP'], false);
    expect(can(suspended, 'FARM_READ')).toBe(false);
    expect(visibleNav(suspended)).toEqual([]);
    expect(can(undefined, 'FARM_READ')).toBe(false);
  });
});
