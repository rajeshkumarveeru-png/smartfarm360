import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import NewRecordPage from '../finance/NewRecordPage';
import StockHome from './StockHome';
import StockRecordPage from './StockRecordPage';
import type { Item, Unit } from './types';
import { isQuantity, unitChoices } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const owner: Permission[] = ['INVENTORY_READ', 'INVENTORY_WRITE', 'FINANCE_READ', 'FINANCE_WRITE'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: owner, enabledModules: ['CROP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const units: Unit[] = [{ code: 'kg', dimension: 'MASS', factor: 1 }, { code: 'quintal', dimension: 'MASS', factor: 100 }, { code: 'litre', dimension: 'VOLUME', factor: 1 }];
const urea: Item = { id: 3, name: 'Urea', kind: 'INPUT', baseUnit: 'kg', customUnitName: null, dimension: 'MASS', expenseCategoryId: null, saleCategoryId: null,
  reorderLevel: 50, trackLots: false, quantity: 40, value: 240, unitCost: 6, lowStock: true, active: true, needsReview: false, notes: null,
  packUnits: [{ id: 1, name: 'bag', factor: 45 }] };

let reconciliation = { ledgerBalance: 240, stockValue: 240, difference: 0 };

function routeApi(url: string, init?: { method?: string }) {
  if (init?.method === 'POST') return Promise.resolve({ document: { summary: { id: 77, number: 'ISSUE/2026-27/0001' } }, warnings: [], summary: { id: 78 } });
  if (url.endsWith('/stock/items')) return Promise.resolve([urea]);
  if (url.endsWith('/stock/units')) return Promise.resolve(units);
  if (url.endsWith('/stock/locations')) return Promise.resolve([{ id: 1, name: 'Main store', farmId: null, isDefault: true, active: true }]);
  if (url.includes('/reports/alerts')) return Promise.resolve({ lowStock: [urea], expiringLots: [], expiryWarningDays: 30 });
  if (url.includes('/reports/reconciliation')) return Promise.resolve(reconciliation);
  if (url.includes('/reports/on-hand')) {
    return Promise.resolve({ asOf: '2026-07-31', items: [{ item: urea, locations: [{ locationId: 1, locationName: 'Main store', lotId: null, lotNo: null, expiryDate: null, quantity: 40 }] }], totalValue: 240 });
  }
  if (url.endsWith('/finance/categories')) return Promise.resolve([{ id: 1, code: 'FERTILIZER', name: null, kind: 'EXPENSE', accountCode: '5000', system: true, active: true }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /><Route path="*" element={<p>navigated</p>} /></Routes></MemoryRouter>);
}

describe('stock web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = owner;
    reconciliation = { ledgerBalance: 240, stockValue: 240, difference: 0 };
    setLanguage('en');
  });

  it('offers units of the same kind and the item pack units, and accepts quantities with up to four decimals', () => {
    expect(unitChoices(urea, units).map(u => u.value)).toEqual(['kg', 'quintal', 'bag']);
    expect(unitChoices({ ...urea, baseUnit: 'piece', customUnitName: 'bundle', dimension: 'COUNT', packUnits: [] }, units))
      .toEqual([{ value: 'piece', label: 'bundle' }]);
    expect(isQuantity('12.5')).toBe(true);
    expect(isQuantity('0.0001')).toBe(true);
    expect(isQuantity('0')).toBe(false);
    expect(isQuantity('0', true)).toBe(true);
    expect(isQuantity('1.23456')).toBe(false);
    expect(isQuantity('-1')).toBe(false);
  });

  it('checks a stock use before sending it and sends the quantity as an exact string in the chosen unit', async () => {
    renderAt('/t/9/stock/new/issue', <StockRecordPage />, '/t/:tenantId/stock/new/:kind');
    const post = await screen.findByRole('button', { name: 'Save and post' });
    await userEvent.click(post);
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a stock item for every line.');

    await userEvent.selectOptions(screen.getByLabelText('Stock item'), '3');
    await userEvent.type(screen.getByLabelText('Quantity'), '2.5');
    await userEvent.selectOptions(screen.getByLabelText('Unit'), 'bag');
    await userEvent.click(post);

    await waitFor(() => expect(screen.getByText('navigated')).toBeInTheDocument());
    const [url, init] = apiMock.mock.calls.find(([, i]) => i?.method === 'POST')!;
    expect(url).toBe('/api/v1/tenants/9/stock/issues');
    expect(init.headers['Idempotency-Key']).toMatch(/.{8,}/);
    expect(init.body).toMatchObject({ locationId: null, costObjectId: null, lines: [{ itemId: 3, quantity: '2.5', unit: 'bag', lotNo: null }] });
  });

  it('shows stock on hand, low stock, and warns when the accounts and the store disagree', async () => {
    reconciliation = { ledgerBalance: 250, stockValue: 240, difference: 10 };
    renderAt('/t/9/stock', <StockHome />, '/t/:tenantId/stock');
    expect(await screen.findByText('Total stock value')).toBeInTheDocument();
    expect(screen.getAllByText('Low stock').length).toBeGreaterThan(0);
    expect(screen.getByRole('alert')).toHaveTextContent('differs from the value of stock on hand');
    expect(screen.getByRole('link', { name: 'Use on the farm' })).toBeInTheDocument();
  });

  it('hides stock actions from people who can only read stock', async () => {
    tenant.permissions = ['INVENTORY_READ'];
    renderAt('/t/9/stock', <StockHome />, '/t/:tenantId/stock');
    expect(await screen.findByText('Total stock value')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Use on the farm' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Return to supplier' })).not.toBeInTheDocument();
  });

  it('sends a bill line for a stock item with its unit and no category', async () => {
    renderAt('/t/9/finance/new/expense', <NewRecordPage />, '/t/:tenantId/finance/new/:kind');
    await userEvent.selectOptions(await screen.findByLabelText('Paid from'), '5');
    await waitFor(() => expect(screen.getByLabelText(/Stock item/)).toBeInTheDocument());
    await userEvent.selectOptions(screen.getByLabelText(/Stock item/), '3');
    expect(screen.queryByLabelText('Category')).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Quantity'), '10');
    await userEvent.type(screen.getByLabelText('Rate (₹)'), '270');
    await userEvent.selectOptions(screen.getByLabelText('Unit'), 'bag');
    await userEvent.click(screen.getByRole('button', { name: 'Save and post' }));
    await waitFor(() => expect(screen.getByText('navigated')).toBeInTheDocument());
    const [, init] = apiMock.mock.calls.find(([, i]) => i?.method === 'POST')!;
    expect(init.body.lines[0]).toMatchObject({ categoryId: null, itemId: 3, quantity: '10', rate: '270', unit: 'bag' });
  });
});
