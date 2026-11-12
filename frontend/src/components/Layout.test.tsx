import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import Layout from './Layout';

const state: { tenants: TenantSummary[]; platformAdmin: boolean } = { tenants: [], platformAdmin: false };
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 1, email: 'a@example.com', displayName: 'A', preferredLanguage: 'en', platformAdmin: state.platformAdmin },
    tenants: state.tenants, logout: vi.fn(), changeLanguage: vi.fn(),
  }),
}));

function renderAt(tenant: TenantSummary, platformAdmin = false) {
  state.tenants = [tenant];
  state.platformAdmin = platformAdmin;
  setLanguage('en');
  return render(
    <MemoryRouter initialEntries={[`/t/${tenant.id}/farms`]}>
      <Routes><Route path="/t/:tenantId/*" element={<Layout />} /></Routes>
    </MemoryRouter>,
  );
}

const base: TenantSummary = { id: 7, name: 'Green Valley', slug: 'gv', preferredLanguage: 'en', active: true, roles: [], permissions: [], enabledModules: ['CROP'], createdAt: '' };

describe('collapsing the menu', () => {
  afterEach(() => { localStorage.clear(); cleanup(); });

  it('collapses to icons, keeps every link reachable by name, and remembers the choice', async () => {
    const first = renderAt({ ...base, permissions: ['TENANT_READ'] });
    const collapse = await screen.findByRole('button', { name: 'Collapse the menu' });
    expect(collapse).toHaveAttribute('aria-expanded', 'true');
    expect(document.documentElement).not.toHaveClass('sf-menu-collapsed');

    await userEvent.click(collapse);
    expect(document.documentElement).toHaveClass('sf-menu-collapsed');
    expect(screen.getByRole('button', { name: 'Expand the menu' })).toHaveAttribute('aria-expanded', 'false');
    // Icon-only: the links keep their names, and a tooltip says the same
    const dashboard = screen.getByRole('link', { name: 'Dashboard' });
    expect(dashboard).toHaveAttribute('title', 'Dashboard');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();

    // Coming back, it stays collapsed until expanded again
    first.unmount();
    expect(document.documentElement).not.toHaveClass('sf-menu-collapsed');
    renderAt({ ...base, permissions: ['TENANT_READ'] });
    expect(document.documentElement).toHaveClass('sf-menu-collapsed');
    await userEvent.click(await screen.findByRole('button', { name: 'Expand the menu' }));
    expect(document.documentElement).not.toHaveClass('sf-menu-collapsed');
    expect(screen.getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('title');
  });
});

describe('layout navigation', () => {
  it('shows a worker only the sections they can use', () => {
    renderAt({ ...base, roles: ['WORKER'], permissions: ['TENANT_READ', 'FARM_READ', 'CROP_READ', 'CROP_ACTIVITY_WRITE'] });
    expect(screen.getByRole('link', { name: 'Farms & land' })).toHaveAttribute('href', '/t/7/farms');
    expect(screen.getByRole('link', { name: 'Crops' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Finance' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Members' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Platform admin' })).not.toBeInTheDocument();
  });

  it('shows platform administration only to platform administrators', () => {
    renderAt({ ...base, permissions: ['TENANT_READ'] }, true);
    expect(screen.getByRole('link', { name: 'Platform admin' })).toBeInTheDocument();
  });

  it('warns when the farm business is suspended', () => {
    renderAt({ ...base, active: false, permissions: ['TENANT_READ', 'FARM_READ'] });
    expect(screen.getByRole('alert')).toHaveTextContent('suspended');
    expect(screen.queryByRole('link', { name: 'Farms & land' })).not.toBeInTheDocument();
  });
});
