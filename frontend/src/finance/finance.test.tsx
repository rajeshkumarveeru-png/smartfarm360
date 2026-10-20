import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import NewRecordPage, { isAmount } from './NewRecordPage';
import ReportsPage from './ReportsPage';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: ['FINANCE_READ', 'FINANCE_WRITE', 'FINANCE_REVERSE', 'FINANCE_MANAGE'], enabledModules: ['CROP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const masterData: Record<string, unknown> = {
  categories: [{ id: 1, code: 'SEEDS', name: null, kind: 'EXPENSE', accountCode: '5000', system: true, active: true },
    { id: 2, code: 'CROP_SALE', name: null, kind: 'INCOME', accountCode: '4000', system: true, active: true }],
  accounts: [{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }],
  parties: [], 'cost-objects': [], loans: [],
};

function routeApi(url: string, init?: { method?: string }) {
  if (init?.method === 'POST') return Promise.resolve({ summary: { id: 77 } });
  const key = Object.keys(masterData).find(k => url.endsWith(`/finance/${k}`));
  if (key) return Promise.resolve(masterData[key]);
  if (url.includes('/reports/income-statement')) {
    return Promise.resolve({ from: '2026-06-01', to: '2026-06-30', basis: 'ACCRUAL', actual: true,
      operatingRevenue: [{ accountCode: '4000', categoryId: 2, categoryCode: 'CROP_SALE', categoryName: null, amount: 50000 }], operatingRevenueTotal: 50000,
      operatingCosts: [{ accountCode: '5100', categoryId: null, categoryCode: null, categoryName: null, amount: 10400 }], operatingCostTotal: 10400,
      operatingProfit: 39600, otherIncome: [], otherIncomeTotal: 0, interest: 2000, losses: 0, netProfit: 37600 });
  }
  if (url.endsWith('/farms')) return Promise.resolve([]);
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /><Route path="*" element={<p>navigated</p>} /></Routes></MemoryRouter>);
}

describe('finance web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    setLanguage('en');
  });

  it('accepts only positive rupee amounts with at most two decimals', () => {
    expect(isAmount('1200')).toBe(true);
    expect(isAmount('1200.50')).toBe(true);
    expect(isAmount('0')).toBe(false);
    expect(isAmount('12.345')).toBe(false);
    expect(isAmount('-5')).toBe(false);
    expect(isAmount('1e5')).toBe(false);
  });

  it('checks an expense before sending it and sends amounts as exact strings with an idempotency key', async () => {
    renderAt('/t/9/finance/new/expense', <NewRecordPage />, '/t/:tenantId/finance/new/:kind');
    const post = await screen.findByRole('button', { name: 'Save and post' });
    await userEvent.click(post);
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a party, or the cash/bank account');
    expect(apiMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);

    await userEvent.selectOptions(screen.getByLabelText('Paid from'), '5');
    await userEvent.selectOptions(screen.getByLabelText('Category'), '1');
    await userEvent.type(screen.getByLabelText('Amount (₹)'), '1200.50');
    await userEvent.click(post);

    await waitFor(() => expect(screen.getByText('navigated')).toBeInTheDocument());
    const [url, init] = apiMock.mock.calls.find(([, i]) => i?.method === 'POST')!;
    expect(url).toBe('/api/v1/tenants/9/finance/bills');
    expect(init.headers['Idempotency-Key']).toMatch(/.{8,}/);
    expect(init.body).toMatchObject({ partyId: null, financialAccountId: 5, post: true, lines: [{ categoryId: 1, amount: '1200.50' }] });
  });

  it('shows the income statement with its date range, actual-figures label and totals in Tamil', async () => {
    setLanguage('ta');
    renderAt('/t/9/finance/reports', <ReportsPage />, '/t/:tenantId/finance/reports');
    expect(await screen.findByText('நிகர லாபம்')).toBeInTheDocument();
    expect(screen.getByText(/உண்மையான எண்ணிக்கைகள்/)).toBeInTheDocument();
    expect(screen.getByText('பயிர் விற்பனை')).toBeInTheDocument();
    expect(document.body.textContent).toContain('37,600.00');
  });
});
