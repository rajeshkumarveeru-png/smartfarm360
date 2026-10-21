import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import AssetPage from '../assets/AssetPage';
import AssetsPage from '../assets/AssetsPage';
import DepreciationPage from '../assets/DepreciationPage';
import type { Asset } from '../assets/types';
import AttendancePage from './AttendancePage';
import PayPage from './PayPage';
import WorkforceLayout from './WorkforceLayout';
import type { Muster, Worker } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const all: Permission[] = ['WORKFORCE_READ', 'WORKFORCE_WRITE', 'PAYROLL_MANAGE', 'ASSETS_READ', 'ASSETS_WRITE', 'CROP_READ', 'FINANCE_READ',
  'FINANCE_WRITE', 'FINANCE_MANAGE'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['CROP', 'WORKFORCE', 'ASSETS'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const murugan: Worker = { id: 1, partyId: 21, name: 'Murugan', phone: null, village: null, payType: 'DAILY', rate: 500, pieceUnit: null, joinedOn: '2026-01-01',
  leftOn: null, active: true, usualWork: null, notes: null, owed: 6000, advance: 2000, rates: [{ effectiveFrom: '2026-01-01', rate: 500, pieceUnit: null }] };
const lakshmi: Worker = { ...murugan, id: 2, partyId: 22, name: 'Lakshmi', owed: 0, advance: 0 };
const muster: Muster = { date: '2026-10-20', workers: [
  { workerId: 1, workerName: 'Murugan', payType: 'DAILY', status: null, hours: null, costObjectId: null, costObjectName: null, notes: null, locked: false, paidByBill: false },
  { workerId: 2, workerName: 'Lakshmi', payType: 'DAILY', status: 'PRESENT', hours: null, costObjectId: null, costObjectName: null, notes: null, locked: true, paidByBill: true }] };
const tractor: Asset = { id: 3, name: 'Tractor', kind: 'VEHICLE', farmId: null, farmName: null, serialNo: 'TN-01', sourceDocumentId: 70, sourceNumber: 'BILL-0007',
  cost: 600000, salvageValue: 60000, usefulLifeMonths: 120, inUseFrom: '2026-04-10', depreciationBefore: 0, accumulated: 9000, bookValue: 591000,
  monthlyDepreciation: 4500, lastDepreciatedMonth: '2026-06', status: 'IN_USE', disposalDocumentId: null, costObjectId: null, costObjectName: null, notes: null };
const cycles = { items: [{ name: 'Samba 2026', status: 'GROWING', costObjectId: 11 }], page: 0, size: 100, totalItems: 1, totalPages: 1 };

function routeApi(url: string, init?: { method?: string }) {
  if (url.includes('/cultivation-cycles?')) return Promise.resolve(cycles);
  if (url.includes('/workforce/attendance/')) return Promise.resolve(muster);
  if (url.includes('/workforce/piece-work')) return Promise.resolve([]);
  if (url.includes('/workforce/workers')) return Promise.resolve([murugan, lakshmi]);
  if (url.endsWith('/pay-runs/preview')) {
    return Promise.resolve({ from: '2026-09-01', to: '2026-09-30', total: 6000, skipped: ['PARTIAL_MONTH:Selvi'], workers: [{ workerId: 1, name: 'Murugan',
      payType: 'DAILY', days: 12, timePay: 6000, piecePay: 0, extraPay: 0, gross: 6000, advance: 2000, owedBefore: 0, notes: [],
      costs: [{ costObjectId: 11, costObjectName: 'Samba 2026', days: 10, amount: 5000 }, { costObjectId: 1, costObjectName: null, days: 2, amount: 1000 }] }] });
  }
  if (url.endsWith('/wage-payments')) return Promise.resolve([{ documentId: 80, number: 'WAGE-0001', workerId: 1, amount: 4000 }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  if (url.endsWith('/finance/parties')) return Promise.resolve([]);
  if (url.endsWith('/depreciation-runs/preview')) return Promise.resolve({ month: '2026-07', total: 4500, assets: [{ assetId: 3, name: 'Tractor', amount: 4500, bookValueAfter: 586500 }] });
  if (url.endsWith('/depreciation-runs') && init?.method === 'POST') return Promise.resolve({ summary: { id: 91 } });
  if (url.endsWith('/depreciation-runs')) return Promise.resolve([{ month: '2026-06', documentId: 90, number: 'DEP-0002', status: 'POSTED', total: 4500 }]);
  if (url.endsWith('/assets/reports/reconciliation')) return Promise.resolve({ registerCost: 600000, registerAccumulated: 9000, ledgerCost: 650000,
    ledgerAccumulated: 9000, unregisteredCost: 50000, manualDepreciation: 0, unexplained: 0 });
  if (url.endsWith('/assets/unregistered')) return Promise.resolve([{ lineId: 44, documentId: 71, documentType: 'BILL', number: 'BILL-0008', date: '2026-08-01',
    description: 'Sprayer', categoryId: 6, categoryCode: 'EQUIPMENT', categoryName: null, amount: 50000 }]);
  if (/\/assets\?/.test(url)) return Promise.resolve([tractor]);
  if (url.endsWith('/assets/3')) return Promise.resolve(tractor);
  if (url.endsWith('/assets/3/schedule')) return Promise.resolve([{ month: '2026-05', amount: 4500, bookValueAfter: 595500, posted: true },
    { month: '2026-07', amount: 4500, bookValueAfter: 586500, posted: false }]);
  if (url.includes('/assets/3/costs')) return Promise.resolve({ assetId: 3, from: '2026-01-01', to: '2026-10-20', depreciation: 9000, runningTotal: 3000, hours: 10,
    costPerHour: 1200, runningCosts: [{ accountCode: '5300', categoryId: 9, categoryCode: null, categoryName: 'Diesel', amount: 3000 }] });
  if (init?.method === 'POST' && url.endsWith('/assets')) return Promise.resolve({ ...tractor, id: 4, name: 'Sprayer' });
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /><Route path="*" element={<p>navigated</p>} /></Routes></MemoryRouter>);
}
const calls = (suffix: string, method = 'POST') => apiMock.mock.calls.filter(([u, i]) => String(u).endsWith(suffix) && i?.method === method);

describe('workforce web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    setLanguage('en');
  });

  it('marks everyone present on a crop and leaves a day already in a pay run alone', async () => {
    renderAt('/t/9/workforce', <AttendancePage />, '/t/:tenantId/workforce');
    expect(await screen.findByLabelText('Day for Murugan')).toBeEnabled();
    expect(screen.getByLabelText('Day for Lakshmi')).toBeDisabled();
    expect(screen.getByText(/also on a labour bill/)).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'All present on Samba 2026' }));
    expect(screen.getByLabelText('Worked on, Murugan')).toHaveValue('11');
    await userEvent.click(screen.getByRole('button', { name: 'Save attendance' }));
    // The page opens on today's date, so match any attendance PUT
    const puts = () => apiMock.mock.calls.filter(([u, i]) => String(u).includes('/attendance/') && i?.method === 'PUT');
    await waitFor(() => expect(puts().length).toBe(1));
    const put = puts()[0];
    expect(put?.[1].body).toEqual({ entries: [{ workerId: 1, status: 'PRESENT', costObjectId: 11, hours: null }] });
  });

  it('previews a pay run with where the cost goes, then pays wages recovering the advance', async () => {
    renderAt('/t/9/workforce/pay', <PayPage />, '/t/:tenantId/workforce/pay');
    await userEvent.click(await screen.findByRole('button', { name: 'Calculate' }));
    expect(await screen.findByText('Samba 2026: ₹5,000.00 · General farm work: ₹1,000.00')).toBeInTheDocument();
    expect(screen.getByText(/Selvi is paid monthly/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Post pay run' }));
    await waitFor(() => expect(calls('/pay-runs').length).toBe(1));
    expect(calls('/pay-runs')[0][1].headers['Idempotency-Key']).toBeTruthy();

    expect(screen.getByLabelText('Pay now, Murugan')).toHaveValue('4000.00');
    expect(screen.getByLabelText('Recover from advance, Murugan')).toHaveValue('2000.00');
    expect(screen.queryByLabelText('Pay now, Lakshmi')).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getAllByLabelText('Paid from')[0], '5');
    await userEvent.click(screen.getByRole('button', { name: 'Record payments' }));
    await waitFor(() => expect(calls('/wage-payments').length).toBe(1));
    expect(calls('/wage-payments')[0][1].body).toMatchObject({ financialAccountId: 5, payments: [{ workerId: 1, cash: '4000.00', advanceRecovered: '2000.00' }] });
    expect(await screen.findByText('Payments recorded: 1.')).toBeInTheDocument();
  });

  it('hides the pay tab from someone who cannot manage payroll', async () => {
    tenant.permissions = ['WORKFORCE_READ', 'WORKFORCE_WRITE'];
    renderAt('/t/9/workforce', <WorkforceLayout />, '/t/:tenantId/workforce');
    expect(screen.getByRole('link', { name: 'Attendance' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Pay' })).not.toBeInTheDocument();
  });
});

describe('assets web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    setLanguage('en');
  });

  it('lists the register and registers an asset cost already in the accounts', async () => {
    renderAt('/t/9/assets', <AssetsPage />, '/t/:tenantId/assets');
    expect(await screen.findByText('Tractor')).toBeInTheDocument();
    expect(screen.getByText('₹5,91,000.00')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Register' }));
    expect(screen.getByLabelText('Asset')).toHaveValue('Sprayer');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/useful life in months/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Useful life (months)'), '60');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls('/assets').length).toBe(1));
    expect(calls('/assets')[0][1].body).toMatchObject({ sourceLineId: 44, asset: { name: 'Sprayer', kind: 'EQUIPMENT', usefulLifeMonths: 60, inUseFrom: '2026-08-01' } });
  });

  it('shows an asset schedule and cost per hour', async () => {
    renderAt('/t/9/assets/3', <AssetPage />, '/t/:tenantId/assets/:assetId');
    expect(await screen.findByText('through 2026-06')).toBeInTheDocument();
    expect(screen.getByText('2026-07')).toBeInTheDocument();
    expect(await screen.findByText('Cost per hour (10 hours used)')).toBeInTheDocument();
    expect(screen.getByText('₹12,000.00')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sell or scrap' })).toBeInTheDocument();
  });

  it('previews and posts a month of depreciation, and shows the register agrees with the accounts', async () => {
    renderAt('/t/9/assets/depreciation', <DepreciationPage />, '/t/:tenantId/assets/depreciation');
    expect(await screen.findByText('The register agrees with the accounts.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Calculate' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Post depreciation for 2026-07' }));
    await waitFor(() => expect(calls('/depreciation-runs').length).toBe(1));
    expect(await screen.findByText('Depreciation for 2026-07 posted.')).toBeInTheDocument();
  });

  it('does not offer selling or depreciation runs without permission to manage the accounts', async () => {
    tenant.permissions = ['ASSETS_READ', 'ASSETS_WRITE'];
    renderAt('/t/9/assets/3', <AssetPage />, '/t/:tenantId/assets/:assetId');
    expect(await screen.findByText('Tractor')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sell or scrap' })).not.toBeInTheDocument();
  });
});
