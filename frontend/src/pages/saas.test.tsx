import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import { ConsentPage, LoginPage } from './AuthPages';
import PlatformPage from './PlatformPage';
import TenantOperations from './TenantOperations';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const login = vi.fn();
const completeSecondStep = vi.fn();
const reloadUser = vi.fn();
const reloadTenants = vi.fn();
let user: Record<string, unknown> = { id: 1, email: 'lakshmi@example.com', preferredLanguage: 'en', platformAdmin: false, pendingConsents: [] };
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ login, completeSecondStep, reloadUser, reloadTenants, logout: vi.fn(), user, sessionExpired: false,
    changeLanguage: async (lang: 'en' | 'ta') => setLanguage(lang) }),
}));

const all: Permission[] = ['TENANT_READ', 'TENANT_MANAGE'];
const tenant: TenantSummary = { id: 9, name: 'Lakshmi Farms', slug: 'lakshmi-farms', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['CROP'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const renderPage = (node: React.ReactNode) => render(<MemoryRouter>{node}</MemoryRouter>);
const calls = (path: string, method?: string) => apiMock.mock.calls.filter(([u, i]) => String(u).includes(path) && (method ? i?.method === method : !i?.method));

beforeEach(() => {
  apiMock.mockReset();
  login.mockReset(); completeSecondStep.mockReset(); reloadUser.mockReset(); reloadTenants.mockReset();
  setLanguage('en');
});

describe('two-step sign-in (O6)', () => {
  it('asks for the app code after the password, and can use a recovery code instead', async () => {
    login.mockResolvedValueOnce('challenge-1');
    completeSecondStep.mockResolvedValueOnce(undefined);
    renderPage(<LoginPage />);
    await userEvent.type(screen.getByLabelText('Email'), 'arun@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'correct-horse-battery');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Enter the 6-digit code from your authenticator app.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Use a recovery code instead' }));
    await userEvent.type(screen.getByLabelText('Recovery code'), '12345-67890');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(completeSecondStep).toHaveBeenCalledWith('challenge-1', '12345-67890', true));
  });
});

describe('new terms (O12)', () => {
  it('shows what changed and records the acceptance before anything else', async () => {
    user = { ...user, pendingConsents: ['TERMS:2'] };
    apiMock.mockImplementation((url: string) => url.startsWith('/api/v1/legal/current')
      ? Promise.resolve([{ kind: 'TERMS', version: 2, language: 'en', title: 'Terms of service', body: 'The terms.', changes: 'Support access', publishedAt: '' },
        { kind: 'PRIVACY', version: 1, language: 'en', title: 'Privacy notice', body: 'Already accepted', changes: null, publishedAt: '' }])
      : Promise.resolve([]));
    renderPage(<ConsentPage />);
    expect(await screen.findByText('Terms of service')).toBeInTheDocument();
    expect(screen.getByText('What changed: Support access')).toBeInTheDocument();
    expect(screen.queryByText('Privacy notice')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Accept and continue' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Tick the box');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: 'Accept and continue' }));
    await waitFor(() => expect(calls('/api/v1/me/consents', 'POST')).toHaveLength(1));
    expect(calls('/api/v1/me/consents', 'POST')[0][1].body).toEqual({ documents: ['TERMS:2'], client: 'WEB' });
    expect(reloadUser).toHaveBeenCalled();
    user = { ...user, pendingConsents: [] };
  });
});

describe("an owner's data and support access (O7, O9, O10)", () => {
  function route() {
    // The server keeps the deletion once asked for, so later reads return it
    let deletion: Record<string, unknown> = { pending: false, requestedAt: null, scheduledFor: null, requestedByEmail: null };
    apiMock.mockImplementation((url: string, init?: { method?: string }) => {
      if (url.endsWith('/usage')) return Promise.resolve([{ limit: 'MEMBERS', used: 21, max: 25, nearLimit: true, overridden: false }]);
      if (url.endsWith('/deletion') && init?.method === 'POST') {
        deletion = { pending: true, requestedAt: '2026-10-22T05:00:00Z', scheduledFor: '2026-11-21T05:00:00Z', requestedByEmail: 'lakshmi@example.com' };
        return Promise.resolve(deletion);
      }
      if (url.endsWith('/deletion')) return Promise.resolve(deletion);
      if (url.endsWith('/support-grants') && init?.method === 'POST') return Promise.resolve({});
      if (url.endsWith('/support-grants')) return Promise.resolve([]);
      if (url.endsWith('/exports') && init?.method === 'POST') return Promise.resolve({ id: 3, status: 'QUEUED' });
      if (url.endsWith('/exports')) return Promise.resolve([{ id: 2, status: 'READY', sizeBytes: 1400, requestedAt: '2026-10-20T05:00:00Z',
        readyAt: '2026-10-20T05:01:00Z', expiresAt: '2026-10-27T05:01:00Z', requestedByEmail: 'lakshmi@example.com' }]);
      return Promise.resolve([]);
    });
  }

  it('shows usage near the limit, grants support with a reason, and starts an export', async () => {
    route();
    renderPage(<TenantOperations />);
    expect(await screen.findByText(/Members: 21 \/ 25/)).toHaveTextContent('near the limit');
    expect(screen.getByRole('button', { name: 'Let support read' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('What should they look at?'), 'October profit check');
    await userEvent.selectOptions(screen.getByLabelText('For'), '24');
    await userEvent.click(screen.getByRole('button', { name: 'Let support read' }));
    await waitFor(() => expect(calls('/support-grants', 'POST')).toHaveLength(1));
    expect(calls('/support-grants', 'POST')[0][1].body).toEqual({ hours: 24, reason: 'October profit check' });
    expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Export everything' }));
    expect(await screen.findByText('The export has started. We will email you when it is ready.')).toBeInTheDocument();
  });

  it('asks for deletion only after the name is typed, then shows the date', async () => {
    route();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage(<TenantOperations />);
    const button = await screen.findByRole('button', { name: 'Delete after 30 days' });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Type Lakshmi Farms to confirm'), 'lakshmi farms');
    await userEvent.click(button);
    await waitFor(() => expect(calls('/deletion', 'POST')).toHaveLength(1));
    expect(calls('/deletion', 'POST')[0][1].body).toEqual({ confirmName: 'lakshmi farms' });
    expect(await screen.findByRole('button', { name: 'Cancel the deletion' })).toBeInTheDocument();
    expect(reloadTenants).toHaveBeenCalled();
  });
});

describe('platform console (O6)', () => {
  it('suspends a farm business only with a reason shown to its members', async () => {
    const detail = { tenant: { id: 4, name: 'Selvam Agro', slug: 'selvam', active: true, planCode: 'FREE', memberCount: 3, farmCount: 1, modules: ['CROP'],
      lastActivity: null, createdAt: '2026-10-01T00:00:00Z', suspendedReason: null, deletionScheduledFor: null },
      owners: [{ userId: 2, email: 'selvam@example.com', displayName: 'Selvam' }], usage: [], history: [], supportGrants: [] };
    apiMock.mockImplementation((url: string, init?: { method?: string }) => {
      if (url.startsWith('/api/v1/platform/tenants?')) return Promise.resolve({ items: [detail.tenant], page: 0, size: 50, totalItems: 1, totalPages: 1 });
      if (url === '/api/v1/platform/tenants/4' && init?.method === 'PATCH') return Promise.resolve({});
      if (url === '/api/v1/platform/tenants/4') return Promise.resolve(detail);
      return Promise.resolve([]);
    });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage(<PlatformPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));
    expect(await screen.findByText('Owners: selvam@example.com')).toBeInTheDocument();
    const suspend = screen.getByRole('button', { name: 'Suspend' });
    expect(suspend).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason (shown to its members)'), 'Payment dispute');
    await userEvent.click(suspend);
    await waitFor(() => expect(calls('/api/v1/platform/tenants/4', 'PATCH')).toHaveLength(1));
    expect(calls('/api/v1/platform/tenants/4', 'PATCH')[0][1].body).toEqual({ active: false, reason: 'Payment dispute' });
  });
});
