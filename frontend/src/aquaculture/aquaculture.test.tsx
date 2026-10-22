import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import { visibleNav } from '../lib/nav';
import CyclePage from './CyclePage';
import PondsPage from './PondsPage';
import WaterPage from './WaterPage';
import type { Cycle, CycleReport, Figures, Pond, Reading } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const all: Permission[] = ['TENANT_READ', 'AQUACULTURE_READ', 'AQUACULTURE_WRITE', 'FINANCE_READ', 'FINANCE_WRITE', 'INVENTORY_READ'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['AQUACULTURE'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const cycle: Cycle = { id: 4, pondId: 2, pondName: 'Pond 1', costObjectId: 40, name: 'Pond 1 – 2026', status: 'GROWING', startedOn: '2026-01-05',
  stockedOn: '2026-02-01', plannedHarvest: '2026-10-15', harvestedOn: null, areaAcres: 1, notes: null };
const reading: Reading = { id: 7, pondId: 2, pondName: 'Pond 1', takenAt: '2026-06-12T05:00:00+05:30', dissolvedOxygen: 3.2, ph: 7.8, temperature: null,
  ammonia: null, transparency: null, salinity: null, notes: null, outOfLimits: ['DISSOLVED_OXYGEN'] };
const pond: Pond = { id: 2, farmId: 1, farmName: 'Fish farm', parcelId: null, name: 'Pond 1', kind: 'EARTHEN', waterArea: 1, areaUnit: 'ACRE', areaAcres: 1,
  depthM: null, active: true, openCycle: cycle, latestReading: reading };
const figures: Figures = { from: '2026-01-05', to: '2026-09-30', daysOfCulture: 241, stockedCount: 4000, stockedKg: 40, deaths: 200, harvestedCount: 3200,
  harvestedKg: 3000, soldKg: 3000, feedKg: 6000, survivalPercent: 80, unseenLosses: 600, fcr: 2.03, yieldPerAcre: 3000, revenue: 404000, costs: 229000,
  profit: 175000, costPerKg: 76.33, profitPerAcre: 175000, averagePrice: 134.67, estimatedKg: null, estimatedFcr: null, estimatedOn: null };
const report: CycleReport = { cycle, period: { ...figures, survivalPercent: null, unseenLosses: null, fcr: null, estimatedKg: 2310, estimatedFcr: 1.85, estimatedOn: '2026-08-01' },
  sinceStocking: figures, species: [{ species: 'ROHU', otherName: null, stocked: 1600, stockedKg: 16, deaths: 50, harvestedCount: 0, harvestedKg: 0,
    estimatedCount: 1550, averageGrams: 600, sampledOn: '2026-08-01', estimatedKg: 930 }] };

function routeApi(url: string, init?: { method?: string; body?: unknown }) {
  if (url.endsWith('/aquaculture/ponds') && init?.method === 'POST') return Promise.resolve(pond);
  if (url.endsWith('/aquaculture/ponds')) return Promise.resolve([pond]);
  if (url.endsWith('/farms')) return Promise.resolve([{ id: 1, tenantId: 9, name: 'Fish farm', farmType: 'AQUACULTURE', country: 'IN', active: true }]);
  if (url.includes('/cycles/4/report')) return Promise.resolve(report);
  if (url.endsWith('/cycles/4/stockings')) return Promise.resolve([{ id: 1, date: '2026-02-01', species: 'ROHU', otherName: null, count: 1600, kg: 16,
    hatchery: null, documentId: 11, reversed: false }]);
  if (url.endsWith('/cycles/4/samples')) return Promise.resolve([]);
  if (url.endsWith('/cycles/4/harvests') && init?.method === 'POST') return Promise.resolve({ id: 1 });
  if (url.endsWith('/cycles/4/harvests')) return Promise.resolve([]);
  if (url.includes('/cycles/4/days/') && init?.method === 'PUT') return Promise.resolve({ day: '2026-10-21', feeds: [], deaths: [], notes: null, feedDocumentId: 3 });
  if (url.includes('/cycles/4/days/')) return Promise.resolve({ day: '2026-10-21', feeds: [], deaths: [], notes: null, feedDocumentId: null });
  if (url.endsWith('/stock/items')) return Promise.resolve([{ id: 8, name: 'Floating feed', kind: 'INPUT', dimension: 'MASS', active: true },
    { id: 9, name: 'Fish', kind: 'PRODUCE', dimension: 'MASS', active: true }]);
  if (url.endsWith('/finance/parties')) return Promise.resolve([{ id: 3, name: 'Fish trader', customer: true, supplier: true, lender: false, active: true, receivable: 0, payable: 0 }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  if (url.includes('/aquaculture/readings')) return Promise.resolve([reading]);
  if (url.includes('/water-limits') && init?.method === 'PUT') return Promise.resolve({ defaults: [{ parameter: 'DISSOLVED_OXYGEN', lowest: 4, highest: null }], pond: [] });
  if (url.includes('/water-limits')) return Promise.resolve({ defaults: [], pond: [] });
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter>);
}
const calls = (pred: (url: string, init?: { method?: string; body?: unknown; headers?: Record<string, string> }) => boolean) =>
  apiMock.mock.calls.filter(([u, i]) => pred(String(u), i));

describe('fish farming web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    tenant.enabledModules = ['AQUACULTURE'];
    setLanguage('en');
  });

  it('shows Fish farming in the menu only with its module', () => {
    expect(visibleNav(tenant).map(n => n.key)).toContain('aquaculture');
    tenant.enabledModules = ['CROP'];
    expect(visibleNav(tenant).map(n => n.key)).not.toContain('aquaculture');
  });

  it('lists ponds with their crop and a flagged reading, and adds a pond', async () => {
    renderAt('/t/9/aquaculture', <PondsPage />, '/t/:tenantId/aquaculture');
    expect(await screen.findByRole('link', { name: 'Pond 1' })).toHaveAttribute('href', '/t/9/aquaculture/ponds/2');
    expect(screen.getByRole('link', { name: 'Pond 1 – 2026' })).toHaveAttribute('href', '/t/9/aquaculture/cycles/4');
    expect(screen.getByText(/Dissolved oxygen outside your limits/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add pond' }));
    await userEvent.type(screen.getByLabelText('Pond'), 'Pond 2');
    await userEvent.type(screen.getByLabelText('Water spread area'), '0.75');
    await waitFor(() => expect(screen.getByLabelText('Farm')).toHaveValue('1'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/aquaculture/ponds') && i?.method === 'POST')).toHaveLength(1));
    expect(calls((u, i) => u.endsWith('/aquaculture/ponds') && i?.method === 'POST')[0][1]!.body)
      .toEqual({ name: 'Pond 2', farmId: 1, kind: 'EARTHEN', waterArea: '0.75', areaUnit: 'ACRE', depthM: null });
  });

  it('shows the crop figures and estimates, and saves the daily record with feed and deaths', async () => {
    renderAt('/t/9/aquaculture/cycles/4', <CyclePage />, '/t/:tenantId/aquaculture/cycles/:cycleId');
    expect(await screen.findByRole('heading', { name: /Pond 1 – 2026/ })).toBeInTheDocument();
    expect(screen.getByText('80%')).toBeInTheDocument();
    expect(screen.getByText(/unseen losses 600/)).toBeInTheDocument();
    expect(screen.getAllByText('₹76.33').length).toBeGreaterThan(0);
    expect(screen.getByText(/Estimated FCR to date 1.85/)).toBeInTheDocument();
    expect(screen.getByText('1,550')).toBeInTheDocument();

    const day = within((await screen.findByRole('button', { name: 'Save the day' })).closest('form')!);
    await userEvent.click(day.getByRole('button', { name: 'Add feed' }));
    await userEvent.selectOptions(day.getByLabelText('Feed'), '8');
    await userEvent.type(day.getByLabelText('kg'), '25.5');
    await userEvent.click(day.getByRole('button', { name: 'Add deaths seen' }));
    await userEvent.type(day.getByLabelText('Number of fish'), '3');
    await userEvent.click(day.getByRole('button', { name: 'Save the day' }));
    await waitFor(() => expect(calls((u, i) => u.includes('/cycles/4/days/') && i?.method === 'PUT')).toHaveLength(1));
    expect(calls((u, i) => u.includes('/days/') && i?.method === 'PUT')[0][1]!.body).toEqual({ feeds: [{ itemId: 8, kg: '25.5' }],
      deaths: [{ species: 'ROHU', otherName: null, count: 3, cause: null }], notes: null, baseVersion: null });
  });

  it('records a graded harvest sale with the buyer\'s commission', async () => {
    renderAt('/t/9/aquaculture/cycles/4', <CyclePage />, '/t/:tenantId/aquaculture/cycles/:cycleId');
    const form = (await screen.findByRole('heading', { name: 'Record harvest' })).closest('form')!;
    const f = within(form);
    await userEvent.selectOptions(f.getByLabelText('Species'), 'ROHU|');
    await userEvent.type(f.getByLabelText(/Size grade/), 'Above 1 kg');
    await userEvent.type(f.getByLabelText('kg'), '1100');
    await userEvent.type(f.getByLabelText(/Number of fish/), '1000');
    await userEvent.type(f.getByLabelText('Price per kg'), '140');
    await userEvent.selectOptions(await f.findByLabelText(/Buyer/), '3');
    await userEvent.type(f.getByLabelText(/Commission/), '2000');
    await userEvent.click(f.getByRole('button', { name: 'Record harvest' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/cycles/4/harvests') && i?.method === 'POST')).toHaveLength(1));
    const [, init] = calls((u, i) => u.endsWith('/cycles/4/harvests') && i?.method === 'POST')[0];
    expect(init!.body).toMatchObject({ kind: 'PARTIAL', partyId: 3, financialAccountId: null, charges: [{ category: 'MARKET_CHARGES', amount: '2000' }],
      lines: [{ species: 'ROHU', otherName: null, grade: 'Above 1 kg', kg: '1100', count: 1000, use: 'SOLD', rate: '140' }] });
    expect(init!.headers!['Idempotency-Key']).toBeTruthy();
  });

  it('hides money without finance access and the buyer without permission to record money', async () => {
    tenant.permissions = ['TENANT_READ', 'AQUACULTURE_READ', 'AQUACULTURE_WRITE'];
    renderAt('/t/9/aquaculture/cycles/4', <CyclePage />, '/t/:tenantId/aquaculture/cycles/:cycleId');
    expect(await screen.findByRole('heading', { name: 'Record harvest' })).toBeInTheDocument();
    expect(screen.queryByText('₹76.33')).not.toBeInTheDocument();
    expect(screen.getByText('Recording a sale needs permission to record money.')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Seed bill amount/)).not.toBeInTheDocument();
    expect(calls(u => u.endsWith('/finance/parties'))).toHaveLength(0);
  });

  it('marks readings outside the limits and saves the farmer\'s own limits', async () => {
    renderAt('/t/9/aquaculture/water', <WaterPage />, '/t/:tenantId/aquaculture/water');
    expect(await screen.findByText('1 readings outside your limits', { exact: false })).toBeInTheDocument();
    await userEvent.type(await screen.findByLabelText('Dissolved oxygen Lowest'), '4');
    await userEvent.click(screen.getByRole('button', { name: 'Save limits' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/water-limits') && i?.method === 'PUT')).toHaveLength(1));
    expect(calls((u, i) => u.endsWith('/water-limits') && i?.method === 'PUT')[0][1]!.body)
      .toEqual({ pondId: null, limits: [{ parameter: 'DISSOLVED_OXYGEN', lowest: 4, highest: null }] });
    expect(await screen.findByText('Limits saved.')).toBeInTheDocument();
  });
});
