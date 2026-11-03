import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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
