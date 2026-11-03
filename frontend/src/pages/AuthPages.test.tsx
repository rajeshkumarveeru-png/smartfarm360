import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ApiError } from '../api/client';
import { setLanguage } from '../i18n';
import { LoginPage, RegisterPage } from './AuthPages';

const login = vi.fn();
const register = vi.fn();
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ login, register, sessionExpired: false, changeLanguage: async (lang: 'en' | 'ta') => setLanguage(lang) }),
}));

function renderPage(node: React.ReactNode) {
  return render(<MemoryRouter>{node}</MemoryRouter>);
}

describe('sign-in page', () => {
  beforeEach(() => {
    login.mockReset();
    register.mockReset();
    setLanguage('en');
  });

  it('switches between English and Tamil at runtime', async () => {
    renderPage(<LoginPage />);
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Language' }));
    expect(await screen.findByRole('button', { name: 'உள்நுழை' })).toBeInTheDocument();
    expect(screen.getByLabelText('மின்னஞ்சல்')).toBeInTheDocument();
  });

  it('asks for missing fields without calling the API', async () => {
    renderPage(<LoginPage />);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(screen.getByRole('alert')).toHaveTextContent('This field is required.');
    expect(login).not.toHaveBeenCalled();
  });

  it('shows the translated message for an API error code', async () => {
    login.mockRejectedValueOnce(new ApiError(401, 'INVALID_CREDENTIALS', 'server text'));
    renderPage(<LoginPage />);
    await userEvent.type(screen.getByLabelText('Email'), 'farmer@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect');
  });
});

describe('registration page', () => {
  it('rejects short passwords before calling the API', async () => {
    setLanguage('en');
    renderPage(<RegisterPage />);
    await userEvent.type(screen.getByLabelText('Your name'), 'Murugan');
    await userEvent.type(screen.getByLabelText('Email'), 'murugan@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'short');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(screen.getByRole('alert')).toHaveTextContent('at least 10 characters');
    expect(register).not.toHaveBeenCalled();
  });
});
