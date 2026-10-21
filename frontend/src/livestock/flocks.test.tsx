import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import FlockPage from './FlockPage';
import FlocksPage from './FlocksPage';
import LivestockHome from './LivestockHome';
import LivestockLayout from './LivestockLayout';
import LivestockReportsPage from './LivestockReportsPage';
import type { BatchDay, Figures, FlockGroup, FlockReport, Movement } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const all: Permission[] = ['TENANT_READ', 'LIVESTOCK_READ', 'LIVESTOCK_WRITE', 'FINANCE_READ', 'FINANCE_WRITE', 'INVENTORY_READ'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['POULTRY', 'GOAT_SHEEP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const flock = (id: number, name: string, species: FlockGroup['species'], purpose: FlockGroup['purpose'], animals: number, closed = false): FlockGroup => ({
  id, costObjectId: id + 10, name, purpose, farmId: null, notes: null, active: true, animals, species, placedOn: '2026-03-01', ageAtPlacementDays: 120,
  strain: null, closedOn: null, closed });
const layers = flock(1, 'Layer shed 1', 'CHICKEN', 'LAYER', 1000);
const broilers = flock(2, 'Broilers May', 'CHICKEN', 'BROILER', 0, true);
const goats = flock(3, 'Goat herd', 'GOAT', 'BREEDING_HERD', 22);
const figures: Figures = { from: '2026-10-01', to: '2026-10-21', openingHeads: 1000, closingHeads: 994, arrived: 0, born: 0, died: 6, culled: 0, sold: 0, movedOut: 0,
  soldKg: 0, headDays: 20950, eggsGood: 17500, eggsCracked: 300, eggsBroken: 50, eggs: 17800, henDayPercent: 85, feedKg: 2300, feedPerDozen: 1.57,
  mortalityPercent: 0.6, averageWeight: null, fcr: null, revenue: 126000, costs: 110300, profit: 15700, costPerEgg: 4.24, costPerKg: null, costPerHeadMonth: null };
const report = (g: FlockGroup): FlockReport => ({ group: g, heads: g.animals, period: figures, sincePlacement: g.species === 'CHICKEN' ? { ...figures, costs: 290300 } : null });
const day: BatchDay = { day: '2026-10-21', deaths: 2, deathCause: null, culls: 0, eggsGood: 850, eggsCracked: 10, eggsBroken: 0, feedItemId: 8, feedKg: 110,
  feedDocumentId: 40, weightBirds: null, weightKg: null, notes: null, headsAtEnd: 994, warnings: ['EGGS_WITHHELD'] };
const moves: Movement[] = [
  { id: 1, date: '2026-03-01', kind: 'PLACED', heads: 1000, kg: null, cause: null, documentId: null, batchDayId: null, pairGroupId: null, pairGroupName: null, reversed: false },
  { id: 2, date: '2026-10-21', kind: 'DIED', heads: 2, kg: null, cause: null, documentId: null, batchDayId: 7, pairGroupId: null, pairGroupName: null, reversed: false },
  { id: 3, date: '2026-10-10', kind: 'CULLED', heads: 4, kg: null, cause: 'Not laying', documentId: null, batchDayId: null, pairGroupId: null, pairGroupName: null, reversed: false }];

function routeApi(url: string, init?: { method?: string; body?: unknown }) {
  if (url.endsWith('/livestock/flocks') && init?.method === 'POST') return Promise.resolve(layers);
  if (url.endsWith('/livestock/flocks')) return Promise.resolve([layers, broilers, goats]);
  const m = /\/flocks\/(\d+)\/report/.exec(url);
  if (m) return Promise.resolve(report([layers, broilers, goats].find(g => g.id === Number(m[1]))!));
  if (url.includes('/flocks/1/movements')) return Promise.resolve(moves);
  if (url.includes('/days/') && init?.method === 'PUT') return Promise.resolve({ ...day, warnings: [] });
  if (url.includes('/days/')) return Promise.resolve(day);
  if (url.includes('/reports/flocks')) return Promise.resolve([report(layers), report(goats)]);
  if (url.includes('/reports/herd')) return Promise.resolve({ groups: [] });
  if (url.endsWith('/stock/items')) return Promise.resolve([{ id: 8, name: 'Layer mash', kind: 'INPUT', dimension: 'MASS', active: true },
    { id: 9, name: 'Eggs', kind: 'PRODUCE', dimension: 'COUNT', active: true }]);
  if (url.endsWith('/finance/parties')) return Promise.resolve([{ id: 3, name: 'Egg trader', customer: true, supplier: false, lender: false, active: true, receivable: 0, payable: 0 }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 4, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter>);
}
const calls = (pred: (url: string, init?: { method?: string; body?: unknown; headers?: Record<string, string> }) => boolean) =>
  apiMock.mock.calls.filter(([u, i]) => pred(String(u), i));

describe('poultry, goats and sheep web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    tenant.enabledModules = ['POULTRY', 'GOAT_SHEEP'];
    setLanguage('en');
  });

  it('lists open poultry batches only and starts a bought batch with its bill', async () => {
    renderAt('/t/9/livestock/poultry', <FlocksPage kind="poultry" />, '/t/:tenantId/livestock/poultry');
    expect(await screen.findByRole('link', { name: 'Layer shed 1' })).toHaveAttribute('href', '/t/9/livestock/flocks/1');
    expect(screen.queryByText('Broilers May')).not.toBeInTheDocument();
    expect(screen.queryByText('Goat herd')).not.toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('Show closed batches'));
    expect(screen.getByText('Broilers May')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Start batch' }));
    await userEvent.type(screen.getByLabelText('Group'), 'Layer shed 2');
    await userEvent.type(screen.getByLabelText('Heads'), '500');
    await userEvent.click(screen.getByLabelText(/Bought them/));
    await userEvent.type(screen.getByLabelText('Bill amount'), '17500');
    await userEvent.selectOptions(await screen.findByLabelText(/Bought from/), '3');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/livestock/flocks') && i?.method === 'POST')).toHaveLength(1));
    const [, init] = calls((u, i) => u.endsWith('/livestock/flocks') && i?.method === 'POST')[0];
    expect(init!.body).toMatchObject({ name: 'Layer shed 2', species: 'CHICKEN', purpose: 'LAYER', heads: 500, ageAtPlacementDays: null,
      bill: { amount: '17500', partyId: 3, financialAccountId: null } });
    expect(init!.headers!['Idempotency-Key']).toBeTruthy();
  });

  it('shows a layer batch’s figures and saves the daily record with feed and eggs', async () => {
    renderAt('/t/9/livestock/flocks/1', <FlockPage />, '/t/:tenantId/livestock/flocks/:groupId');
    expect(await screen.findByRole('heading', { name: 'Layer shed 1' })).toBeInTheDocument();
    expect(screen.getAllByText(/Hen-day % 85/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('₹4.24').length).toBeGreaterThan(0);
    expect(screen.getByText('Since the batch started')).toBeInTheDocument();
    expect(await screen.findByText(/Eggs withheld after treatment/)).toBeInTheDocument();

    const deaths = screen.getByLabelText('Deaths');
    await userEvent.clear(deaths);
    await userEvent.type(deaths, '3');
    await userEvent.click(screen.getByRole('button', { name: 'Save the day' }));
    await waitFor(() => expect(calls((u, i) => u.includes('/flocks/1/days/') && i?.method === 'PUT')).toHaveLength(1));
    expect(calls((u, i) => u.includes('/days/') && i?.method === 'PUT')[0][1]!.body).toMatchObject({ deaths: 3, eggsGood: 850, eggsCracked: 10,
      eggsBroken: null, feedItemId: 8, feedKg: '110' });
    expect(await screen.findByText(/Record for .* saved\./)).toBeInTheDocument();
  });

  it('lets only hand-entered head changes be removed, and checks a count before sending', async () => {
    renderAt('/t/9/livestock/flocks/1', <FlockPage />, '/t/:tenantId/livestock/flocks/:groupId');
    expect(await screen.findByText('Not laying')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Remove this change' })).toHaveLength(1);
    await userEvent.selectOptions(screen.getByLabelText('What happened'), 'COUNT');
    await userEvent.click(screen.getByRole('button', { name: 'Record change' }));
    expect(screen.getByText('Enter the number of heads.')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Number counted'), '990');
    await userEvent.click(screen.getByRole('button', { name: 'Record change' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/flocks/1/movements') && i?.method === 'POST')).toHaveLength(1));
    expect(calls((u, i) => u.endsWith('/movements') && i?.method === 'POST')[0][1]!.body).toMatchObject({ kind: 'COUNT', heads: null, countedHeads: 990 });
  });

  it('offers kidding for a goat herd but no daily batch record', async () => {
    renderAt('/t/9/livestock/flocks/3', <FlockPage />, '/t/:tenantId/livestock/flocks/:groupId');
    expect(await screen.findByRole('heading', { name: 'Goat herd' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record births' })).toBeInTheDocument();
    expect(screen.queryByText('Daily batch record')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Sell animals' })).toBeInTheDocument();
  });

  it('hides money without finance access and sales without permission to record money', async () => {
    tenant.permissions = ['TENANT_READ', 'LIVESTOCK_READ', 'LIVESTOCK_WRITE'];
    renderAt('/t/9/livestock/flocks/1', <FlockPage />, '/t/:tenantId/livestock/flocks/:groupId');
    expect(await screen.findByRole('heading', { name: 'Layer shed 1' })).toBeInTheDocument();
    expect(screen.queryByText('₹4.24')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record sale' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record purchase' })).not.toBeInTheDocument();
    expect(calls(u => u.endsWith('/finance/parties'))).toHaveLength(0);
  });

  it('shows batches and herds on the reports page without asking for the milk report', async () => {
    renderAt('/t/9/livestock/reports', <LivestockReportsPage />, '/t/:tenantId/livestock/reports');
    expect(await screen.findByRole('link', { name: 'Goat herd' })).toHaveAttribute('href', '/t/9/livestock/flocks/3');
    expect(calls(u => u.includes('/reports/milk'))).toHaveLength(0);
  });

  it('shows only the parts of livestock whose modules are on, and opens on poultry without dairy', async () => {
    tenant.enabledModules = ['POULTRY'];
    render(<MemoryRouter initialEntries={['/t/9/livestock']}><Routes>
      <Route path="/t/:tenantId/livestock" element={<LivestockLayout />}>
        <Route index element={<LivestockHome />} />
        <Route path="poultry" element={<FlocksPage kind="poultry" />} />
      </Route></Routes></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Poultry' })).toBeInTheDocument();
    const tabs = screen.getByRole('navigation', { name: 'Livestock' });
    expect(tabs).toHaveTextContent('Poultry');
    expect(tabs).not.toHaveTextContent('Herd');
    expect(tabs).not.toHaveTextContent('Milk');
    expect(tabs).not.toHaveTextContent('Goats and sheep');
  });
});
