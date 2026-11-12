import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/client';
import { setLanguage } from '../i18n';
import { ErrorBanner } from './common';
import { SavedToast, Toast } from './Toast';

const ok = (body: unknown = {}) => new Response(JSON.stringify(body), { status: 200 });

describe('save confirmations', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers();
    setLanguage('en');
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  const write = async (path: string, method: string) => {
    await act(async () => { await api(path, { method }); });
    await act(async () => { await vi.advanceTimersByTimeAsync(800); });
  };

  it('says Saved after a write to a farm business, and Deleted after a delete, then goes away', async () => {
    render(<SavedToast />);
    fetchMock.mockImplementation(async () => ok());
    await write('/api/v1/tenants/1/farms', 'POST');
    expect(screen.getByRole('status')).toHaveTextContent('Saved.');
    await act(async () => { await vi.advanceTimersByTimeAsync(4100); });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    await write('/api/v1/tenants/1/farms/4', 'DELETE');
    expect(screen.getByRole('status')).toHaveTextContent('Deleted.');
  });

  it('says nothing for reading, previews, exports, billing, or a failed save', async () => {
    render(<SavedToast />);
    fetchMock.mockImplementation(async () => ok());
    await write('/api/v1/tenants/1/farms', 'GET');
    await write('/api/v1/tenants/1/finance/allocation/preview', 'POST');
    await write('/api/v1/tenants/1/pay-runs/preview', 'POST');
    await write('/api/v1/tenants/1/exports', 'POST');
    await write('/api/v1/tenants/1/billing/checkout', 'POST');
    await write('/api/v1/auth/login', 'POST');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    fetchMock.mockImplementation(async () => new Response(JSON.stringify({ code: 'NOPE', message: 'No' }), { status: 400 }));
    await act(async () => { await api('/api/v1/tenants/1/farms', { method: 'POST' }).catch(() => undefined); });
    await act(async () => { await vi.advanceTimersByTimeAsync(800); });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('gives one confirmation for several writes together', async () => {
    render(<SavedToast />);
    fetchMock.mockImplementation(async () => ok());
    await act(async () => { await Promise.all([api('/api/v1/tenants/1/a', { method: 'POST' }), api('/api/v1/tenants/1/b', { method: 'POST' })]); });
    await act(async () => { await vi.advanceTimersByTimeAsync(800); });
    expect(screen.getAllByRole('status')).toHaveLength(1);
  });

  it('lets a page’s own message take the place of Saved, before or after it shows', async () => {
    fetchMock.mockImplementation(async () => ok());
    const onClose = vi.fn();
    const { rerender } = render(<><SavedToast /><Toast message="" onClose={onClose} /></>);
    // The page's message arrives while the general one is waiting
    await act(async () => { await api('/api/v1/tenants/1/farms', { method: 'POST' }); });
    rerender(<><SavedToast /><Toast message="Record for 5 Nov saved." onClose={onClose} /></>);
    await act(async () => { await vi.advanceTimersByTimeAsync(800); });
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('Record for 5 Nov saved.');

    // The page's message arrives after the general one has shown
    rerender(<><SavedToast /><Toast message="" onClose={onClose} /></>);
    await write('/api/v1/tenants/1/farms', 'POST');
    expect(screen.getByRole('status')).toHaveTextContent('Saved.');
    rerender(<><SavedToast /><Toast message="Pond saved." onClose={onClose} /></>);
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('Pond saved.');
  });

  it('closes a page’s message by itself, later for a longer one', async () => {
    const onClose = vi.fn();
    render(<Toast message={'x'.repeat(200)} onClose={onClose} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(onClose).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(9000); });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('brings an error that is out of view into view', () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    const { rerender } = render(<ErrorBanner message="" />);
    expect(scroll).not.toHaveBeenCalled();
    rerender(<ErrorBanner message="Could not save." />);
    expect(scroll).toHaveBeenCalledTimes(1);
    // @ts-expect-error jsdom has no scrollIntoView of its own
    delete Element.prototype.scrollIntoView;
  });
});
