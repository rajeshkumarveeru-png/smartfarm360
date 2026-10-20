import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useParams } from 'react-router-dom';
import './i18n';
import './styles.css';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Layout from './components/Layout';
import { Loading } from './components/common';
import { visibleNav } from './lib/nav';
import { AcceptInvitationPage, ForgotPasswordPage, LoginPage, RegisterPage, ResetPasswordPage } from './pages/AuthPages';
import AuditPage from './pages/AuditPage';
import CropPage from './pages/CropPage';
import FarmsPage from './pages/FarmsPage';
import FinancePage from './pages/FinancePage';
import MembersPage from './pages/MembersPage';
import OnboardingPage from './pages/OnboardingPage';
import PlatformPage from './pages/PlatformPage';
import SettingsPage from './pages/SettingsPage';

function RequireAuth() {
  const { status } = useAuth();
  if (status === 'loading') return <Loading />;
  return status === 'signedIn' ? <Outlet /> : <Navigate to="/login" replace />;
}

function PublicOnly({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === 'loading') return <Loading />;
  return status === 'signedIn' ? <Navigate to="/" replace /> : <>{children}</>;
}

/** Sends a signed-in user to their first farm business, or to onboarding when they have none. */
function Home() {
  const { tenants } = useAuth();
  if (!tenants.length) return <Navigate to="/onboarding" replace />;
  const first = tenants[0];
  const section = visibleNav(first)[0]?.path ?? 'settings';
  return <Navigate to={`/t/${first.id}/${section}`} replace />;
}

/** Only renders tenant pages for tenants the user belongs to. */
function TenantRoute() {
  const { tenantId } = useParams();
  const { tenants } = useAuth();
  return tenants.some(t => String(t.id) === tenantId) ? <Outlet /> : <Navigate to="/" replace />;
}

function RequirePlatformAdmin() {
  const { user } = useAuth();
  return user?.platformAdmin ? <Outlet /> : <Navigate to="/" replace />;
}

function App() {
  return (
    <Routes>
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route path="/register" element={<PublicOnly><RegisterPage /></PublicOnly>} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
      <Route element={<RequireAuth />}>
        <Route path="/" element={<Home />} />
        <Route path="/onboarding" element={<OnboardingPage />} />
        <Route element={<Layout />}>
          <Route path="/t/:tenantId" element={<TenantRoute />}>
            <Route index element={<Navigate to="farms" replace />} />
            <Route path="farms" element={<FarmsPage />} />
            <Route path="crops" element={<CropPage />} />
            <Route path="finance" element={<FinancePage />} />
            <Route path="members" element={<MembersPage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="audit" element={<AuditPage />} />
          </Route>
          <Route path="/platform" element={<RequirePlatformAdmin />}>
            <Route index element={<PlatformPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider><App /></AuthProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
