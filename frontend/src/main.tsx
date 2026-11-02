import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Outlet, Route, Routes, useParams } from 'react-router-dom';
import './i18n';
import './styles.css';
import './theme.css';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Layout from './components/Layout';
import { Loading } from './components/common';
import { visibleNav } from './lib/nav';
import { AcceptInvitationPage, ForgotPasswordPage, LoginPage, RegisterPage, ResetPasswordPage, ConsentPage, VerifyEmailPage } from './pages/AuthPages';
import AuditPage from './pages/AuditPage';
import { BillingReturnPage } from './pages/BillingPanel';
import ComparisonPage from './crop/ComparisonPage';
import CropLayout from './crop/CropLayout';
import CyclePage from './crop/CyclePage';
import CyclesPage from './crop/CyclesPage';
import SetupPage from './crop/SetupPage';
import WeatherPage from './crop/WeatherPage';
import AllocationPage from './finance/AllocationPage';
import FarmsPage from './pages/FarmsPage';
import FarmPage from './pages/FarmPage';
import AccountsPage from './finance/AccountsPage';
import DocumentPage from './finance/DocumentPage';
import FinanceHome from './finance/FinanceHome';
import FinanceLayout from './finance/FinanceLayout';
import FinanceSettingsPage from './finance/FinanceSettingsPage';
import NewRecordPage from './finance/NewRecordPage';
import PartiesPage from './finance/PartiesPage';
import RecordsPage from './finance/RecordsPage';
import ReportsPage from './finance/ReportsPage';
import ItemPage from './stock/ItemPage';
import ItemsPage from './stock/ItemsPage';
import LocationsPage from './stock/LocationsPage';
import StockHome from './stock/StockHome';
import StockLayout from './stock/StockLayout';
import StockRecordPage from './stock/StockRecordPage';
import StockReportsPage from './stock/StockReportsPage';
import AssetPage from './assets/AssetPage';
import AssetsLayout from './assets/AssetsLayout';
import AssetsPage from './assets/AssetsPage';
import DepreciationPage from './assets/DepreciationPage';
import MaintenancePage from './assets/MaintenancePage';
import AttendancePage from './workforce/AttendancePage';
import PayPage from './workforce/PayPage';
import WorkerPage from './workforce/WorkerPage';
import WorkersPage from './workforce/WorkersPage';
import WorkforceLayout from './workforce/WorkforceLayout';
import WorkforceReportsPage from './workforce/WorkforceReportsPage';
import DashboardPage from './reports/DashboardPage';
import { BalanceSheetPage, CategoriesPage, MonthlyPage, PartyStatementPage, TrialBalancePage } from './reports/MoneyReports';
import { RunningCostsPage, WagesReportPage } from './reports/OperationsReports';
import ReportsCatalogPage from './reports/ReportsCatalogPage';
import AnimalPage from './livestock/AnimalPage';
import CarePage from './livestock/CarePage';
import GroupsPage from './livestock/GroupsPage';
import FlockPage from './livestock/FlockPage';
import FlocksPage from './livestock/FlocksPage';
import LivestockHome from './livestock/LivestockHome';
import AquaLayout from './aquaculture/AquaLayout';
import AquaReportsPage from './aquaculture/AquaReportsPage';
import CyclePondPage from './aquaculture/CyclePage';
import PondPage from './aquaculture/PondPage';
import PondsPage from './aquaculture/PondsPage';
import WaterPage from './aquaculture/WaterPage';
import BatchPage from './organic/BatchPage';
import BatchesPage from './organic/BatchesPage';
import OrganicLayout from './organic/OrganicLayout';
import OrganicReportsPage from './organic/OrganicReportsPage';
import OwnProducePage from './stock/OwnProducePage';
import TradingPage from './stock/TradingPage';
import ProcessingBatchPage from './processing/BatchPage';
import ProcessingBatchesPage from './processing/BatchesPage';
import ProcessingLayout from './processing/ProcessingLayout';
import ProcessingReportsPage from './processing/ProcessingReportsPage';
import TracePage from './processing/TracePage';
import LivestockLayout from './livestock/LivestockLayout';
import LivestockReportsPage from './livestock/LivestockReportsPage';
import MilkPage from './livestock/MilkPage';
import MembersPage from './pages/MembersPage';
import OnboardingPage from './pages/OnboardingPage';
import PlatformPage from './pages/PlatformPage';
import AccountPage from './pages/AccountPage';
import SettingsPage from './pages/SettingsPage';

/** Signed-in users first accept new terms or a new privacy notice, if any were published (O12). */
function RequireAuth() {
  const { status, user } = useAuth();
  if (status === 'loading') return <Loading />;
  if (status !== 'signedIn') return <Navigate to="/login" replace />;
  return user?.pendingConsents?.length ? <ConsentPage /> : <Outlet />;
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
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route element={<RequireAuth />}>
        <Route path="/" element={<Home />} />
        <Route path="/onboarding" element={<OnboardingPage />} />
        <Route path="/billing/return" element={<BillingReturnPage />} />
        <Route element={<Layout />}>
          <Route path="/t/:tenantId" element={<TenantRoute />}>
            <Route index element={<Navigate to="dashboard" replace />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="reports" element={<ReportsCatalogPage />} />
            <Route path="reports/monthly" element={<MonthlyPage />} />
            <Route path="reports/categories" element={<CategoriesPage />} />
            <Route path="reports/balance-sheet" element={<BalanceSheetPage />} />
            <Route path="reports/trial-balance" element={<TrialBalancePage />} />
            <Route path="reports/wages" element={<WagesReportPage />} />
            <Route path="reports/running-costs" element={<RunningCostsPage />} />
            <Route path="farms" element={<FarmsPage />} />
            <Route path="farms/:farmId" element={<FarmPage />} />
            <Route path="crops" element={<CropLayout />}>
              <Route index element={<CyclesPage />} />
              <Route path="cycles/:cycleId" element={<CyclePage />} />
              <Route path="setup" element={<SetupPage />} />
              <Route path="compare/:cropId" element={<ComparisonPage />} />
              <Route path="weather" element={<WeatherPage />} />
            </Route>
            <Route path="livestock" element={<LivestockLayout />}>
              <Route index element={<LivestockHome />} />
              <Route path="animals/:animalId" element={<AnimalPage />} />
              <Route path="milk" element={<MilkPage />} />
              <Route path="poultry" element={<FlocksPage kind="poultry" />} />
              <Route path="small-stock" element={<FlocksPage kind="smallStock" />} />
              <Route path="flocks/:groupId" element={<FlockPage />} />
              <Route path="care" element={<CarePage />} />
              <Route path="groups" element={<GroupsPage />} />
              <Route path="reports" element={<LivestockReportsPage />} />
            </Route>
            <Route path="aquaculture" element={<AquaLayout />}>
              <Route index element={<PondsPage />} />
              <Route path="ponds/:pondId" element={<PondPage />} />
              <Route path="cycles/:cycleId" element={<CyclePondPage />} />
              <Route path="water" element={<WaterPage />} />
              <Route path="reports" element={<AquaReportsPage />} />
            </Route>
            <Route path="processing" element={<ProcessingLayout />}>
              <Route index element={<ProcessingBatchesPage />} />
              <Route path="batches/:batchId" element={<ProcessingBatchPage />} />
              <Route path="trace" element={<TracePage />} />
              <Route path="reports" element={<ProcessingReportsPage />} />
            </Route>
            <Route path="organic" element={<OrganicLayout />}>
              <Route index element={<BatchesPage />} />
              <Route path="batches/:batchId" element={<BatchPage />} />
              <Route path="reports" element={<OrganicReportsPage />} />
            </Route>
            <Route path="finance" element={<FinanceLayout />}>
              <Route index element={<FinanceHome />} />
              <Route path="records" element={<RecordsPage />} />
              <Route path="records/:documentId" element={<DocumentPage />} />
              <Route path="new/:kind" element={<NewRecordPage />} />
              <Route path="parties" element={<PartiesPage />} />
              <Route path="parties/:partyId/statement" element={<PartyStatementPage />} />
              <Route path="accounts" element={<AccountsPage />} />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="settings" element={<FinanceSettingsPage />} />
              <Route path="allocation" element={<AllocationPage />} />
            </Route>
            <Route path="stock" element={<StockLayout />}>
              <Route index element={<StockHome />} />
              <Route path="items" element={<ItemsPage />} />
              <Route path="items/:itemId" element={<ItemPage />} />
              <Route path="locations" element={<LocationsPage />} />
              <Route path="reports" element={<StockReportsPage />} />
              <Route path="new/:kind" element={<StockRecordPage />} />
              <Route path="own-produce" element={<OwnProducePage />} />
              <Route path="trading" element={<TradingPage />} />
            </Route>
            <Route path="workforce" element={<WorkforceLayout />}>
              <Route index element={<AttendancePage />} />
              <Route path="workers" element={<WorkersPage />} />
              <Route path="workers/:workerId" element={<WorkerPage />} />
              <Route path="pay" element={<PayPage />} />
              <Route path="reports" element={<WorkforceReportsPage />} />
            </Route>
            <Route path="assets" element={<AssetsLayout />}>
              <Route index element={<AssetsPage />} />
              <Route path="depreciation" element={<DepreciationPage />} />
              <Route path="maintenance" element={<MaintenancePage />} />
              <Route path=":assetId" element={<AssetPage />} />
            </Route>
            <Route path="members" element={<MembersPage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="audit" element={<AuditPage />} />
          </Route>
          <Route path="/account" element={<AccountPage />} />
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
