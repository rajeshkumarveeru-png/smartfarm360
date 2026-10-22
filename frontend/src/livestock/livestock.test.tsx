import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { Permission, TenantSummary } from '../api/types';
import { setLanguage } from '../i18n';
import AnimalPage from './AnimalPage';
import HerdPage from './HerdPage';
import LivestockReportsPage from './LivestockReportsPage';
import MilkPage from './MilkPage';
import type { Animal, Group, MilkDay } from './types';

const apiMock = vi.fn();
vi.mock('../api/client', async importOriginal => ({ ...(await importOriginal<object>()), api: (...args: unknown[]) => apiMock(...args) }));

const all: Permission[] = ['TENANT_READ', 'LIVESTOCK_READ', 'LIVESTOCK_WRITE', 'FINANCE_READ', 'FINANCE_WRITE'];
const tenant: TenantSummary = { id: 9, name: 'Test farm', slug: 't', preferredLanguage: 'en', active: true, roles: ['TENANT_OWNER'],
  permissions: all, enabledModules: ['DAIRY'], createdAt: '' };
vi.mock('../auth/useCurrentTenant', () => ({ useTenant: () => tenant, useCurrentTenant: () => tenant }));

const groups: Group[] = [
  { id: 1, costObjectId: 11, name: 'Milking herd', purpose: 'MILKING', farmId: null, notes: null, active: true, animals: 2 },
  { id: 2, costObjectId: 12, name: 'Calves', purpose: 'CALF', farmId: null, notes: null, active: true, animals: 1 }];
const breeding = { repro: 'OPEN' as const, lastInsemination: null, checkDueFrom: null, expectedCalving: null, dryOffDue: null, lactation: 2,
  lastCalving: '2026-05-01', daysInMilk: 81, inMilk: true, withheldUntil: null };
const lakshmi: Animal = { id: 5, tag: 'L-01', name: 'Lakshmi', species: 'COW', breed: 'Jersey', sex: 'FEMALE', birthDate: '2021-01-01', birthDateEstimated: false,
  origin: 'BOUGHT', arrivedOn: '2026-04-01', damId: null, damTag: null, sire: null, farmId: null, groupId: 1, groupName: 'Milking herd', assetId: 7,
  bookValue: 58500, status: 'ACTIVE', exitDate: null, exitCause: null, exitDocumentId: null, notes: null, breeding: { ...breeding, withheldUntil: '2026-07-23' } };
const ganga: Animal = { ...lakshmi, id: 6, tag: 'G-01', name: 'Ganga', origin: 'BORN', assetId: null, bookValue: null,
  breeding: { ...breeding, repro: 'INSEMINATED', checkDueFrom: '2026-09-08', expectedCalving: '2027-04-19', dryOffDue: '2027-02-18' } };
const day: MilkDay = { date: '2026-07-22', sessions: [], total: 0, withheld: [{ animalId: 5, tag: 'L-01', name: 'Lakshmi', until: '2026-07-23' }], inStock: 12 };

function routeApi(url: string, init?: { method?: string; body?: unknown }) {
  if (url.endsWith('/livestock/groups')) return Promise.resolve(groups);
  if (url.includes('/livestock/animals?') || url.endsWith('/livestock/animals')) return Promise.resolve([lakshmi, ganga]);
  if (url.includes('/livestock/milk/') && init?.method === 'PUT') return Promise.resolve({ ...day, total: 9 });
  if (url.includes('/livestock/milk/')) return Promise.resolve(day);
  if (url.endsWith('/livestock/animals/6')) return Promise.resolve({ animal: ganga, moves: [{ groupId: 1, groupName: 'Milking herd', from: '2022-03-01' }],
    events: [], lactations: [{ number: 1, calvingDate: '2026-05-01', dryOffDate: null, recordedLitres: 248 }], calves: [], recentYields: [] });
  if (url.includes('/breeding') && init?.method === 'POST') return Promise.resolve({ id: 1 });
  if (url.includes('/reports/herd')) return Promise.resolve({ groups: [{ groupId: 1, groupName: 'Milking herd', purpose: 'MILKING', revenue: 16150,
    costs: [{ accountCode: '5800', categoryId: null, categoryCode: null, categoryName: null, amount: 500 }], costTotal: 16050, profit: 100,
    litresProduced: 558, litresSold: 425, costPerLitre: 28.76, pricePerLitre: 38 }] });
  if (url.includes('/reports/milk')) return Promise.resolve({ from: '2026-07-01', to: '2026-07-31', days: [], produced: 558, sold: 425, homeUse: 31,
    fedToAnimals: 62, discarded: 40, otherOut: 0, fat: 4.2, snf: 8.5, animals: [] });
  if (url.endsWith('/finance/parties')) return Promise.resolve([{ id: 3, name: 'Village dairy society', customer: true, supplier: false, lender: false, active: true, receivable: 0, payable: 0 }]);
  if (url.endsWith('/finance/accounts')) return Promise.resolve([{ id: 4, name: 'Cash in hand', kind: 'CASH', active: true, balance: 0 }]);
  if (url.endsWith('/finance/settings')) return Promise.resolve({ lockDate: null, fiscalYearStartMonth: 4 });
  return Promise.resolve([]);
}

function renderAt(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter>);
}

describe('livestock web', () => {
  beforeEach(() => {
    apiMock.mockReset();
    apiMock.mockImplementation(routeApi);
    tenant.permissions = all;
    setLanguage('en');
  });

  it('lists the herd with breeding state, withheld milk and book values', async () => {
    renderAt('/t/9/livestock', <HerdPage />, '/t/:tenantId/livestock');
    expect(await screen.findByRole('link', { name: 'L-01 Lakshmi' })).toHaveAttribute('href', '/t/9/livestock/animals/5');
    expect(screen.getByText(/milk withheld until 23 Jul 2026/)).toBeInTheDocument();
    expect(screen.getByText(/Inseminated · calving 19 Apr 2027/)).toBeInTheDocument();
    expect(screen.getByText('₹58,500.00')).toBeInTheDocument();
  });

  it('records milk per animal, warns about withheld milk, and offers a society sale', async () => {
    renderAt('/t/9/livestock/milk', <MilkPage />, '/t/:tenantId/livestock/milk');
    expect(await screen.findByRole('alert')).toHaveTextContent('L-01 (23 Jul 2026)');
    const perAnimal = screen.getAllByLabelText('Per animal');
    await userEvent.click(perAnimal[0]);
    await userEvent.type(screen.getByLabelText('Litres, G-01, Morning'), '4.5');
    await userEvent.type(screen.getByLabelText('Litres, Milking herd, Evening'), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Save milk' }));
    await waitFor(() => expect(apiMock.mock.calls.some(([, i]) => i?.method === 'PUT')).toBe(true));
    const body = apiMock.mock.calls.find(([, i]) => i?.method === 'PUT')![1].body as { sessions: unknown[] };
    expect(body.sessions).toEqual([
      { session: 'MORNING', groupId: 1, litres: null, fat: null, snf: null, yields: [{ animalId: 6, litres: '4.5' }], baseVersion: '0' },
      { session: 'EVENING', groupId: 1, litres: '9', fat: null, snf: null, yields: [], baseVersion: '0' }]);
    expect(screen.getByRole('button', { name: 'Record sale' })).toBeInTheDocument();
  });

  it('hides the sale form without permission to record money', async () => {
    tenant.permissions = ['TENANT_READ', 'LIVESTOCK_READ', 'LIVESTOCK_WRITE'];
    renderAt('/t/9/livestock/milk', <MilkPage />, '/t/:tenantId/livestock/milk');
    expect(await screen.findByRole('button', { name: 'Save milk' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record sale' })).not.toBeInTheDocument();
  });

  it('records an insemination for an animal and shows its worked-out dates', async () => {
    renderAt('/t/9/livestock/animals/6', <AnimalPage />, '/t/:tenantId/livestock/animals/:animalId');
    expect(await screen.findByText('8 Sept 2026')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Breeding event' }));
    await userEvent.type(screen.getByLabelText(/Bull or semen/), 'HF semen');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(apiMock.mock.calls.some(([u, i]) => String(u).endsWith('/animals/6/breeding') && i?.method === 'POST')).toBe(true));
    const call = apiMock.mock.calls.find(([u, i]) => String(u).endsWith('/animals/6/breeding') && i?.method === 'POST')!;
    expect(call[1].body).toMatchObject({ kind: 'INSEMINATION', service: 'AI', bull: 'HF semen' });
    expect(call[1].headers['Idempotency-Key']).toBeTruthy();
  });

  it('shows herd profitability with cost per litre, and only the milk report without finance access', async () => {
    renderAt('/t/9/livestock/reports', <LivestockReportsPage />, '/t/:tenantId/livestock/reports');
    expect(await screen.findByText('₹28.76')).toBeInTheDocument();
    expect(screen.getByText('₹38.00')).toBeInTheDocument();
    tenant.permissions = ['TENANT_READ', 'LIVESTOCK_READ'];
    apiMock.mockClear();
    renderAt('/t/9/livestock/reports', <LivestockReportsPage />, '/t/:tenantId/livestock/reports');
    await waitFor(() => expect(apiMock.mock.calls.some(([u]) => String(u).includes('/reports/milk'))).toBe(true));
    expect(apiMock.mock.calls.some(([u]) => String(u).includes('/reports/herd'))).toBe(false);
  });
});
