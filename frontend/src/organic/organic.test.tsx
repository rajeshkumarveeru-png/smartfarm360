import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import { visibleNav } from '../lib/nav';
import OwnProducePage from '../stock/OwnProducePage';
import BatchesPage from './BatchesPage';
import BatchPage from './BatchPage';
import type { Batch, BatchReport, Figures } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const all: Permission[] = ['TENANT_READ', 'ORGANIC_READ', 'ORGANIC_WRITE', 'FINANCE_READ', 'FINANCE_WRITE', 'FINANCE_MANAGE', 'INVENTORY_READ', 'INVENTORY_WRITE'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['ORGANIC'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const batch: Batch = { id: 3, costObjectId: 30, name: 'VC bed 1 – Aug 2026', kind: 'VERMICOMPOST', farmId: 1, farmName: 'Main farm', startedOn: '2026-08-01',
  readyBy: '2026-10-10', finishedOn: null, status: 'READY', notes: null };
const figures: Figures = { from: '2026-08-01', to: '2026-10-31', days: 75, inputKg: 2500, output: 1000, outputUnit: 'kg', yieldPercent: 40, costs: 10000,
  movedOut: 6000, revenue: 4500, profit: 500, costPerUnit: 10, inStock: 100 };
const report: BatchReport = { batch, period: figures, whole: figures };
const activities = [{ id: 30, kind: 'ORGANIC_BATCH', name: 'VC bed 1 – Aug 2026', active: true }, { id: 40, kind: 'CROP_CYCLE', name: 'Groundnut 2026', active: true },
  { id: 1, kind: 'OVERHEAD', name: 'Farm overhead', active: true }];

function routeApi(url: string, init?: { method?: string; body?: unknown }) {
  if (url.endsWith('/organic/batches') && init?.method === 'POST') return Promise.resolve(batch);
  if (url.endsWith('/organic/batches')) return Promise.resolve([batch]);
  if (url.includes('/organic/batches/3/report')) return Promise.resolve(report);
  if (url.endsWith('/organic/batches/3/logs') && init?.method === 'POST') return Promise.resolve({ id: 1 });
  if (url.endsWith('/organic/batches/3/logs')) return Promise.resolve([]);
  if (url.endsWith('/organic/batches/3/outputs') && init?.method === 'POST') return Promise.resolve({ documentId: 5 });
  if (url.endsWith('/organic/batches/3/outputs')) return Promise.resolve([{ documentId: 5, number: 'PROD/2026-27/0001', date: '2026-10-15', itemId: 8,
    itemName: 'Vermicompost', quantity: 1000, unit: 'kg', reversed: false }]);
  if (url.endsWith('/internal-transfers/preview')) return Promise.resolve({ defaultMethod: 'AVERAGE_COST', method: 'AVERAGE_COST', baseQuantity: 600, baseUnit: 'kg',
    sourceCosts: 10000, sourceProduced: 1000, alreadyMovedQuantity: 0, alreadyMovedCost: 0, unitCost: 10, costMoved: 6000 });
  if (url.endsWith('/stock/internal-transfers') && init?.method === 'POST') return Promise.resolve({ number: 'ITRF/2026-27/0001', costMoved: 6000 });
  if (url.includes('/stock/internal-transfers')) return Promise.resolve([{ documentId: 7, number: 'ITRF/2026-27/0001', date: '2026-10-20', itemId: 8,
    itemName: 'Vermicompost', baseQuantity: 600, baseUnit: 'kg', fromCostObjectId: 30, fromName: 'VC bed 1 – Aug 2026', fromKind: 'ORGANIC_BATCH',
    toCostObjectId: 40, toName: 'Groundnut 2026', toKind: 'CROP_CYCLE', method: 'AVERAGE_COST', unitCost: 10, costMoved: 6000, reversed: false }]);
  if (url.endsWith('/stock/items')) return Promise.resolve([{ id: 8, name: 'Vermicompost', kind: 'PRODUCE', baseUnit: 'kg', active: true },
    { id: 9, name: 'Urea', kind: 'INPUT', baseUnit: 'kg', active: true }]);
  if (url.endsWith('/finance/cost-objects')) return Promise.resolve(activities);
  if (url.endsWith('/farms')) return Promise.resolve([{ id: 1, tenantId: 9, name: 'Main farm', farmType: 'MIXED', country: 'IN', active: true }]);
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter>);
}
const calls = (pred: (url: string, init?: { method?: string; body?: unknown; headers?: Record<string, string> }) => boolean) =>
  apiMock.mock.calls.filter(([u, i]) => pred(String(u), i));

describe('organic manure web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    tenant.enabledModules = ['ORGANIC'];
    setLanguage('en');
  });

  it('shows Organic manure in the menu only with its module', () => {
    expect(visibleNav(tenant).map(n => n.key)).toContain('organic');
    tenant.enabledModules = ['CROP'];
    expect(visibleNav(tenant).map(n => n.key)).not.toContain('organic');
  });

  it('starts a batch and lists it', async () => {
    renderAt('/t/9/organic', <BatchesPage />, '/t/:tenantId/organic');
    expect(await screen.findByRole('link', { name: 'VC bed 1 – Aug 2026' })).toHaveAttribute('href', '/t/9/organic/batches/3');
    await userEvent.click(screen.getByRole('button', { name: 'Start batch' }));
    const form = screen.getByRole('button', { name: 'Save' }).closest('form')!;
    await userEvent.type(within(form).getByLabelText('Batch'), 'Pit 2');
    await waitFor(() => expect(within(form).getByLabelText('Farm')).toHaveValue('1'));
    await userEvent.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/organic/batches') && i?.method === 'POST')).toHaveLength(1));
    expect(calls((u, i) => u.endsWith('/organic/batches') && i?.method === 'POST')[0][1]!.body).toMatchObject({ name: 'Pit 2', kind: 'VERMICOMPOST', farmId: 1, readyBy: null });
  });

  it('shows the batch figures and records log entries and the last output', async () => {
    renderAt('/t/9/organic/batches/3', <BatchPage />, '/t/:tenantId/organic/batches/:batchId');
    expect(await screen.findByRole('heading', { name: /VC bed 1/ })).toBeInTheDocument();
    expect(screen.getAllByText(/Yield 40%/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('₹10.00').length).toBeGreaterThan(0);
    expect(screen.getByText('Groundnut 2026')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Use the output on a field' })).toHaveAttribute('href', '/t/9/stock/own-produce?from=30&itemId=8');

    const log = within(screen.getByRole('button', { name: 'Record' }).closest('form')!);
    await userEvent.selectOptions(log.getByLabelText('What was done'), 'ADDED');
    await userEvent.type(log.getByLabelText(/^kg/), '40');
    await userEvent.type(log.getByLabelText(/Notes/), 'Dry leaves');
    await userEvent.click(log.getByRole('button', { name: 'Record' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/batches/3/logs') && i?.method === 'POST')).toHaveLength(1));
    expect(calls((u, i) => u.endsWith('/batches/3/logs') && i?.method === 'POST')[0][1]!.body).toMatchObject({ kind: 'ADDED', kg: '40', notes: 'Dry leaves' });

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const out = within(screen.getByRole('button', { name: 'Record output' }).closest('form')!);
    await userEvent.selectOptions(await out.findByLabelText('Item'), '8');
    await userEvent.type(out.getByLabelText('Quantity'), '1000');
    await userEvent.click(out.getByLabelText(/This is the last output/));
    await userEvent.click(out.getByRole('button', { name: 'Record output' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/batches/3/outputs') && i?.method === 'POST')).toHaveLength(1));
    const [, init] = calls((u, i) => u.endsWith('/batches/3/outputs') && i?.method === 'POST')[0];
    expect(init!.body).toEqual({ date: expect.any(String), itemId: 8, quantity: '1000', finish: true });
    expect(init!.headers!['Idempotency-Key']).toBeTruthy();
  });

  it('shows the cost that moves before recording own produce on a field', async () => {
    renderAt('/t/9/stock/own-produce?from=30&itemId=8', <OwnProducePage />, '/t/:tenantId/stock/own-produce');
    await waitFor(() => expect(screen.getByLabelText('Stock item')).toHaveValue('8'));
    await userEvent.type(screen.getByLabelText(/Quantity/), '600');
    await userEvent.selectOptions(screen.getByLabelText('Used on'), '40');
    expect(await screen.findByText(/Average cost ₹10.00 per kg/)).toHaveTextContent('₹6,000.00 moves with it');
    expect(screen.getByLabelText('Cost moved')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Record' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/stock/internal-transfers') && i?.method === 'POST')).toHaveLength(1));
    const [, init] = calls((u, i) => u.endsWith('/stock/internal-transfers') && i?.method === 'POST')[0];
    expect(init!.body).toMatchObject({ itemId: 8, quantity: '600', fromCostObjectId: 30, toCostObjectId: 40, costMethod: null });
    expect(init!.headers!['Idempotency-Key']).toBeTruthy();
    expect(await screen.findByText(/ITRF\/2026-27\/0001 recorded; cost moved ₹6,000.00/)).toBeInTheDocument();
    // Only produce is offered, and the farm overhead is not an activity
    expect(screen.getByLabelText('Stock item')).not.toHaveTextContent('Urea');
    expect(screen.getByLabelText('Used on')).not.toHaveTextContent('Farm overhead');
  });

  it('lets only an owner or accountant choose how cost moves, and hides money without finance access', async () => {
    tenant.permissions = ['TENANT_READ', 'ORGANIC_READ', 'ORGANIC_WRITE', 'INVENTORY_READ', 'INVENTORY_WRITE', 'FINANCE_READ'];
    renderAt('/t/9/stock/own-produce?from=30&itemId=8', <OwnProducePage />, '/t/:tenantId/stock/own-produce');
    await waitFor(() => expect(screen.getByLabelText('Stock item')).toHaveValue('8'));
    expect(screen.queryByLabelText('Cost moved')).not.toBeInTheDocument();
    tenant.permissions = ['TENANT_READ', 'ORGANIC_READ', 'ORGANIC_WRITE', 'INVENTORY_READ'];
    renderAt('/t/9/organic/batches/3', <BatchPage />, '/t/:tenantId/organic/batches/:batchId');
    expect(await screen.findByRole('heading', { name: /VC bed 1/ })).toBeInTheDocument();
    expect(screen.queryByText('₹10.00')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record output' })).not.toBeInTheDocument();
  });
});
