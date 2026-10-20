import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { LogOut, ShieldCheck, Sprout } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { useCurrentTenant } from '../auth/useCurrentTenant';
import { visibleNav } from '../lib/nav';
import { LanguageToggle } from './common';

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
        {tenant && !tenant.active && <div className="error-banner" role="alert">{t('tenant.suspended')}</div>}
        <Outlet />
      </main>
    </div>
  );
}
