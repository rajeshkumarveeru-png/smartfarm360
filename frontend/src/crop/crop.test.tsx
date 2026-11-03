import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import AllocationPage from '../finance/AllocationPage';
import CyclePage from './CyclePage';
import CyclesPage from './CyclesPage';
import type { Cycle } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const all: Permission[] = ['CROP_READ', 'CROP_WRITE', 'CROP_ACTIVITY_WRITE', 'INVENTORY_READ', 'INVENTORY_WRITE', 'FINANCE_READ', 'FINANCE_WRITE',
  'FINANCE_REVERSE', 'FINANCE_MANAGE', 'FARM_READ'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['CROP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const samba: Cycle = { id: 4, farmId: 1, farmName: 'Main farm', landParcelId: 2, parcelName: 'North field', parcelArea: 2, parcelAreaUnit: 'ACRE', cropId: 3,
  cropName: 'Paddy', varietyId: null, varietyName: null, seasonId: null, seasonName: null, name: 'Samba 2026', plannedSowingDate: '2026-07-01',
  actualSowingDate: '2026-07-01', expectedHarvestDate: null, closedOn: null, cultivatedArea: 2, areaUnit: 'ACRE', areaAcres: 2, expectedYield: null,
  status: 'GROWING', costObjectId: 11, daysSinceSowing: 30, harvestCount: 0, warnings: ['FIELD_OVERCOMMITTED'] };
const report = { cycleId: 4, cycleName: 'Samba 2026', cropName: 'Paddy', status: 'GROWING', from: '2026-01-01', to: '2026-10-20', basis: 'ACCRUAL', actual: true,
  revenue: [], revenueTotal: 66000, otherIncome: 0, directCosts: [{ accountCode: '5000', categoryId: 2, categoryCode: 'FERTILIZER', categoryName: null, amount: 1866.67 }],
  directCostTotal: 14866.67, allocatedOverhead: [], allocatedTotal: 800, interest: 0, losses: 0, totalCost: 15666.67, profit: 50333.33, areaAcres: 2,
  costPerAcre: 7833.34, harvested: [{ itemId: 8, itemName: 'Paddy', baseUnit: 'kg', quantity: 4000, unsold: 1000 }], yieldPerAcre: 2000, costPerUnit: 3.9167,
  costUnit: 'kg', unsold: 1000, estimatePrice: null, estimateValue: null,
  plan: { plannedSowingDate: '2026-07-01', actualSowingDate: '2026-07-01', expectedHarvestDate: null, firstHarvestDate: null, expectedYield: null, actualYield: 4000 },
  memo: { activityCost: 0, harvestValue: 0, harvestCount: 0 } };
const urea = { id: 5, name: 'Urea', kind: 'INPUT', baseUnit: 'kg', customUnitName: null, dimension: 'MASS', expenseCategoryId: null, saleCategoryId: null,
  reorderLevel: 0, trackLots: false, quantity: 100, value: 600, unitCost: 6, lowStock: false, active: true, needsReview: false, notes: null, packUnits: [] };

function routeApi(url: string, init?: { method?: string }) {
  if (init?.method === 'POST' && url.endsWith('/overhead-allocations/preview')) {
    return Promise.resolve({ from: '2026-07-01', to: '2026-07-31', allocated: 1200, notAllocated: 0, groups: [{ accountCode: '5400', categoryId: 11,
      categoryCode: 'ELECTRICITY_WATER', categoryName: null, farmId: null, amount: 1200, shares: [
        { costObjectId: 11, name: 'Samba 2026', areaAcres: 2, days: 31, weight: 62, amount: 800 },
        { costObjectId: 12, name: 'Groundnut 2026', areaAcres: 1, days: 31, weight: 31, amount: 400 }] }] });
  }
  if (init?.method === 'POST') return Promise.resolve({ summary: { id: 90 }, activity: {}, warnings: [] });
  if (url.includes('/cultivation-cycles?')) return Promise.resolve({ items: [samba], page: 0, size: 25, totalItems: 1, totalPages: 1 });
  if (url.endsWith('/cultivation-cycles/4')) return Promise.resolve(samba);
  if (url.includes('/cultivation-cycles/4/report')) return Promise.resolve(report);
  if (url.includes('/activities?') || url.includes('/harvests?')) return Promise.resolve({ items: [], page: 0, size: 100, totalItems: 0, totalPages: 0 });
  if (url.endsWith('/stock/items')) return Promise.resolve([urea]);
  if (url.endsWith('/stock/units')) return Promise.resolve([{ code: 'kg', dimension: 'MASS', factor: 1 }, { code: 'quintal', dimension: 'MASS', factor: 100 }]);
  if (url.endsWith('/finance/categories')) return Promise.resolve([{ id: 8, code: 'LABOUR', name: null, kind: 'EXPENSE', accountCode: '5100', system: true, active: true }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  if (url.endsWith('/farms')) {
    return Promise.resolve([{ id: 1, tenantId: 9, name: 'Yerpathi', farmType: 'CROP', country: 'IN', active: true },
      { id: 2, tenantId: 9, name: 'Poultry', farmType: 'POULTRY', country: 'IN', active: true }]);
  }
  if (url.endsWith('/farms/1/land-parcels')) return Promise.resolve([{ id: 3, tenantId: 9, farmId: 1, name: 'North field', area: 1.7, areaUnit: 'ACRE', active: true }]);
  if (url.endsWith('/crops')) return Promise.resolve([{ id: 6, name: 'GroundNut' }]);
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /><Route path="*" element={<p>navigated</p>} /></Routes></MemoryRouter>);
}

describe('crop web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    setLanguage('en');
  });

  it('names and marks the missing fields of a new cycle', async () => {
    renderAt('/t/9/crops', <CyclesPage />, '/t/:tenantId/crops');
    await userEvent.click(await screen.findByRole('button', { name: /New cycle/ }));
    const form = within(screen.getByRole('button', { name: 'Save' }).closest('form')!);
    await userEvent.type(form.getByLabelText('Cycle name'), 'Samba 2027');
    await waitFor(() => expect(form.getAllByRole('option', { name: 'Yerpathi' }).length).toBeGreaterThan(0));
    await userEvent.selectOptions(form.getByLabelText('Farm'), '1');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Please fill in: Field, Crop.');
    expect(form.getByLabelText('Field')).toHaveAttribute('aria-invalid', 'true');
    expect(form.getByLabelText('Crop')).toHaveAttribute('aria-invalid', 'true');
    expect(form.getByLabelText('Cycle name')).toHaveAttribute('aria-invalid', 'false');
    expect(apiMock.mock.calls.some(([, i]) => i?.method === 'POST')).toBe(false);
  });

  it('points a poultry farm to its own section instead of a crop cycle', async () => {
    renderAt('/t/9/crops', <CyclesPage />, '/t/:tenantId/crops');
    await userEvent.click(await screen.findByRole('button', { name: /New cycle/ }));
    const form = within(screen.getByRole('button', { name: 'Save' }).closest('form')!);
    await waitFor(() => expect(form.getAllByRole('option', { name: 'Poultry' }).length).toBeGreaterThan(0));
    await userEvent.selectOptions(form.getByLabelText('Farm'), '2');
    expect(screen.getByRole('note')).toHaveTextContent(/Poultry is not a crop farm/);
    // The poultry module is off for this business, so the link turns it on in Settings
    expect(screen.getByRole('link', { name: 'Turn on Poultry in Settings' })).toHaveAttribute('href', '/t/9/settings');
    expect(screen.getByText('This farm has no fields yet.')).toBeInTheDocument();
  });

  it('lists cycles with their stage and warns when a field is overcommitted', async () => {
    renderAt('/t/9/crops', <CyclesPage />, '/t/:tenantId/crops');
    expect(await screen.findByText('Samba 2026')).toBeInTheDocument();
    expect(screen.getAllByText('Growing').some(e => e.classList.contains('stage'))).toBe(true);
    expect(screen.getByText(/need more area than it has/)).toBeInTheDocument();
  });

  it('shows the cycle figures and records work with stock inputs; money for the work goes under Expenses & sales', async () => {
    renderAt('/t/9/crops/cycles/4', <CyclePage />, '/t/:tenantId/crops/cycles/:cycleId');
    expect((await screen.findAllByText('₹50,333.33')).length).toBeGreaterThan(0);
    expect(screen.getByText('₹7,833.34')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Record field work/ }));
    await userEvent.selectOptions(screen.getByLabelText('Work'), 'FERTILIZER');
    await userEvent.click(screen.getByRole('button', { name: /Add input/ }));
    await waitFor(() => expect(screen.getByLabelText('Stock item')).toBeInTheDocument());
    await userEvent.selectOptions(screen.getByLabelText('Stock item'), '5');
    await userEvent.type(screen.getAllByLabelText('Quantity')[0], '30');
    // The diary takes no money: there is one place for it, under Expenses & sales
    expect(screen.queryByLabelText(/Labour or machine hire was paid/)).not.toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent(/goes under Expenses & sales/);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(apiMock.mock.calls.some(([u, i]) => u === '/api/v1/tenants/9/activities' && i?.method === 'POST')).toBe(true));
    const [, init] = apiMock.mock.calls.find(([u, i]) => u === '/api/v1/tenants/9/activities' && i?.method === 'POST')!;
    expect(init.headers['Idempotency-Key']).toMatch(/.{8,}/);
    expect(init.body).toMatchObject({ cycleId: 4, activityType: 'FERTILIZER', inputs: [{ itemId: 5, quantity: '30', unit: 'kg', lotNo: null }], paidWork: null });
    expect(screen.queryByText(/Recorded before the accounts/)).not.toBeInTheDocument();
  });

  it('the work diary sends money to the Expenses & sales tab', async () => {
    renderAt('/t/9/crops/cycles/4', <CyclePage />, '/t/:tenantId/crops/cycles/:cycleId');
    expect(await screen.findByRole('tab', { name: 'Work diary' })).toHaveAttribute('aria-selected', 'true');
    await userEvent.click(screen.getByRole('button', { name: /Record field work/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Add an expense' }));
    expect(screen.getByRole('tab', { name: 'Expenses & sales' })).toHaveAttribute('aria-selected', 'true');
  });

  it('hides stock inputs and paid work from people who cannot record stock or money', async () => {
    tenant.permissions = ['CROP_READ', 'CROP_ACTIVITY_WRITE'];
    renderAt('/t/9/crops/cycles/4', <CyclePage />, '/t/:tenantId/crops/cycles/:cycleId');
    await userEvent.click(await screen.findByRole('button', { name: /Record field work/ }));
    expect(screen.queryByText('Inputs used from stock')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Labour or machine hire was paid/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close cycle' })).not.toBeInTheDocument();
  });

  it('previews how overhead is shared before posting it', async () => {
    renderAt('/t/9/finance/allocation', <AllocationPage />, '/t/:tenantId/finance/allocation');
    await userEvent.click(screen.getByRole('button', { name: 'Show how it would be shared' }));
    expect(await screen.findByText('Samba 2026')).toBeInTheDocument();
    expect(screen.getByText('₹800.00')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Record the allocation' }));
    await waitFor(() => expect(screen.getByText('navigated')).toBeInTheDocument());
    const [, init] = apiMock.mock.calls.find(([u, i]) => u === '/api/v1/tenants/9/finance/overhead-allocations' && i?.method === 'POST')!;
    expect(init.headers['Idempotency-Key']).toMatch(/.{8,}/);
  });
});
