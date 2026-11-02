import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import NewRecordPage from './NewRecordPage';
import RecordsPanel from './RecordsPanel';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const owner: Permission[] = ['FINANCE_READ', 'FINANCE_WRITE', 'FINANCE_REVERSE'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: owner, enabledModules: ['CROP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const doc = (id: number, status: string, number: string | null) => ({ id, type: 'BILL', status, number, docDate: '2026-07-02', partyName: null,
  financialAccountName: 'Cash in hand', totalAmount: 450, paymentStatus: status === 'POSTED' ? 'PAID' : null });

function routeApi(url: string, init?: { method?: string }) {
  if (init?.method === 'POST') return Promise.resolve({ summary: { id: 99 } });
  if (url.includes('/documents?costObjectId=7')) {
    return Promise.resolve({ items: [doc(41, 'POSTED', 'BILL/2026-27/0004'), doc(42, 'DRAFT', null)], page: 0, size: 10, totalItems: 2, totalPages: 1 });
  }
  if (url.endsWith('/documents/41')) {
    return Promise.resolve({ summary: { ...doc(41, 'REVERSED', 'BILL/2026-27/0004'), dueDate: null, partyId: null }, farmId: 2, financialAccountId: 5, notes: null,
      referenceNo: null, lines: [{ categoryId: 1, description: 'Seed', amount: 450, quantity: null, rate: null, costObjectId: 7, assetId: null, itemId: null,
        unit: null, locationId: null, lotNo: null }] });
  }
  if (url.endsWith('/finance/categories')) return Promise.resolve([{ id: 1, code: 'SEEDS', name: null, kind: 'EXPENSE', accountCode: '5000', system: true, active: true }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  if (url.endsWith('/finance/cost-objects')) return Promise.resolve([{ id: 7, kind: 'CROP_CYCLE', name: 'GroundNut', cropCycleId: 1, farmId: 2, active: true }]);
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} />
    <Route path="/t/:tenantId/finance/new/:kind" element={<p>new record form</p>} /><Route path="*" element={<p>navigated</p>} /></Routes></MemoryRouter>);
}

describe('records panel', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = owner;
    setLanguage('en');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  const panel = <RecordsPanel filter="costObjectId=7" prefill="farm=2&activity=7" title="Money for this cycle" />;

  it('lists the activity records with links to add more, booked to the same farm and activity', async () => {
    renderAt('/t/9/x', panel, '/t/:tenantId/x');
    expect(await screen.findByRole('link', { name: /BILL\/2026-27\/0004/ })).toHaveAttribute('href', '/t/9/finance/records/41');
    expect(screen.getByRole('link', { name: 'Add expense' })).toHaveAttribute('href', '/t/9/finance/new/expense?farm=2&activity=7');
    expect(screen.getByRole('link', { name: /Edit draft/ })).toHaveAttribute('href', '/t/9/finance/new/expense?draft=42');
  });

  it('discards a draft and reverses a posted record, after asking', async () => {
    renderAt('/t/9/x', panel, '/t/:tenantId/x');
    await userEvent.click(await screen.findByRole('button', { name: /Discard draft/ }));
    await waitFor(() => expect(apiMock).toHaveBeenCalledWith('/api/v1/tenants/9/finance/documents/42/cancel', { method: 'POST' }));
    await userEvent.click(screen.getByRole('button', { name: /^Reverse BILL/ }));
    await waitFor(() => expect(apiMock.mock.calls.some(([u, i]) => u === '/api/v1/tenants/9/finance/documents/41/reverse' && i?.method === 'POST')).toBe(true));
    expect(window.confirm).toHaveBeenCalledTimes(2);
  });

  it('corrects a posted record: reverses it and opens a copy', async () => {
    renderAt('/t/9/x', panel, '/t/:tenantId/x');
    await userEvent.click(await screen.findByRole('button', { name: /Correct/ }));
    expect(await screen.findByText('new record form')).toBeInTheDocument();
    expect(apiMock.mock.calls.some(([u]) => u === '/api/v1/tenants/9/finance/documents/41/reverse')).toBe(true);
  });

  it('does nothing when the question is declined, and offers no changes to readers', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderAt('/t/9/x', panel, '/t/:tenantId/x');
    await userEvent.click(await screen.findByRole('button', { name: /^Reverse BILL/ }));
    expect(apiMock.mock.calls.some(([, i]) => i?.method === 'POST')).toBe(false);
  });

  it('hides the change buttons from people who can only read', async () => {
    tenant.permissions = ['FINANCE_READ'];
    renderAt('/t/9/x', panel, '/t/:tenantId/x');
    expect(await screen.findByRole('link', { name: /BILL\/2026-27\/0004/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reverse/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Add expense' })).not.toBeInTheDocument();
  });

  it('the corrected copy starts from the reversed record and is saved as a new record', async () => {
    render(<MemoryRouter initialEntries={['/t/9/finance/new/expense?copy=41&farm=2&activity=7']}><Routes>
      <Route path="/t/:tenantId/finance/new/:kind" element={<NewRecordPage />} /><Route path="*" element={<p>navigated</p>} /></Routes></MemoryRouter>);
    expect(await screen.findByText(/Correcting BILL\/2026-27\/0004/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Amount (₹)')).toHaveValue('450'));
    await userEvent.click(screen.getByRole('button', { name: 'Save and post' }));
    await waitFor(() => expect(screen.getByText('navigated')).toBeInTheDocument());
    const [url, init] = apiMock.mock.calls.find(([, i]) => i?.method === 'POST')!;
    expect(url).toBe('/api/v1/tenants/9/finance/bills');
    expect(init.body).toMatchObject({ financialAccountId: 5, lines: [{ categoryId: 1, amount: '450', costObjectId: 7 }] });
  });
});
