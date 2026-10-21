import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { TenantSummary } from '../auth/session';
import { tr, type Language } from '../i18n/translations';
import { attentionText, fetchedLabel, loadDashboard, type CachedDashboard } from './dashboardView';

const money = (n: number) => `₹${Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
const today = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };

/**
 * Home tab dashboard (H13): what is owed and owing, and what needs attention, as the role may see them. Offline, the last
 * copy with its time. Entries waiting on this phone are counted separately as "not yet sent".
 */
export default function DashboardPanel({ tenant, language, scope, pending, refreshKey }: {
  tenant: TenantSummary; language: Language; scope: string; pending: number; refreshKey: number;
}) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const [data, setData] = useState<CachedDashboard | null>(null);
  useEffect(() => { loadDashboard(tenant.id, scope, today()).then(setData).catch(() => setData(null)); }, [tenant.id, scope, refreshKey]);

  const m = data?.dashboard.money;
  return (
    <View>
      {pending > 0 && <View style={s.pending}><Text style={s.pendingText}>⏳ {t('mobile.notYetSent', { count: String(pending) })}</Text></View>}
      {data?.offline && <Text style={s.offline}>{fetchedLabel(language, data.fetchedAt)}</Text>}
      {m && (
        <View style={s.row}>
          <View style={s.tile}><Text style={s.label}>{t('fin.receivable')}</Text><Text style={s.value}>{money(m.receivableTotal)}</Text>
            {m.overdueReceivables > 0 && <Text style={s.small}>{t('fin.overdue', { amount: money(m.overdueReceivables) })}</Text>}</View>
          <View style={s.tile}><Text style={s.label}>{t('fin.payable')}</Text><Text style={s.value}>{money(m.payableTotal)}</Text></View>
        </View>
      )}
      {data && data.dashboard.attention.length > 0 && (
        <View style={s.card}>
          <Text style={s.title}>⚑ {t('dashboard.needsAttention')}</Text>
          {data.dashboard.attention.map(a => <Text key={a.code} style={s.item}>• {attentionText(language, a)}</Text>)}
        </View>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  row: { flexDirection: 'row', gap: 10, marginBottom: 14 },
  tile: { flex: 1, backgroundColor: '#FFFFFF', borderRadius: 15, padding: 13, borderWidth: 1, borderColor: '#E8ECE4' },
  label: { fontSize: 12, color: '#778277', fontWeight: '600' },
  value: { fontSize: 18, fontWeight: '800', color: '#24352A', marginTop: 4 },
  small: { fontSize: 11, color: '#C44E45', marginTop: 3 },
  card: { backgroundColor: '#FFF7E6', borderRadius: 15, padding: 14, marginBottom: 18, borderWidth: 1, borderColor: '#F1D9A6' },
  title: { fontSize: 13, fontWeight: '800', color: '#5B4210' },
  item: { fontSize: 12, color: '#5B4210', marginTop: 6, lineHeight: 18 },
  offline: { fontSize: 11, color: '#7A857B', marginBottom: 8 },
  pending: { backgroundColor: '#EEF3E9', borderRadius: 12, padding: 10, marginBottom: 10 },
  pendingText: { fontSize: 12, color: '#3C5943', fontWeight: '700' },
});
