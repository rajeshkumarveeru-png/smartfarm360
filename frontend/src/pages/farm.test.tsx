import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import NewRecordPage from '../finance/NewRecordPage';
import FarmPage from './FarmPage';
import FarmsPage from './FarmsPage';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const owner: Permission[] = ['FARM_READ', 'FARM_WRITE', 'FINANCE_READ', 'FINANCE_WRITE'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: owner, enabledModules: ['CROP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const farm = { id: 2, tenantId: 9, name: 'Yerpathi', farmType: 'CROP', village: 'Seplanatham', district: 'Cuddalore', state: 'Tamil Nadu', country: 'IN', active: true };

function routeApi(url: string, init?: { method?: string }) {
  if (init?.method === 'POST') return Promise.resolve({ summary: { id: 70 } });
  if (url.endsWith('/farms')) return Promise.resolve([farm]);
  if (url.endsWith('/farms/2')) return Promise.resolve(farm);
  if (url.endsWith('/farms/2/land-parcels')) return Promise.resolve([{ id: 5, tenantId: 9, farmId: 2, name: 'North field', area: 1.7, areaUnit: 'ACRE', active: true }]);
  if (url.includes('/reports/income-statement')) {
    return Promise.resolve({ operatingRevenueTotal: 50000, operatingCostTotal: 12000, operatingProfit: 38000, operatingRevenue: [], operatingCosts: [] });
  }
  if (url.includes('/reports/categories')) {
    return Promise.resolve({ rows: [{ accountCode: '5000', categoryId: 1, categoryCode: 'SEEDS', categoryName: null, amount: 12000, share: 100 }], total: 12000 });
  }
  if (url.includes('/finance/documents?farmId=2')) {
    return Promise.resolve({ items: [{ id: 41, type: 'BILL', status: 'POSTED', number: 'BILL/2026-27/0004', docDate: '2026-07-02', partyName: 'Seed shop',
      financialAccountName: null, totalAmount: 12000, paymentStatus: 'PAID' }], page: 0, size: 10, totalItems: 1, totalPages: 1 });
  }
  if (url.endsWith('/finance/categories')) return Promise.resolve([{ id: 1, code: 'SEEDS', name: null, kind: 'EXPENSE', accountCode: '5000', system: true, active: true }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  if (url.endsWith('/finance/settings')) return Promise.resolve({ lockDate: null, fiscalYearStartMonth: 4 });
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /><Route path="*" element={<p>navigated</p>} /></Routes></MemoryRouter>);
}

describe('farm workspace', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = owner;
    setLanguage('en');
  });

  it('lists farms as cards that open the farm', async () => {
    renderAt('/t/9/farms', <FarmsPage />, '/t/:tenantId/farms');
    expect(await screen.findByRole('link', { name: /Yerpathi/ })).toHaveAttribute('href', '/t/9/farms/2');
  });

  it('shows the farm money, its records and land, and books new expenses and sales to the farm', async () => {
    renderAt('/t/9/farms/2', <FarmPage />, '/t/:tenantId/farms/:farmId');
    expect(await screen.findByRole('heading', { name: 'Yerpathi' })).toBeInTheDocument();
    expect(await screen.findByText('₹38,000.00')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Expense bill · BILL\/2026-27\/0004/ })).toHaveAttribute('href', '/t/9/finance/records/41');
    expect(screen.getByText('North field')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add expense' })).toHaveAttribute('href', '/t/9/finance/new/expense?farm=2');
    expect(screen.getByRole('link', { name: 'Record sale' })).toHaveAttribute('href', '/t/9/finance/new/sale?farm=2');
    expect(apiMock.mock.calls.some(([u]) => /income-statement\?from=.*&farmId=2$/.test(u))).toBe(true);
  });

  it('shows land without money to people who cannot read finance', async () => {
    tenant.permissions = ['FARM_READ'];
    renderAt('/t/9/farms/2', <FarmPage />, '/t/:tenantId/farms/:farmId');
    expect(await screen.findByText('North field')).toBeInTheDocument();
    expect(screen.getByText(/needs finance access/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Add expense' })).not.toBeInTheDocument();
    expect(apiMock.mock.calls.some(([u]) => String(u).includes('/finance/'))).toBe(false);
  });

  it('an expense opened from a farm is booked to that farm', async () => {
    renderAt('/t/9/finance/new/expense?farm=2', <NewRecordPage />, '/t/:tenantId/finance/new/:kind');
    await waitFor(() => expect(screen.getByLabelText('Farm')).toHaveValue('2'));
    await userEvent.selectOptions(screen.getByLabelText('Paid from'), '5');
    await userEvent.selectOptions(screen.getByLabelText('Category'), '1');
    await userEvent.type(screen.getByLabelText('Amount (₹)'), '450');
    await userEvent.click(screen.getByRole('button', { name: 'Save and post' }));
    await waitFor(() => expect(screen.getByText('navigated')).toBeInTheDocument());
    const [, init] = apiMock.mock.calls.find(([, i]) => i?.method === 'POST')!;
    expect(init.body).toMatchObject({ farmId: 2, financialAccountId: 5 });
  });
});
