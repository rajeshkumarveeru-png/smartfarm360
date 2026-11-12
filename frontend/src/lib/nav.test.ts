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
    expect(visibleNav(worker).map(i => i.key)).toEqual(['dashboard', 'farms', 'reports', 'settings']);
  });

  it('hides modules the tenant has not enabled', () => {
    const owner = tenant(['TENANT_READ', 'FARM_READ', 'CROP_READ', 'FINANCE_READ'], []);
    expect(visibleNav(owner).map(i => i.key)).toContain('farms');
  });

  it('gives each animal section its own entry: cows and dairy, poultry, goats and sheep', () => {
    const owner = (modules: TenantSummary['enabledModules']) => visibleNav(tenant(['TENANT_READ', 'LIVESTOCK_READ'], modules)).map(i => i.key);
    expect(owner(['POULTRY'])).toContain('poultry');
    expect(owner(['POULTRY'])).not.toContain('livestock');
    expect(owner(['DAIRY'])).toEqual(expect.arrayContaining(['livestock']));
    expect(owner(['DAIRY'])).not.toContain('poultry');
    expect(owner(['GOAT_SHEEP'])).toEqual(['dashboard', 'smallStock', 'reports', 'settings']);
    expect(owner(['DAIRY', 'POULTRY', 'GOAT_SHEEP'])).toEqual(expect.arrayContaining(['livestock', 'poultry', 'smallStock']));
    expect(visibleNav(tenant(['TENANT_READ', 'LIVESTOCK_READ'], ['POULTRY'])).find(i => i.key === 'poultry')!.path).toBe('livestock/poultry');
  });

  it('treats a suspended tenant as having no permissions', () => {
    const suspended = tenant(['TENANT_READ', 'FARM_READ'], ['CROP'], false);
    expect(can(suspended, 'FARM_READ')).toBe(false);
    expect(visibleNav(suspended)).toEqual([]);
    expect(can(undefined, 'FARM_READ')).toBe(false);
  });
});
