import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import type { TenantSummary } from '../auth/session';
import { tr, type Language } from '../i18n/translations';
import { errorText } from '../screens/AuthScreens';
import { dayFormValid, isWhole, loadDay, loadFeeds, loadFlocks, recordHeads, saveDay, toDayForm, type BatchDay, type DayForm, type FeedItem, type Flock } from './flockView';

/** Poultry batches and goat and sheep herds on the Livestock tab (J12): today's batch record, and quick died, culled or born counts for herds. */
export default function FlockSection({ tenant, language, today }: { tenant: TenantSummary; language: Language; today: string }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const canWrite = tenant.permissions.includes('LIVESTOCK_WRITE');
  const [flocks, setFlocks] = useState<Flock[] | null>(null);
  const [feeds, setFeeds] = useState<FeedItem[]>([]);
  const [open, setOpen] = useState<Flock | null>(null);
  const [day, setDay] = useState<BatchDay | null>(null);
  const [form, setForm] = useState<DayForm | null>(null);
  const [heads, setHeads] = useState('');
  const [offline, setOffline] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setFlocks(await loadFlocks(tenant.id, tenant.enabledModules)); setOffline(false);
    } catch {
      setOffline(true); setFlocks([]);
    }
  }, [tenant.id, tenant.enabledModules]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (tenant.enabledModules.includes('POULTRY') && tenant.permissions.includes('INVENTORY_READ')) void loadFeeds(tenant.id).then(setFeeds); },
    [tenant.id, tenant.enabledModules, tenant.permissions]);

  async function choose(f: Flock) {
    if (open?.id === f.id) { setOpen(null); return; }
    setOpen(f); setDay(null); setForm(null); setHeads('');
    if (f.species !== 'CHICKEN') return;
    try { const d = await loadDay(tenant.id, f.id, today); setDay(d); setForm(toDayForm(d)); }
    catch (e) { Alert.alert(t('livestock.dailyRecord'), errorText(language, e)); }
  }

  async function run(title: string, action: () => Promise<unknown>, done: string) {
    setBusy(true);
    try { await action(); await load(); Alert.alert(title, done); }
    catch (e) { Alert.alert(title, errorText(language, e)); }
    finally { setBusy(false); }
  }

  if (!flocks) return null;
  const set = (patch: Partial<DayForm>) => setForm(x => (x ? { ...x, ...patch } : x));
  const field = (label: string, key: 'deaths' | 'culls' | 'eggsGood' | 'eggsCracked' | 'eggsBroken' | 'feedKg') => (
    <View key={key} style={s.half}>
      <Text style={s.label}>{t(label)}</Text>
      <TextInput accessibilityLabel={t(label)} style={s.input} keyboardType={key === 'feedKg' ? 'decimal-pad' : 'number-pad'} value={form![key]}
                 onChangeText={v => set({ [key]: v })} />
    </View>
  );
  return (
    <View>
      <Text style={[s.title, { marginTop: 16 }]}>{t('mobile.batchesAndHerds')}</Text>
      {offline && <Text style={s.note}>{t('mobile.livestockOffline')}</Text>}
      {flocks.length === 0 && !offline && <Text style={s.note}>{t('livestock.noBatches')}</Text>}
      {flocks.map(f => (
        <Pressable key={f.id} style={[s.card, open?.id === f.id && s.selected]} onPress={() => void choose(f)}>
          <Text style={s.cardTitle}>{f.name}</Text>
          <Text style={s.cardSub}>{t(`livestock.species.${f.species}`)} · {t(`livestock.purpose.${f.purpose}`)} · {t('livestock.headsNow', { count: String(f.animals) })}</Text>
          {open?.id === f.id && f.species === 'CHICKEN' && form && day && (
            <View style={{ marginTop: 8 }}>
              <Text style={s.label}>{t('livestock.dailyRecord')} · {today}</Text>
              {day.warnings.map(w => <Text key={w} style={s.warn}>⚠ {t(`livestock.warning.${w}`)}</Text>)}
              <View style={s.grid}>
                {field('livestock.deaths', 'deaths')}
                {field('livestock.culls', 'culls')}
                {f.purpose !== 'BROILER' && <>{field('livestock.eggsGood', 'eggsGood')}{field('livestock.eggsCracked', 'eggsCracked')}{field('livestock.eggsBroken', 'eggsBroken')}</>}
                {field('livestock.feedKg', 'feedKg')}
              </View>
              {feeds.length > 0 && <View style={s.chips}>{feeds.map(i => (
                <Pressable key={i.id} accessibilityRole="button" accessibilityState={{ selected: form.feedItemId === i.id }} onPress={() => set({ feedItemId: i.id })}
                           style={[s.chip, form.feedItemId === i.id && s.chipOn]}><Text style={[s.chipText, form.feedItemId === i.id && s.chipTextOn]}>{i.name}</Text></Pressable>))}</View>}
              {canWrite && <Button title={busy ? t('mobile.saving') : t('livestock.saveDay')} disabled={busy} onPress={() => {
                if (!dayFormValid(form)) { Alert.alert(t('livestock.dailyRecord'), t(form.feedKg.trim() !== '' || form.feedItemId != null ? 'livestock.errors.feed' : 'livestock.errors.wholeNumbers')); return; }
                void run(t('livestock.dailyRecord'), async () => { const d = await saveDay(tenant.id, f.id, today, form, day); setDay(d); setForm(toDayForm(d)); },
                  t('livestock.dayRecordSaved', { date: today }));
              }} />}
            </View>
          )}
          {open?.id === f.id && f.species !== 'CHICKEN' && canWrite && (
            <View style={{ marginTop: 8 }}>
              <Text style={s.label}>{t('livestock.heads')}</Text>
              <TextInput accessibilityLabel={t('livestock.heads')} style={s.input} keyboardType="number-pad" value={heads} onChangeText={setHeads} />
              <View style={s.chips}>{(['BORN', 'DIED', 'CULLED'] as const).map(k => (
                <Button key={k} outline title={t(`livestock.moveKind.${k}`)} disabled={busy || heads.trim() === '' || !isWhole(heads) || Number(heads) <= 0}
                        onPress={() => void run(f.name, () => recordHeads(tenant.id, f.id, today, k, heads).then(() => setHeads('')), t('livestock.headsRecorded'))} />))}</View>
            </View>
          )}
        </Pressable>
      ))}
    </View>
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
  note: { fontSize: 12, color: '#66816A', marginVertical: 8 },
  warn: { fontSize: 12, color: '#9C3E30', fontWeight: '700', marginTop: 4 },
  card: { backgroundColor: '#FFFFFF', borderRadius: 14, padding: 14, marginBottom: 10, borderWidth: 1, borderColor: '#E1E8DF' },
  selected: { borderColor: '#80AD7B' },
  cardTitle: { fontSize: 15, fontWeight: '800', color: '#182A21' },
  cardSub: { fontSize: 12, color: '#7C8C7F', marginTop: 2 },
  label: { fontSize: 12, fontWeight: '700', color: '#526456', marginTop: 8 },
  input: { backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 10, padding: 10, fontSize: 15, marginTop: 4 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  half: { width: '47%' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  chip: { borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 16, paddingVertical: 8, paddingHorizontal: 14, backgroundColor: '#FFFFFF' },
  chipOn: { backgroundColor: '#28633E', borderColor: '#28633E' },
  chipText: { fontSize: 13, color: '#31583E' },
  chipTextOn: { color: '#FFFFFF', fontWeight: '700' },
  button: { backgroundColor: '#28633E', borderRadius: 10, paddingVertical: 12, paddingHorizontal: 12, marginTop: 8, alignItems: 'center' },
  buttonText: { color: '#FFFFFF', fontWeight: '700', fontSize: 14 },
  outline: { backgroundColor: 'transparent', borderWidth: 1, borderColor: '#28633E' },
  outlineText: { color: '#28633E' },
});
