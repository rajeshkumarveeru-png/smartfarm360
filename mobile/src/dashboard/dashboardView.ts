import AsyncStorage from '@react-native-async-storage/async-storage';
import { apiRequest } from '../services/api';
import { tr, type Language } from '../i18n/translations';

/**
 * The farm business dashboard on the phone (dashboard and reports design, H13). The server leaves out what the role may
 * not see; the last copy is kept per user and tenant so the Home tab still shows it offline, with the time it was fetched.
 */
export type DashboardMoney = { cashTotal: number; receivableTotal: number; overdueReceivables: number; payableTotal: number;
  monthRevenue: number; monthCosts: number; yearNetProfit: number };
export type Attention = { code: string; count: number; amount: number | null; detail: string | null };
export type Dashboard = { asOf: string; money: DashboardMoney | null; attention: Attention[] };
export type CachedDashboard = { dashboard: Dashboard; fetchedAt: string; offline: boolean };

const cacheKey = (scope: string) => `${scope}/dashboard`;

/** Fetches the dashboard and keeps a copy; offline (or on any failure) returns the kept copy marked offline, or null. */
export async function loadDashboard(tenantId: number, scope: string, asOf: string, now: () => Date = () => new Date()): Promise<CachedDashboard | null> {
  try {
    const dashboard = await apiRequest<Dashboard>(`/api/v1/tenants/${tenantId}/dashboard?asOf=${asOf}`);
    const kept: CachedDashboard = { dashboard: { asOf: dashboard.asOf, money: dashboard.money, attention: dashboard.attention }, fetchedAt: now().toISOString(), offline: false };
    await AsyncStorage.setItem(cacheKey(scope), JSON.stringify(kept));
    return kept;
  } catch {
    const raw = await AsyncStorage.getItem(cacheKey(scope));
    if (!raw) return null;
    try { return { ...(JSON.parse(raw) as CachedDashboard), offline: true }; } catch { return null; }
  }
}

const money = (n: number | null) => (n == null ? '' : `₹${Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`);

/** The "needs attention" line, from the same catalog text as the web dashboard. */
export function attentionText(language: Language, a: Attention): string {
  return tr(language, `dashboard.attention.${a.code}`, { count: String(a.count), amount: money(a.amount), detail: a.detail ?? '' });
}

/** "as of 21 Oct, 09:30" for a kept copy. */
export function fetchedLabel(language: Language, fetchedAt: string): string {
  const d = new Date(fetchedAt);
  const time = d.toLocaleString(language === 'ta' ? 'ta-IN' : 'en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  return tr(language, 'mobile.dashboardAsOf', { time });
}
