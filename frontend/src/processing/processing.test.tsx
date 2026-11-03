import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import { visibleNav } from '../lib/nav';
import TradingPage from '../stock/TradingPage';
import BatchesPage from './BatchesPage';
import BatchPage from './BatchPage';
import TracePage from './TracePage';
import type { Batch, BatchReport, Figures, TracedLot } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const all: Permission[] = ['TENANT_READ', 'PROCESSING_READ', 'PROCESSING_WRITE', 'FINANCE_READ', 'FINANCE_WRITE', 'INVENTORY_READ', 'INVENTORY_WRITE'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['PROCESSING'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const batch: Batch = { id: 3, costObjectId: 30, code: 'RM-2610-01', name: 'Rice milling Oct 2026', kind: 'MILLING', farmId: 1, farmName: 'Main farm', mainItemId: 5,
  mainItemName: 'Rice', startedOn: '2026-10-01', expectedEnd: '2026-10-10', finishedOn: null, status: 'OUTPUT', notes: null };
const figures: Figures = { from: '2026-10-01', to: '2026-10-31', days: 4, inputKg: 2000, outputs: [
  { role: 'MAIN', itemId: 5, name: 'Rice', kg: 1300, yieldPercent: 65 }, { role: 'BYPRODUCT', itemId: 6, name: 'Bran', kg: 160, yieldPercent: 8 },
  { role: 'WASTE', itemId: null, name: 'Husk', kg: 340, yieldPercent: 17 }], outputKg: 1800, lossKg: 200, lossPercent: 10, mainKg: 1300, costs: 37000,
  revenue: 83400, profit: 46400, byproductSales: 5400, mainCostPerKg: 24.31, rawCostPerKg: 15 };
const report: BatchReport = { batch, period: figures, whole: figures };
const trace: TracedLot[] = [{ lotId: 1, lotNo: 'RM-2610-01-1', itemId: 5, itemName: 'Rice', baseUnit: 'kg',
  origin: { kind: 'BATCH', date: '2026-10-05', documentNumber: 'PROD/1', costObjectId: 30, activityName: 'Rice milling Oct 2026', activityKind: 'PROCESSING_BATCH',
    partyName: null, batchCode: 'RM-2610-01' },
  inputs: [{ lotId: 2, lotNo: 'SAMBA-2026-H1', itemId: 4, itemName: 'Paddy', baseUnit: 'kg', origin: { kind: 'PRODUCED', date: '2026-09-30', documentNumber: 'PROD/0',
    costObjectId: 7, activityName: 'Samba 2026', activityKind: 'CROP_CYCLE', partyName: null, batchCode: null }, inputs: [], usedIn: [], sales: [] }],
  usedIn: [], sales: [{ date: '2026-10-06', documentNumber: 'SALE/1', partyName: 'Lakshmi Stores', quantity: 1000 }] }];

function routeApi(url: string, init?: { method?: string; body?: unknown }) {
  if (url.endsWith('/processing/batches') && init?.method === 'POST') return Promise.resolve(batch);
  if (url.endsWith('/processing/batches')) return Promise.resolve([batch]);
  if (url.includes('/processing/batches/3/report')) return Promise.resolve(report);
  if (url.endsWith('/batches/3/steps') && init?.method === 'POST') return Promise.resolve({ id: 1 });
  if (url.endsWith('/batches/3/steps')) return Promise.resolve([]);
  if (url.endsWith('/batches/3/outputs') && init?.method === 'POST') return Promise.resolve({ id: 1 });
  if (url.endsWith('/batches/3/outputs')) return Promise.resolve([{ id: 1, date: '2026-10-05', role: 'MAIN', itemId: 5, itemName: 'Rice', description: null, quantity: 1300,
    unit: 'kg', kg: 1300, grade: null, lotNo: 'RM-2610-01-1', documentId: 11, documentNumber: 'PROD/1', reversed: false }]);
  if (url.includes('/processing/trace')) return Promise.resolve(trace);
  if (url.includes('/stock/internal-transfers')) return Promise.resolve([]);
  if (url.includes('/stock/reports/trading')) return Promise.resolve([{ itemId: 9, itemName: 'Turmeric (bought)', baseUnit: 'kg', boughtQuantity: 2000, boughtValue: 160000,
    soldQuantity: 1500, revenue: 150000, costOfSales: 120000, margin: 30000, marginPercent: 20, stockQuantity: 500, stockValue: 40000 }]);
  if (url.endsWith('/stock/items')) return Promise.resolve([{ id: 5, name: 'Rice', kind: 'PRODUCE', active: true }, { id: 6, name: 'Bran', kind: 'PRODUCE', active: true }]);
  if (url.endsWith('/farms')) return Promise.resolve([{ id: 1, tenantId: 9, name: 'Main farm', farmType: 'MIXED', country: 'IN', active: true }]);
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter>);
}
const calls = (pred: (url: string, init?: { method?: string; body?: unknown; headers?: Record<string, string> }) => boolean) =>
  apiMock.mock.calls.filter(([u, i]) => pred(String(u), i));

describe('processing web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    tenant.enabledModules = ['PROCESSING'];
    setLanguage('en');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  it('shows Processing in the menu only with its module', () => {
    expect(visibleNav(tenant).map(n => n.key)).toContain('processing');
    tenant.enabledModules = ['CROP'];
    expect(visibleNav(tenant).map(n => n.key)).not.toContain('processing');
  });

  it('starts a batch with a code and a main product', async () => {
    renderAt('/t/9/processing', <BatchesPage />, '/t/:tenantId/processing');
    expect(await screen.findByRole('link', { name: 'RM-2610-01' })).toHaveAttribute('href', '/t/9/processing/batches/3');
    await userEvent.click(screen.getByRole('button', { name: 'Start batch' }));
    const form = within(screen.getByRole('button', { name: 'Save' }).closest('form')!);
    await userEvent.type(form.getByLabelText('Code'), 'op-1');
    await userEvent.type(form.getByLabelText('Batch'), 'Oil pressing');
    await userEvent.selectOptions(await form.findByLabelText('Main product'), '5');
    await waitFor(() => expect(form.getByLabelText('Farm')).toHaveValue('1'));
    await userEvent.click(form.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/processing/batches') && i?.method === 'POST')).toHaveLength(1));
    expect(calls((u, i) => u.endsWith('/processing/batches') && i?.method === 'POST')[0][1]!.body).toMatchObject({ code: 'OP-1', name: 'Oil pressing', kind: 'MILLING',
      mainItemId: 5, farmId: 1 });
  });

  it('shows the weight balance and cost per kg, and records a drying step and waste', async () => {
    renderAt('/t/9/processing/batches/3', <BatchPage />, '/t/:tenantId/processing/batches/:batchId');
    expect(await screen.findByRole('heading', { name: /RM-2610-01 · Rice milling/ })).toBeInTheDocument();
    expect(screen.getAllByText('65%').length).toBeGreaterThan(0);
    expect(screen.getAllByText('₹24.31').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'RM-2610-01-1' })).toHaveAttribute('href', '/t/9/processing/trace?lotNo=RM-2610-01-1');

    const step = within(screen.getByRole('button', { name: 'Record step' }).closest('form')!);
    await userEvent.type(step.getByLabelText(/kg in/), '2000');
    await userEvent.type(step.getByLabelText(/kg out/), '1920');
    await userEvent.type(step.getByLabelText(/Moisture before/), '17');
    await userEvent.click(step.getByRole('button', { name: 'Record step' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/batches/3/steps') && i?.method === 'POST')).toHaveLength(1));
    expect(calls((u, i) => u.endsWith('/batches/3/steps') && i?.method === 'POST')[0][1]!.body)
      .toMatchObject({ kind: 'DRYING', kgIn: '2000', kgOut: '1920', moistureBefore: '17', moistureAfter: null });

    const out = within(screen.getByRole('button', { name: 'Record output' }).closest('form')!);
    await userEvent.selectOptions(out.getByLabelText('Role'), 'WASTE');
    await userEvent.type(out.getByLabelText('What the waste is'), 'Husk');
    await userEvent.type(out.getByLabelText('kg'), '340');
    await userEvent.click(out.getByLabelText(/This is the last output/));
    await userEvent.click(out.getByRole('button', { name: 'Record output' }));
    await waitFor(() => expect(calls((u, i) => u.endsWith('/batches/3/outputs') && i?.method === 'POST')).toHaveLength(1));
    const [, init] = calls((u, i) => u.endsWith('/batches/3/outputs') && i?.method === 'POST')[0];
    expect(init!.body).toMatchObject({ role: 'WASTE', itemId: null, description: 'Husk', quantity: '340', finish: true });
    expect(init!.headers!['Idempotency-Key']).toBeTruthy();
  });

  it('traces a lot back to the field and forward to the customer', async () => {
    renderAt('/t/9/processing/trace?lotNo=RM-2610-01-1', <TracePage />, '/t/:tenantId/processing/trace');
    expect(await screen.findByText(/made by batch RM-2610-01/)).toBeInTheDocument();
    expect(screen.getByText(/harvested from Samba 2026/)).toBeInTheDocument();
    expect(screen.getByText(/sold 1,000 kg to Lakshmi Stores/)).toBeInTheDocument();
  });

  it('shows the trading margin, and only to readers of money', async () => {
    renderAt('/t/9/stock/trading', <TradingPage />, '/t/:tenantId/stock/trading');
    expect(await screen.findByText('Turmeric (bought)')).toBeInTheDocument();
    expect(screen.getAllByText('₹30,000.00').length).toBeGreaterThan(0);
    expect(screen.getByText('20%')).toBeInTheDocument();
    tenant.permissions = ['TENANT_READ', 'INVENTORY_READ'];
    renderAt('/t/9/stock/trading', <TradingPage />, '/t/:tenantId/stock/trading');
    expect(screen.getByText('The trading margin needs permission to see money.')).toBeInTheDocument();
  });

  it('hides money without finance access and blocks product output without stock permission', async () => {
    tenant.permissions = ['TENANT_READ', 'PROCESSING_READ', 'PROCESSING_WRITE', 'INVENTORY_READ'];
    renderAt('/t/9/processing/batches/3', <BatchPage />, '/t/:tenantId/processing/batches/:batchId');
    expect(await screen.findByRole('heading', { name: /RM-2610-01/ })).toBeInTheDocument();
    expect(screen.queryByText('₹24.31')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record output' })).toBeDisabled();
    expect(screen.getByText('Putting output into stock needs permission to record stock.')).toBeInTheDocument();
  });
});
