import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import type { TenantSummary } from '../auth/session';
import { tr, type Language } from '../i18n/translations';
import { errorText } from '../screens/AuthScreens';
import { newEntryId } from '../storage/entries';
import { flags, isLitres, loadHerd, loadMilkDay, milkingGroups, recordHeat, recordTreatment, saveSession, type Animal, type Group, type MilkDay, type Session } from './livestockView';

const localToday = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };

/** The Livestock tab (I15): today's milk by session, quick heat and treatment notes, and the animals with their flags. */
export default function LivestockScreen({ tenant, language }: { tenant: TenantSummary; language: Language }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const canRead = tenant.permissions.includes('LIVESTOCK_READ') && tenant.enabledModules.includes('DAIRY');
  const canWrite = tenant.permissions.includes('LIVESTOCK_WRITE');
  const today = localToday();
  const [groups, setGroups] = useState<Group[]>([]);
  const [animals, setAnimals] = useState<Animal[] | null>(null);
  const [day, setDay] = useState<MilkDay | null>(null);
  const [session, setSession] = useState<Session>(new Date().getHours() < 12 ? 'MORNING' : 'EVENING');
  const [totals, setTotals] = useState<Record<number, string>>({});
  const [yields, setYields] = useState<Record<number, string>>({});
  const [selected, setSelected] = useState<Animal | null>(null);
  const [note, setNote] = useState({ description: '', withdrawal: '' });
  const [offline, setOffline] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!canRead) return;
    try {
      const [herd, milk] = await Promise.all([loadHerd(tenant.id), loadMilkDay(tenant.id, today)]);
      setGroups(herd.groups); setAnimals(herd.animals); setDay(milk); setOffline(false);
    } catch {
      setOffline(true); setAnimals([]);
    }
  }, [tenant.id, canRead, today]);
  useEffect(() => { void load(); }, [load]);

  if (!canRead) return <Text style={s.note}>{t('mobile.livestockNotEnabled')}</Text>;
  if (!animals) return <ActivityIndicator size="large" color="#28734A" style={{ marginTop: 40 }} />;
  const milking = milkingGroups(groups, animals);

  async function save(group: Group) {
    const herd = animals!.filter(a => a.groupId === group.id);
    const perAnimal = Object.fromEntries(herd.map(a => [a.id, yields[a.id] ?? '']));
    const values = [totals[group.id] ?? '', ...Object.values(perAnimal)].filter(v => v.trim() !== '');
    if (values.length === 0 || !values.every(isLitres)) { Alert.alert(t('livestock.milk'), t('livestock.errors.litres')); return; }
    setBusy(true);
    try {
      setDay(await saveSession(tenant.id, today, session, group.id, totals[group.id] ?? '', perAnimal));
      setTotals(x => ({ ...x, [group.id]: '' }));
      setYields(x => { const y = { ...x }; herd.forEach(a => delete y[a.id]); return y; });
      Alert.alert(t('livestock.milk'), t('livestock.milkSaved', { date: today }));
    } catch (e) {
      Alert.alert(t('livestock.milk'), errorText(language, e));
    } finally {
      setBusy(false);
    }
  }

  async function quick(action: () => Promise<unknown>) {
    setBusy(true);
    try { await action(); setSelected(null); setNote({ description: '', withdrawal: '' }); await load(); }
    catch (e) { Alert.alert(t('livestock.title'), errorText(language, e)); }
    finally { setBusy(false); }
  }

  const recorded = (g: Group) => day?.sessions.find(x => x.session === session && x.groupId === g.id)?.litres;
  return (
    <View>
      <Text style={s.title}>{t('livestock.title')}</Text>
      {offline && <Text style={s.note}>{t('mobile.livestockOffline')}</Text>}
      {day && <Text style={s.subtitle}>{t('livestock.dayTotal', { litres: String(day.total) })}</Text>}
      {day && day.withheld.length > 0 && (
        <View style={s.warn}><Text style={s.warnText}>⚠ {t('livestock.withheldWarning', { animals: day.withheld.map(w => `${w.tag} (${w.until})`).join(', ') })}</Text></View>
      )}
      {canWrite && milking.length > 0 && (
        <>
          <View style={s.row}>{(['MORNING', 'EVENING'] as Session[]).map(x => (
            <Chip key={x} label={t(`livestock.session.${x}`)} on={session === x} onPress={() => setSession(x)} />))}</View>
          {milking.map(g => {
            const herd = animals.filter(a => a.groupId === g.id && a.sex === 'FEMALE');
            const done = recorded(g);
            return (
              <View key={g.id} style={s.card}>
                <Text style={s.cardTitle}>{g.name}{done != null ? ` · ${t('mobile.recordedLitres', { litres: String(done) })}` : ''}</Text>
                <Text style={s.label}>{t('livestock.groupLitres')}</Text>
                <TextInput accessibilityLabel={t('livestock.litresFor', { animal: g.name, session: t(`livestock.session.${session}`) })} style={s.input}
                           keyboardType="decimal-pad" value={totals[g.id] ?? ''} onChangeText={v => setTotals(x => ({ ...x, [g.id]: v }))} />
                {herd.length > 0 && <Text style={s.label}>{t('livestock.perAnimal')}</Text>}
                {herd.map(a => (
                  <View key={a.id} style={s.yieldRow}>
                    <Text style={s.yieldName}>{a.tag}{a.name ? ` ${a.name}` : ''}{a.breeding.withheldUntil && a.breeding.withheldUntil >= today ? ` · ${t('livestock.withheld')}` : ''}</Text>
                    <TextInput accessibilityLabel={t('livestock.litresFor', { animal: a.tag, session: t(`livestock.session.${session}`) })} style={[s.input, s.small]}
                               keyboardType="decimal-pad" value={yields[a.id] ?? ''} onChangeText={v => setYields(x => ({ ...x, [a.id]: v }))} />
                  </View>
                ))}
                <Button title={busy ? t('mobile.saving') : t('livestock.saveMilk')} disabled={busy} onPress={() => void save(g)} />
              </View>
            );
          })}
        </>
      )}

      <Text style={[s.title, { marginTop: 16 }]}>{t('livestock.herd')}</Text>
      {animals.length === 0 && !offline && <Text style={s.note}>{t('livestock.noAnimals')}</Text>}
      {animals.map(a => {
        const f = flags(a, today);
        return (
          <Pressable key={a.id} style={[s.card, selected?.id === a.id && s.selected]} onPress={() => setSelected(selected?.id === a.id ? null : a)}>
            <Text style={s.cardTitle}>{a.tag}{a.name ? ` ${a.name}` : ''}</Text>
            <Text style={s.cardSub}>{a.groupName ?? ''}{a.breeding.repro && a.breeding.repro !== 'OPEN' ? ` · ${t(`livestock.repro.${a.breeding.repro}`)}` : ''}</Text>
            {f.length > 0 && <Text style={s.flag}>{f.map(x => t(`livestock.flag.${x}`)).join(' · ')}</Text>}
            {selected?.id === a.id && canWrite && (
              <View style={{ gap: 6, marginTop: 8 }}>
                {a.sex === 'FEMALE' && <Button title={t('mobile.heatSeen')} outline disabled={busy} onPress={() => void quick(() => recordHeat(tenant.id, a.id, today))} />}
                <Text style={s.label}>{t('livestock.description')}</Text>
                <TextInput style={s.input} value={note.description} onChangeText={v => setNote(n => ({ ...n, description: v }))} />
                <Text style={s.label}>{t('livestock.withdrawalDays')}</Text>
                <TextInput style={s.input} keyboardType="number-pad" value={note.withdrawal} onChangeText={v => setNote(n => ({ ...n, withdrawal: v }))} />
                <Button title={t('mobile.treatmentGiven')} disabled={busy || note.description.trim() === ''}
                        onPress={() => void quick(() => recordTreatment(tenant.id, a.id, today, note.description, note.withdrawal, newEntryId()))} />
                <Text style={s.note}>{t('livestock.noAdvice')}</Text>
              </View>
            )}
          </Pressable>
        );
      })}
      <Button title={t('mobile.refresh')} onPress={() => void load()} outline />
    </View>
  );
}

function Chip({ label, on, onPress }: { label: string; on: boolean; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected: on }} onPress={onPress} style={[s.chip, on && s.chipOn]}>
      <Text style={[s.chipText, on && s.chipTextOn]}>{label}</Text>
    </Pressable>
  );
}

function Button({ title, onPress, outline, disabled }: { title: string; onPress: () => void; outline?: boolean; disabled?: boolean }) {
  return (
    <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[s.button, outline && s.outline, disabled && { opacity: 0.5 }]}>
      <Text style={[s.buttonText, outline && s.outlineText]}>{title}</Text>
    </Pressable>
  );
}

const s = StyleSheet.create({
  title: { fontSize: 22, fontWeight: '800', color: '#182A21', marginBottom: 2 },
  subtitle: { fontSize: 13, color: '#66816A', marginBottom: 10 },
  note: { fontSize: 12, color: '#66816A', marginVertical: 8 },
  warn: { backgroundColor: '#FFF0ED', borderRadius: 12, padding: 10, marginBottom: 10 },
  warnText: { fontSize: 12, color: '#9C3E30', fontWeight: '700' },
  row: { flexDirection: 'row', gap: 8, marginBottom: 10 },
  card: { backgroundColor: '#FFFFFF', borderRadius: 14, padding: 14, marginBottom: 10, borderWidth: 1, borderColor: '#E1E8DF' },
  selected: { borderColor: '#80AD7B' },
  cardTitle: { fontSize: 15, fontWeight: '800', color: '#182A21' },
  cardSub: { fontSize: 12, color: '#7C8C7F', marginTop: 2 },
  flag: { fontSize: 12, color: '#B5532F', fontWeight: '700', marginTop: 4 },
  label: { fontSize: 12, fontWeight: '700', color: '#526456', marginTop: 8 },
  input: { backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 10, padding: 10, fontSize: 15, marginTop: 4 },
  small: { width: 90 },
  yieldRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 4 },
  yieldName: { fontSize: 13, color: '#24352A', flex: 1 },
  chip: { borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 16, paddingVertical: 8, paddingHorizontal: 14, backgroundColor: '#FFFFFF' },
  chipOn: { backgroundColor: '#28633E', borderColor: '#28633E' },
  chipText: { fontSize: 13, color: '#31583E' },
  chipTextOn: { color: '#FFFFFF', fontWeight: '700' },
  button: { backgroundColor: '#28633E', borderRadius: 10, paddingVertical: 12, paddingHorizontal: 12, marginTop: 8, alignItems: 'center' },
  buttonText: { color: '#FFFFFF', fontWeight: '700', fontSize: 14 },
  outline: { backgroundColor: 'transparent', borderWidth: 1, borderColor: '#28633E' },
  outlineText: { color: '#28633E' },
});
