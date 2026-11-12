import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import DashboardPage from './DashboardPage';
import { BalanceSheetPage, CategoriesPage } from './MoneyReports';
import ReportsCatalogPage from './ReportsCatalogPage';
import { fiscalYearLabel, fiscalYearStart, rangeFor } from './ranges';
import type { Dashboard } from './types';

const apiMock = vi.fn();
const downloadMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args),
  download: (...args: unknown[]) => downloadMock(...args) }));

const all: Permission[] = ['TENANT_READ', 'FINANCE_READ', 'FINANCE_MANAGE', 'CROP_READ', 'INVENTORY_READ', 'WORKFORCE_READ', 'ASSETS_READ'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['CROP', 'WORKFORCE', 'ASSETS'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const months = Array.from({ length: 12 }, (_, i) => ({ month: `2025-${String(i + 1).padStart(2, '0')}`, revenue: i === 11 ? 90000 : 0, costs: 5000, operatingProfit: i === 11 ? 85000 : -5000 }));
const dashboard: Dashboard = {
  asOf: '2026-07-31', actual: true,
  money: { fiscalYearFrom: '2026-04-01', cashTotal: 130000, accounts: [{ id: 2, name: 'Bank', kind: 'BANK', active: true, balance: 130000 }], receivableTotal: 40000,
    overdueReceivables: 40000, payableTotal: 50000, loanTotal: 0, monthRevenue: 90000, monthCosts: 8000, monthOperatingProfit: 82000, yearRevenue: 90000,
    yearCosts: 60000, yearNetProfit: 30000 },
  trend: { months, topCosts: [{ accountCode: '5100', categoryId: 8, categoryCode: 'LABOUR', categoryName: null, amount: 6000, share: 75, previous: null, change: null, changePercent: null }] },
  crops: [{ cycleId: 4, name: 'Samba 2026', cropName: 'Paddy', status: 'GROWING', daysSinceSowing: 30, profitToDate: 12000, unsoldEstimate: 5000 }],
  businessProfits: [],
  attention: [{ code: 'WAGES_OWED', count: 1, amount: 4000, detail: null }, { code: 'DEPRECIATION_DUE', count: 1, amount: null, detail: '2026-08' }],
  errors: [],
};

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter>);
}

describe('dashboard and reports web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    downloadMock.mockReset();
    downloadMock.mockResolvedValue(undefined);
    tenant.permissions = all;
    setLanguage('en');
  });

  it('shows money, the 12-month trend as a chart and table, crops and what needs attention, with links', async () => {
    apiMock.mockResolvedValue(dashboard);
    renderAt('/t/9/dashboard', <DashboardPage />, '/t/:tenantId/dashboard');
    expect(await screen.findByText('₹1,30,000.00', { selector: 'b' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /last 12 months/i })).toBeInTheDocument();
    expect(screen.getAllByText('₹85,000.00').length).toBeGreaterThan(0);   // in the table under the chart
    expect(screen.getByRole('link', { name: 'Wages owed to workers: ₹4,000.00' })).toHaveAttribute('href', '/t/9/workforce/pay');
    expect(screen.getByRole('link', { name: /Depreciation for 2026-08/ })).toHaveAttribute('href', '/t/9/assets/depreciation');
    expect(screen.getByText(/unsold produce \(estimate\)/)).toBeInTheDocument();
    expect(apiMock.mock.calls[0][0]).toMatch(/^\/api\/v1\/tenants\/9\/dashboard\?asOf=/);
  });

  it('shows placeholders while loading, and an error that can be retried', async () => {
    apiMock.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(dashboard);
    renderAt('/t/9/dashboard', <DashboardPage />, '/t/:tenantId/dashboard');
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    await userEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('₹1,30,000.00', { selector: 'b' })).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledTimes(2);
  });

  it('shows no money tiles when the server leaves them out for the role', async () => {
    tenant.permissions = ['TENANT_READ', 'CROP_READ'];
    apiMock.mockResolvedValue({ ...dashboard, money: null, trend: null, attention: [] });
    renderAt('/t/9/dashboard', <DashboardPage />, '/t/:tenantId/dashboard');
    expect(await screen.findByText('Samba 2026')).toBeInTheDocument();
    expect(screen.queryByText('Cash and bank')).not.toBeInTheDocument();
    expect(screen.getByText('Nothing needs attention right now.')).toBeInTheDocument();
  });

  it('balance sheet shows both sides and warns when the accounts do not balance; exports carry format and language', async () => {
    apiMock.mockResolvedValue({ asOf: '2026-07-31', fiscalYearFrom: '2026-04-01', actual: true, cashAccounts: [],
      assets: [{ accountCode: '1010', amount: 130000 }, { accountCode: '1310', amount: -6000 }], assetTotal: 124000,
      liabilities: [{ accountCode: '2000', amount: 50000 }], liabilityTotal: 50000, equity: [{ accountCode: '3000', amount: 70000 }],
      earlierProfit: 0, yearProfit: 3000, equityTotal: 73000, liabilitiesAndEquity: 123000, difference: 1000 });
    renderAt('/t/9/reports/balance-sheet', <BalanceSheetPage />, '/t/:tenantId/reports/balance-sheet');
    expect(await screen.findByText('Bank and UPI accounts')).toBeInTheDocument();
    expect(screen.getByText('₹1,24,000.00')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('₹1,000.00');
    await userEvent.click(screen.getByRole('button', { name: /Export Excel/ }));
    expect(downloadMock.mock.calls[0][0]).toMatch(/\/finance\/reports\/balance-sheet\?asOf=.*&format=xlsx&lang=en$/);
    expect(downloadMock.mock.calls[0][1]).toMatch(/^balance-sheet-.*\.xlsx$/);
  });

  it('category comparison shows a dash where the earlier amount was zero', async () => {
    apiMock.mockResolvedValue({ from: '2026-07-01', to: '2026-07-31', kind: 'COSTS', basis: 'ACCRUAL', actual: true, compare: 'previous',
      previousFrom: '2026-06-01', previousTo: '2026-06-30', total: 6000, previousTotal: 0,
      rows: [{ accountCode: '5100', categoryId: 8, categoryCode: 'LABOUR', categoryName: null, amount: 6000, share: 100, previous: 0, change: 6000, changePercent: null }] });
    renderAt('/t/9/reports/categories', <CategoriesPage />, '/t/:tenantId/reports/categories');
    expect(await screen.findByText('Labour')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('100%')).toBeInTheDocument();
  });

  it('lists only the reports the role may open', async () => {
    tenant.permissions = ['TENANT_READ', 'FINANCE_READ'];
    renderAt('/t/9/reports', <ReportsCatalogPage />, '/t/:tenantId/reports');
    expect(screen.getByRole('link', { name: 'Balance sheet' })).toHaveAttribute('href', '/t/9/reports/balance-sheet');
    expect(screen.queryByRole('link', { name: 'Trial balance' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Wages' })).not.toBeInTheDocument();
    tenant.permissions = all;
  });

  it('quick ranges follow the April fiscal year and are labelled 2026-27', () => {
    const today = new Date(2026, 6, 15);   // 15 July 2026
    expect(fiscalYearStart(today, 4)).toEqual(new Date(2026, 3, 1));
    expect(fiscalYearLabel(new Date(2026, 3, 1), 4)).toBe('2026-27');
    expect(rangeFor('thisYear', today, 4)).toEqual({ from: '2026-04-01', to: '2026-07-15', label: '2026-27' });
    expect(rangeFor('lastYear', today, 4)).toEqual({ from: '2025-04-01', to: '2026-03-31', label: '2025-26' });
    expect(rangeFor('thisQuarter', today, 4)).toEqual({ from: '2026-07-01', to: '2026-07-15' });
    expect(rangeFor('lastMonth', new Date(2026, 0, 10), 4)).toEqual({ from: '2025-12-01', to: '2025-12-31' });
    expect(rangeFor('thisYear', new Date(2026, 1, 1), 4).label).toBe('2025-26');
  });
});
