import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { LogOut, ShieldCheck, Sprout, UserRound } from 'lucide-react';
import { api } from '../api/client';
import type { Announcement } from '../api/types';
import { currentLanguage } from '../i18n';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { useCurrentTenant } from '../auth/useCurrentTenant';
import { visibleNav } from '../lib/nav';
import { LanguageToggle } from './common';

/** Announcements and maintenance notices from the platform, in the user's language (O8). */
export function AnnouncementBanner() {
  const [items, setItems] = useState<Announcement[]>([]);
  const lang = currentLanguage();
  useEffect(() => { api<Announcement[]>('/api/v1/announcements').then(setItems).catch(() => setItems([])); }, []);
  return <>{items.map(a => <div key={a.id} className={a.maintenance ? 'error-banner' : 'info-banner'} role="status">{lang === 'ta' ? a.messageTa : a.messageEn}</div>)}</>;
}

export default function Layout() {
  const { t } = useTranslation();
  const { user, tenants, logout } = useAuth();
  const tenant = useCurrentTenant();
  const navigate = useNavigate();
  const items = visibleNav(tenant);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><Sprout size={25} aria-hidden /></div>
          <div><strong>SmartFarm<span>360</span></strong><small>{t('common.tagline')}</small></div>
        </div>
        <label className="tenant-switcher">
          <span className="side-label">{t('nav.switchTenant')}</span>
          <select value={tenant?.id ?? ''} onChange={e => {
            if (e.target.value === 'new') navigate('/onboarding');
            else navigate(`/t/${e.target.value}/farms`);
          }}>
            {tenants.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}
            <option value="new">{t('nav.newTenant')}</option>
          </select>
        </label>
        <nav aria-label={t('nav.menu')}>
          {tenant && items.map(item => (
            <NavLink key={item.key} to={`/t/${tenant.id}/${item.path}`} className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              {t(`nav.${item.key}`)}
            </NavLink>
          ))}
          {user?.platformAdmin && (
            <NavLink to="/platform" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <ShieldCheck size={16} aria-hidden />{t('nav.platform')}
            </NavLink>
          )}
        </nav>
        <div className="side-bottom">
          <NavLink to="/account" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}><UserRound size={15} aria-hidden />{t('nav.account')}</NavLink>
          <small>{t('auth.signedInAs', { email: user?.email ?? '' })}</small>
          <button type="button" className="side-signout" onClick={() => void logout().then(() => navigate('/login'))}>
            <LogOut size={15} aria-hidden />{t('auth.signOut')}
          </button>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <div><span className="eyebrow">SMARTFARM360</span><h1>{tenant?.name ?? t('common.appName')}</h1></div>
          <LanguageToggle />
        </header>
        <AnnouncementBanner />
        {user && user.emailVerified === false && (
          <div className="info-banner" role="status">{t('account.verifyBanner')} <Link to="/account">{t('account.verifyBannerLink')}</Link></div>
        )}
        {tenant && !tenant.active && (
          <div className="error-banner" role="alert">{t('tenant.suspended')}{tenant.suspendedReason ? ` ${t('tenant.suspendedReason', { reason: tenant.suspendedReason })}` : ''}</div>
        )}
        <Outlet />
      </main>
    </div>
  );
}
