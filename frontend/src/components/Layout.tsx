import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Bird, ChartColumn, Factory, PawPrint, Fish, HardHat, LayoutDashboard, Leaf, LogOut, Map as MapIcon, PanelLeftClose, PanelLeftOpen, Milk, Package, ScrollText, Settings, ShieldCheck, Sprout,
  Tractor, UserRound, UsersRound, Wallet, type LucideIcon } from 'lucide-react';
import { api } from '../api/client';
import type { Announcement } from '../api/types';
import { currentLanguage } from '../i18n';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { useCurrentTenant } from '../auth/useCurrentTenant';
import { can, visibleNav } from '../lib/nav';
import { LanguageToggle } from './common';
import { livestockSection } from '../livestock/LivestockLayout';
import { BillingBanner } from '../pages/BillingPanel';
import { ErrorBoundary } from './ErrorBoundary';

/** Announcements and maintenance notices from the platform, in the user's language (O8). */
export function AnnouncementBanner() {
  const [items, setItems] = useState<Announcement[]>([]);
  const lang = currentLanguage();
  useEffect(() => { api<Announcement[]>('/api/v1/announcements').then(setItems).catch(() => setItems([])); }, []);
  return <>{items.map(a => <div key={a.id} className={a.maintenance ? 'error-banner' : 'info-banner'} role="status">{lang === 'ta' ? a.messageTa : a.messageEn}</div>)}</>;
}

/** An icon for each section of the menu (presentation only; the sections and their permissions come from NAV_ITEMS). */
const NAV_ICONS: Record<string, LucideIcon> = {
  dashboard: LayoutDashboard, farms: MapIcon, crops: Sprout, livestock: Milk, poultry: Bird, smallStock: PawPrint, aquaculture: Fish, organic: Leaf, processing: Factory, finance: Wallet,
  stock: Package, workforce: HardHat, assets: Tractor, reports: ChartColumn, members: UsersRound, audit: ScrollText, settings: Settings,
};

const COLLAPSED_KEY = 'sf_menu_collapsed';

export default function Layout() {
  const { t } = useTranslation();
  const { user, tenants, logout } = useAuth();
  const tenant = useCurrentTenant();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(() => { try { return localStorage.getItem(COLLAPSED_KEY) === '1'; } catch { return false; } });
  // On the document, as the menu and the page both change size. Remembered per browser
  useEffect(() => {
    document.documentElement.classList.toggle('sf-menu-collapsed', collapsed);
    try { localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0'); } catch { /* storage unavailable */ }
    return () => document.documentElement.classList.remove('sf-menu-collapsed');
  }, [collapsed]);
  const pageRef = useRef<HTMLDivElement>(null);
  // Each page fades in. Animating the element keeps the page mounted, so this reloads nothing
  useEffect(() => {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    pageRef.current?.animate?.([{ opacity: 0 }, { opacity: 1 }], { duration: 240, easing: 'ease-out' });
  }, [pathname]);
  // Poultry and goat pages live under /livestock: there their own entry is the active one, not cows and dairy
  const inOtherAnimals = pathname.includes('/livestock') && livestockSection(pathname) !== 'cattle';
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
          {tenant && items.map(item => {
            const Icon = NAV_ICONS[item.key] ?? LayoutDashboard;
            return (
              <NavLink key={item.key} to={`/t/${tenant.id}/${item.path}`}
                title={collapsed ? t(`nav.${item.key}`) : undefined}
                className={({ isActive }) => `side-link${(isActive || (item.key === 'farms' && (pathname.includes('/farms') || pathname.includes('/crops')))) && !(item.key === 'livestock' && inOtherAnimals) ? ' active' : ''}`}>
                <Icon size={17} aria-hidden /><span className="side-text">{t(`nav.${item.key}`)}</span>
              </NavLink>
            );
          })}
          {user?.platformAdmin && (
            <NavLink to="/platform" title={collapsed ? t('nav.platform') : undefined} className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <ShieldCheck size={16} aria-hidden /><span className="side-text">{t('nav.platform')}</span>
            </NavLink>
          )}
        </nav>
        <div className="side-bottom">
          <NavLink to="/account" title={collapsed ? t('nav.account') : undefined} className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}><UserRound size={15} aria-hidden /><span className="side-text">{t('nav.account')}</span></NavLink>
          <small>{t('auth.signedInAs', { email: user?.email ?? '' })}</small>
          <button type="button" className="side-signout" title={collapsed ? t('auth.signOut') : undefined} onClick={() => void logout().then(() => navigate('/login'))}>
            <LogOut size={15} aria-hidden /><span className="side-text">{t('auth.signOut')}</span>
          </button>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <div>
            <button type="button" className="menu-toggle" aria-expanded={!collapsed} aria-label={t(collapsed ? 'nav.expandMenu' : 'nav.collapseMenu')} title={t(collapsed ? 'nav.expandMenu' : 'nav.collapseMenu')}
                    onClick={() => setCollapsed(c => !c)}>
              {collapsed ? <PanelLeftOpen size={18} aria-hidden /> : <PanelLeftClose size={18} aria-hidden />}
            </button>
            <span className="eyebrow">SMARTFARM360</span><h1>{tenant?.name ?? t('common.appName')}</h1></div>
          <LanguageToggle />
        </header>
        <AnnouncementBanner />
        {user && user.emailVerified === false && (
          <div className="info-banner" role="status">{t('account.verifyBanner')} <Link to="/account">{t('account.verifyBannerLink')}</Link></div>
        )}
        {tenant && tenant.active && <BillingBanner tenantId={tenant.id} canManage={can(tenant, 'BILLING_MANAGE')} />}
        {tenant && !tenant.active && (
          <div className="error-banner" role="alert">{t('tenant.suspended')}{tenant.suspendedReason ? ` ${t('tenant.suspendedReason', { reason: tenant.suspendedReason })}` : ''}</div>
        )}
        <div ref={pageRef}><ErrorBoundary resetKey={pathname}><Outlet /></ErrorBoundary></div>
      </main>
    </div>
  );
}
