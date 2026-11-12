import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { setLanguage } from '../i18n';
import { ErrorBoundary } from './ErrorBoundary';

let broken = true;
function Page() {
  if (broken) throw new Error('bad data');
  return <p>The page</p>;
}

describe('a fault in one page', () => {
  it('shows a message instead of a blank app, and recovers on retry or on moving to another page', async () => {
    setLanguage('en');
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    broken = true;
    const { rerender } = render(<ErrorBoundary resetKey="/a"><Page /></ErrorBoundary>);
    expect(screen.getByRole('alert')).toHaveTextContent('This page could not be shown');

    broken = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(screen.getByText('The page')).toBeInTheDocument();

    broken = true;
    rerender(<ErrorBoundary resetKey="/b"><Page /></ErrorBoundary>);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    broken = false;
    rerender(<ErrorBoundary resetKey="/c"><Page /></ErrorBoundary>);
    expect(screen.getByText('The page')).toBeInTheDocument();
    quiet.mockRestore();
  });
});
