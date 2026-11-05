import { useEffect, useRef } from 'react';
import React from 'react';
import { CircleAlert, Languages, RefreshCw, Sprout } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { currentLanguage } from '../i18n';

export function ErrorBanner({ message, onRetry, retryLabel }: { message: string; onRetry?: () => void; retryLabel?: string }) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  // A form can be far below the page's banner: bring a problem into view
  useEffect(() => {
    const box = message ? ref.current?.getBoundingClientRect() : null;
    if (box && (box.top < 80 || box.bottom > window.innerHeight)) ref.current?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
  }, [message]);
  if (!message) return null;
  return (
    <div ref={ref} className="error-banner" role="alert">
      <CircleAlert size={18} aria-hidden />
      <span>{message}</span>
      {onRetry && <button type="button" onClick={onRetry}>{retryLabel ?? t('common.retry')}</button>}
    </div>
  );
}

export function Loading() {
  const { t } = useTranslation();
  return <div className="crop-loading" role="status"><RefreshCw size={16} aria-hidden />{t('common.loading')}</div>;
}

export function LanguageToggle() {
  const { t } = useTranslation();
  const { changeLanguage } = useAuth();
  const next = currentLanguage() === 'en' ? 'ta' : 'en';
  return (
    <button type="button" className="language" onClick={() => void changeLanguage(next)} aria-label={t('common.language')}>
      <Languages size={17} aria-hidden />{next === 'ta' ? t('common.tamil') : t('common.english')}
    </button>
  );
}

/** Centered card used by the sign-in, registration, password and invitation pages. */
export function AuthCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div className="auth-shell">
      <main className="auth-card">
        <div className="auth-top"><div className="brand-mark"><Sprout size={25} aria-hidden /></div><LanguageToggle /></div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
        {children}
      </main>
    </div>
  );
}

export function EmptyState({ children }: { children: React.ReactNode }) {
  return <div className="empty-state"><p>{children}</p></div>;
}
