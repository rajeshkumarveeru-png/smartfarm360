import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { BillingOverview, Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import BillingPanel, { BillingBanner } from './BillingPanel';
import PlatformPage from './PlatformPage';

const apiMock = vi.fn();
const downloadMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args),
  download: (...args: unknown[]) => downloadMock(...args) }));

const owner: Permission[] = ['TENANT_READ', 'TENANT_MANAGE', 'BILLING_VIEW', 'BILLING_MANAGE', 'FINANCE_READ', 'FINANCE_WRITE'];
const tenant: TenantSummary = { id: 9, name: 'Murugan Farms', slug: 'murugan-farms', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: owner, enabledModules: ['CROP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const plans: BillingOverview['plans'] = [
  { code: 'FREE', name: 'Free', paid: false, sortOrder: 1, limits: [{ code: 'MEMBERS', value: 5 }, { code: 'FARMS', value: 3 }], prices: [] },
  { code: 'GROWER', name: 'Grower', paid: true, sortOrder: 2, limits: [{ code: 'MEMBERS', value: 25 }, { code: 'FARMS', value: 20 }],
    prices: [{ period: 'MONTHLY', current: { amount: 499, validFrom: '2026-01-01' }, next: null }, { period: 'YEARLY', current: { amount: 4990, validFrom: '2026-01-01' }, next: null }] },
  { code: 'ESTATE', name: 'Estate', paid: true, sortOrder: 3, limits: [{ code: 'MEMBERS', value: 100 }, { code: 'FARMS', value: null }],
    prices: [{ period: 'MONTHLY', current: { amount: 1999, validFrom: '2026-01-01' }, next: null }, { period: 'YEARLY', current: { amount: 19990, validFrom: '2026-01-01' }, next: null }] },
];

function overview(over: Partial<BillingOverview> = {}): BillingOverview {
  return { state: 'TRIAL', planCode: 'GROWER', period: null, effectivePlanCode: 'GROWER', trialEndsAt: '2026-12-01T00:00:00Z', paidUntil: null, graceEndsAt: null,
    pendingPlanCode: null, pendingPeriod: null, compPlanCode: null, compUntil: null, checkoutStarted: false, upgradeStarted: false, profileComplete: true,
    banner: null, plans, ...over };
}

const invoice = { id: 41, number: 'SF/2026-27/000041', invoiceDate: '2026-11-28', description: 'SmartFarm360 Grower plan (yearly)', planCode: 'GROWER', period: 'YEARLY',
  periodStart: '2026-11-28', periodEnd: '2027-11-27', taxableAmount: 4990, cgst: 449.1, sgst: 449.1, igst: 0, total: 5888.2, refundedAmount: 0, buyerGstin: '33ABCDE1234F1Z7',
  bookedDocumentId: null, creditNotes: [] };

const calls = (path: string, method?: string) => apiMock.mock.calls.filter(([u, i]) => String(u).endsWith(path) && (method ? i?.method === method : !i?.method));

function route(state: BillingOverview) {
  let o = state;
  apiMock.mockImplementation((url: string, init?: { method?: string; body?: unknown }) => {
    if (url.endsWith('/billing') && !init?.method) return Promise.resolve(o);
    if (url.endsWith('/billing/profile') && !init?.method) return Promise.resolve({ legalName: 'Murugan Farms', addressLine1: '12 Canal Road', addressLine2: null, city: 'Erode',
      pincode: '638001', stateCode: '33', gstin: '33ABCDE1234F1Z7', email: 'murugan@example.com' });
    if (url.endsWith('/billing/profile')) return Promise.resolve({ ...(init?.body as object) });
    if (url.endsWith('/billing/invoices')) return Promise.resolve([invoice]);
    if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 5, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
    if (url.endsWith('/billing/checkout')) return Promise.resolve({ checkoutUrl: null, overview: { ...o, checkoutStarted: true } });
    if (url.endsWith('/billing/cancel')) { o = { ...o, state: 'CANCELLED' }; return Promise.resolve(o); }
    if (url.endsWith('/book')) return Promise.resolve({});
    return Promise.resolve([]);
  });
}

beforeEach(() => {
  apiMock.mockReset();
  downloadMock.mockReset();
  tenant.permissions = owner;
  setLanguage('en');
});

describe('plan and billing (billing design, sections 3–8)', () => {
  it('shows the trial, the plans with prices before GST, and starts a checkout for the chosen period', async () => {
    route(overview());
    const { container } = render(<MemoryRouter><BillingPanel /></MemoryRouter>);
    expect(await screen.findByText(/Trial until/)).toBeInTheDocument();
    expect(container.textContent).toContain('Grower · Trial');
    expect(screen.getByLabelText('State')).toHaveValue('33');
    expect(screen.getByText(/4,990.00 a year/)).toBeInTheDocument();
    expect(screen.getAllByText(/plus 18% GST/).length).toBe(2);
    expect(screen.getByText(/Farms: no limit/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Monthly' }));
    expect(screen.getByText(/499.00 a month/)).toBeInTheDocument();
    const estate = screen.getByRole('heading', { name: 'Estate' }).closest('article')!;
    await userEvent.click(within(estate as HTMLElement).getByRole('button', { name: 'Choose and pay' }));
    await waitFor(() => expect(calls('/billing/checkout', 'POST')).toHaveLength(1));
    expect(calls('/billing/checkout', 'POST')[0][1].body).toEqual({ planCode: 'ESTATE', period: 'MONTHLY' });
    expect(await screen.findByText(/A payment was started/)).toBeInTheDocument();
  });

  it('lists invoices with their GST, downloads the PDF and records an invoice in the books', async () => {
    route(overview({ state: 'ACTIVE', period: 'YEARLY', paidUntil: '2027-11-28T00:00:00Z' }));
    render(<MemoryRouter><BillingPanel /></MemoryRouter>);
    expect(await screen.findByText(/SF\/2026-27\/000041/)).toBeInTheDocument();
    expect(screen.getByText(/includes GST .*898.20/)).toBeInTheDocument();
    expect(screen.getByText(/Renews on/)).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole('button', { name: 'Download PDF' })[0]);
    expect(downloadMock).toHaveBeenCalledWith('/api/v1/tenants/9/billing/invoices/41/pdf', 'SF-2026-27-000041.pdf');
    await userEvent.selectOptions(screen.getByLabelText(/Paid from/), 'Cash in hand');
    await userEvent.click(screen.getByRole('button', { name: 'Record in my books' }));
    await waitFor(() => expect(calls('/finance/subscription-invoices/41/book', 'POST')).toHaveLength(1));
    expect(calls('/finance/subscription-invoices/41/book', 'POST')[0][1].body).toEqual({ financialAccountId: 5 });
    // A paid plan can be cancelled after confirming; it stays until the end of the paid period
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await userEvent.click(screen.getByRole('button', { name: 'Cancel the plan' }));
    expect(await screen.findByText(/It stays until the end of the paid period/)).toBeInTheDocument();
  });

  it('lets an accountant read the invoices and details but not pay or change them', async () => {
    tenant.permissions = ['TENANT_READ', 'BILLING_VIEW', 'FINANCE_READ'];
    route(overview({ state: 'ACTIVE', period: 'YEARLY', paidUntil: '2027-11-28T00:00:00Z' }));
    render(<MemoryRouter><BillingPanel /></MemoryRouter>);
    expect(await screen.findByText(/SF\/2026-27\/000041/)).toBeInTheDocument();
    expect(screen.getByLabelText('Legal name')).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Choose and pay|Switch to this plan/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record in my books' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel the plan' })).not.toBeInTheDocument();
  });

  it('warns every member when a renewal failed, in Tamil too', async () => {
    route(overview({ state: 'PAST_DUE', banner: 'PAST_DUE', graceEndsAt: '2027-12-12T00:00:00Z' }));
    setLanguage('ta');
    render(<MemoryRouter><BillingBanner tenantId={9} canManage={false} /></MemoryRouter>);
    expect(await screen.findByRole('status')).toHaveTextContent('புதுப்பிப்புக் கட்டணம் தோல்வியடைந்தது');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});

describe('the console Billing tab (P12)', () => {
  it('asks for a reason before refunding and shows what the reconciliation found', async () => {
    apiMock.mockImplementation((url: string, init?: { method?: string }) => {
      if (url === '/api/v1/platform/billing') return Promise.resolve({ subscriptions: { TRIAL: 2, ACTIVE: 1 }, unappliedEvents: [],
        lastReconciliation: { id: 1, ranAt: '2026-11-29T21:15:00Z', ok: false, error: null, differences: [{ kind: 'PAYMENT_NOT_RECORDED', gatewayId: 'pay_x', tenantId: 9, detail: '588.82' }] } });
      if (url.startsWith('/api/v1/platform/billing/payments') && !init?.method) return Promise.resolve([{ id: 7, tenantId: 9, businessName: 'Murugan Farms', kind: 'RENEWAL',
        amount: 5888.2, refundedAmount: 0, method: 'upi', capturedAt: '2026-11-28T10:00:00Z', invoiceId: 41, invoiceNumber: 'SF/2026-27/000041' }]);
      if (url.startsWith('/api/v1/platform/billing/subscriptions')) return Promise.resolve([]);
      if (url.startsWith('/api/v1/platform/tenants')) return Promise.resolve({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 });
      return Promise.resolve([]);
    });
    render(<MemoryRouter><PlatformPage /></MemoryRouter>);
    await userEvent.click(await screen.findByRole('tab', { name: 'Billing' }));
    expect(await screen.findByText(/Payment not recorded: pay_x/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Refund' }));
    const apply = screen.getByRole('button', { name: 'Apply' });
    expect(apply).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), 'Charged twice');
    await userEvent.click(apply);
    await waitFor(() => expect(calls('/payments/7/refund', 'POST')).toHaveLength(1));
    expect(calls('/payments/7/refund', 'POST')[0][1].body).toEqual({ amount: '5888.2', reason: 'Charged twice' });
  });
});
