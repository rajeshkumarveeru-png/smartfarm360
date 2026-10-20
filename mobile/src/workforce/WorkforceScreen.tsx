import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { tr, type Language } from '../i18n/translations';
import { errorText } from '../screens/AuthScreens';
import type { TenantSummary } from '../auth/session';
import {
  ATTENDANCE, initialMarks, loadActivities, loadBalances, loadMuster, markAll, saveMuster, shiftDay,
  type Activity, type Mark, type Muster, type WorkerBalance,
} from './workforceView';

const money = (n: number) => `₹${Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
const localToday = () => { const d = new Date(); return shiftDay(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`, 0); };

/** Workers on the phone: mark the day's attendance and see what each worker is owed (online). */
export default function WorkforceScreen({ tenant, language }: { tenant: TenantSummary; language: Language }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const canRead = tenant.permissions.includes('WORKFORCE_READ') && tenant.enabledModules.includes('WORKFORCE');
  const canWrite = tenant.permissions.includes('WORKFORCE_WRITE');
  const [date, setDate] = useState(localToday);
  const [muster, setMuster] = useState<Muster | null>(null);
  const [marks, setMarks] = useState<Record<number, Mark>>({});
  const [activities, setActivities] = useState<Activity[]>([]);
  const [balances, setBalances] = useState<WorkerBalance[]>([]);
  const [offline, setOffline] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!canRead) return;
    try {
      const [m, b] = await Promise.all([loadMuster(tenant.id, date), loadBalances(tenant.id)]);
      setMuster(m); setMarks(initialMarks(m)); setBalances(b); setOffline(false);
    } catch {
      setOffline(true); setMuster({ date, workers: [] });
    }
  }, [tenant.id, date, canRead]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (canRead && tenant.permissions.includes('CROP_READ') && tenant.enabledModules.includes('CROP')) loadActivities(tenant.id).then(setActivities).catch(() => setActivities([]));
  }, [tenant, canRead]);

  async function save() {
    if (!muster) return;
    const request = saveMuster(tenant.id, muster, marks);
    if (!request) { Alert.alert(t('workforce.title'), t('mobile.nothingChanged')); return; }
    setBusy(true);
    try {
      const saved = await request;
      setMuster(saved); setMarks(initialMarks(saved));
      Alert.alert(t('workforce.title'), t('workforce.saved', { date }));
    } catch (e) {
      Alert.alert(t('workforce.title'), errorText(language, e));
    } finally {
      setBusy(false);
    }
  }

  if (!canRead) return <Text style={s.note}>{t('mobile.workforceNotEnabled')}</Text>;
  if (!muster) return <ActivityIndicator size="large" color="#28734A" style={{ marginTop: 40 }} />;
  const nameOf = (id: number | null) => activities.find(a => a.costObjectId === id)?.name ?? t('workforce.general');
  return (
    <View>
      <Text style={s.title}>{t('workforce.attendance')}</Text>
      <Text style={s.subtitle}>{t('workforce.attendanceHelp')}</Text>
      <View style={s.dateRow}>
        <Chip label="‹" onPress={() => setDate(d => shiftDay(d, -1))} />
        <Text style={s.date}>{date}</Text>
        <Chip label="›" disabled={date >= localToday()} onPress={() => setDate(d => shiftDay(d, 1))} />
      </View>
      {offline && <Text style={s.note}>{t('mobile.workforceOffline')}</Text>}
      {!offline && muster.workers.length === 0 && <Text style={s.note}>{t('workforce.noWorkers')}</Text>}
      {canWrite && muster.workers.length > 0 && (
        <View style={s.choices}>
          <Chip label={t('workforce.allPresent')} onPress={() => setMarks(m => markAll(muster, m, 'PRESENT', null))} />
          {activities.map(a => <Chip key={a.costObjectId} label={t('workforce.allPresentOn', { activity: a.name })}
                                     onPress={() => setMarks(m => markAll(muster, m, 'PRESENT', a.costObjectId))} />)}
        </View>
      )}
      {muster.workers.map(w => {
        const mark = marks[w.workerId] ?? { status: w.status, costObjectId: w.costObjectId };
        const working = mark.status === 'PRESENT' || mark.status === 'HALF_DAY';
        const disabled = !canWrite || w.locked;
        return (
          <View key={w.workerId} style={s.card}>
            <Text style={s.cardTitle}>{w.workerName}</Text>
            <Text style={s.cardSub}>{t(`workforce.payType.${w.payType}`)}{w.locked ? ` · ${t('workforce.paid')}` : ''}{working ? ` · ${nameOf(mark.costObjectId)}` : ''}</Text>
            <View style={s.choices}>
              {ATTENDANCE.map(status => <Chip key={status} label={t(`workforce.status.${status}`)} on={mark.status === status} disabled={disabled}
                onPress={() => setMarks(m => ({ ...m, [w.workerId]: { ...mark, status: mark.status === status ? null : status } }))} />)}
            </View>
            {working && !disabled && activities.length > 0 && (
              <View style={s.choices}>
                {[{ costObjectId: null as number | null, name: t('workforce.general') }, ...activities].map(a => (
                  <Chip key={String(a.costObjectId)} label={a.name} small on={mark.costObjectId === a.costObjectId}
                        onPress={() => setMarks(m => ({ ...m, [w.workerId]: { ...mark, costObjectId: a.costObjectId } }))} />
                ))}
              </View>
            )}
          </View>
        );
      })}
      {canWrite && muster.workers.length > 0 && <Button title={busy ? t('mobile.saving') : t('workforce.saveAttendance')} disabled={busy} onPress={() => void save()} />}

      {balances.length > 0 && (
        <>
          <Text style={[s.title, { marginTop: 18 }]}>{t('workforce.workers')}</Text>
          {balances.map(b => (
            <View key={b.id} style={s.card}>
              <Text style={s.cardTitle}>{b.name}</Text>
              <View style={s.row}><Text style={s.rowLabel}>{t('workforce.owed')}</Text><Text style={s.rowValue}>{money(b.owed)}</Text></View>
              {Number(b.advance) > 0 && <View style={s.row}><Text style={s.rowLabel}>{t('workforce.advance')}</Text><Text style={s.rowValue}>{money(b.advance)}</Text></View>}
            </View>
          ))}
        </>
      )}
      <Button title={t('mobile.refresh')} onPress={() => void load()} outline />
    </View>
  );
}

function Chip({ label, on, disabled, small, onPress }: { label: string; on?: boolean; disabled?: boolean; small?: boolean; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected: !!on, disabled: !!disabled }} disabled={disabled} onPress={onPress}
               style={[s.chip, on && s.chipOn, disabled && s.chipOff, small && s.chipSmall]}>
      <Text style={[s.chipText, on && s.chipTextOn]}>{label}</Text>
    </Pressable>
  );
}

function Button({ title, onPress, outline, disabled }: { title: string; onPress: () => void; outline?: boolean; disabled?: boolean }) {
  return (
    <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[s.button, outline && s.outline, disabled && s.chipOff]}>
      <Text style={[s.buttonText, outline && s.outlineText]}>{title}</Text>
    </Pressable>
  );
}

const s = StyleSheet.create({
  title: { fontSize: 22, fontWeight: '800', color: '#182A21', marginBottom: 2 },
  subtitle: { fontSize: 13, color: '#66816A', marginBottom: 12 },
  note: { fontSize: 13, color: '#66816A', marginVertical: 12 },
  dateRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 10 },
  date: { fontSize: 16, fontWeight: '800', color: '#182A21' },
  card: { backgroundColor: '#FFFFFF', borderRadius: 14, padding: 14, marginBottom: 10, borderWidth: 1, borderColor: '#E1E8DF', gap: 6 },
  cardTitle: { fontSize: 16, fontWeight: '800', color: '#182A21' },
  cardSub: { fontSize: 12, color: '#7C8C7F' },
  row: { flexDirection: 'row', justifyContent: 'space-between' },
  rowLabel: { fontSize: 13, color: '#526456' },
  rowValue: { fontSize: 13, fontWeight: '800', color: '#182A21' },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: 6 },
  chip: { borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 16, paddingVertical: 8, paddingHorizontal: 12, backgroundColor: '#FFFFFF' },
  chipSmall: { paddingVertical: 5, paddingHorizontal: 9 },
  chipOn: { backgroundColor: '#28633E', borderColor: '#28633E' },
  chipOff: { opacity: 0.5 },
  chipText: { fontSize: 13, color: '#31583E' },
  chipTextOn: { color: '#FFFFFF', fontWeight: '700' },
  button: { backgroundColor: '#28633E', borderRadius: 10, paddingVertical: 12, paddingHorizontal: 12, marginTop: 8, alignItems: 'center' },
  buttonText: { color: '#FFFFFF', fontWeight: '700', fontSize: 14 },
  outline: { backgroundColor: 'transparent', borderWidth: 1, borderColor: '#28633E' },
  outlineText: { color: '#28633E' },
});
