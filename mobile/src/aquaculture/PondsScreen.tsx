import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import type { TenantSummary } from '../auth/session';
import { tr, type Language } from '../i18n/translations';
import { loadFeeds, type FeedItem } from '../livestock/flockView';
import { errorText } from '../screens/AuthScreens';
import { isKg, isMeasure, isWhole, loadDay, loadPonds, loadSpecies, recordReading, recordSample, saveDay, type Pond, type ReadingForm, type Species } from './aquaView';

const localToday = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
const emptyReading = (): ReadingForm => ({ dissolvedOxygen: '', ph: '', temperature: '', ammonia: '' });

/** Fish farming on the phone (K13): per pond, today's feed and deaths seen, a water reading and a weight sample. */
export default function PondsScreen({ tenant, language }: { tenant: TenantSummary; language: Language }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const canRead = tenant.permissions.includes('AQUACULTURE_READ') && tenant.enabledModules.includes('AQUACULTURE');
  const canWrite = tenant.permissions.includes('AQUACULTURE_WRITE');
  const today = localToday();
  const [ponds, setPonds] = useState<Pond[] | null>(null);
  const [feeds, setFeeds] = useState<FeedItem[]>([]);
  const [open, setOpen] = useState<Pond | null>(null);
  const [species, setSpecies] = useState<Species[]>([]);
  const [feed, setFeed] = useState<{ itemId: number | null; kg: string }>({ itemId: null, kg: '' });
  const [deaths, setDeaths] = useState<{ index: number; count: string }>({ index: 0, count: '' });
  const [reading, setReading] = useState<ReadingForm>(emptyReading);
  const [sample, setSample] = useState({ fishWeighed: '', kg: '' });
  const [offline, setOffline] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!canRead) return;
    try { setPonds(await loadPonds(tenant.id)); setOffline(false); } catch { setOffline(true); setPonds([]); }
  }, [tenant.id, canRead]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (canRead && tenant.permissions.includes('INVENTORY_READ')) void loadFeeds(tenant.id).then(f => { setFeeds(f); if (f.length === 1) setFeed(x => ({ ...x, itemId: f[0].id })); }); },
    [tenant.id, tenant.permissions, canRead]);

  if (!canRead) return <Text style={s.note}>{t('mobile.aquacultureNotEnabled')}</Text>;
  if (!ponds) return <ActivityIndicator size="large" color="#28734A" style={{ marginTop: 40 }} />;

  async function choose(p: Pond) {
    if (open?.id === p.id) { setOpen(null); return; }
    setOpen(p); setSpecies([]); setDeaths({ index: 0, count: '' }); setReading(emptyReading()); setSample({ fishWeighed: '', kg: '' });
    if (p.openCycle?.stockedOn) loadSpecies(tenant.id, p.openCycle.id).then(setSpecies).catch(() => setSpecies([]));
  }

  async function run(title: string, action: () => Promise<string>) {
    setBusy(true);
    try { const done = await action(); await load(); Alert.alert(title, done); }
    catch (e) { Alert.alert(title, errorText(language, e)); }
    finally { setBusy(false); }
  }

  function saveToday(p: Pond) {
    const cycle = p.openCycle!;
    const withFeed = feed.kg.trim() !== '', withDeaths = deaths.count.trim() !== '';
    if ((withFeed && (feed.itemId == null || !isKg(feed.kg))) || (withDeaths && (!isWhole(deaths.count) || !species[deaths.index])) || (!withFeed && !withDeaths)) {
      Alert.alert(t('aquaculture.dailyRecord'), t('aquaculture.errors.day')); return;
    }
    void run(t('aquaculture.dailyRecord'), async () => {
      const loaded = await loadDay(tenant.id, cycle.id, today);
      await saveDay(tenant.id, cycle.id, today, loaded, withFeed ? { itemId: feed.itemId!, kg: feed.kg } : null,
        withDeaths ? { species: species[deaths.index], count: deaths.count } : null);
      setFeed(f => ({ ...f, kg: '' })); setDeaths(d => ({ ...d, count: '' }));
      return t('aquaculture.daySaved', { date: today });
    });
  }

  const name = (x: Species) => x.otherName ?? t(`aquaculture.species.${x.species}`);
  return (
    <View>
      <Text style={s.title}>{t('aquaculture.ponds')}</Text>
      {offline && <Text style={s.note}>{t('mobile.livestockOffline')}</Text>}
      {ponds.length === 0 && !offline && <Text style={s.note}>{t('aquaculture.noPonds')}</Text>}
      {ponds.map(p => {
        const growing = p.openCycle?.status === 'GROWING';
        const flagged = (p.latestReading?.outOfLimits ?? []).map(x => t(`aquaculture.parameter.${x}`));
        return (
          <Pressable key={p.id} style={[s.card, open?.id === p.id && s.selected]} onPress={() => void choose(p)}>
            <Text style={s.cardTitle}>{p.name}</Text>
            <Text style={s.cardSub}>{p.openCycle ? `${p.openCycle.name} · ${t(`aquaculture.status.${p.openCycle.status}`)}` : t('aquaculture.noCycles')}</Text>
            {flagged.length > 0 && <Text style={s.flag}>⚠ {flagged.join(', ')} {t('aquaculture.outsideLimits')}</Text>}
            {open?.id === p.id && canWrite && (
              <View style={{ marginTop: 8 }}>
                {growing && <>
                  <Text style={s.section}>{t('aquaculture.dailyRecord')} · {today}</Text>
                  {feeds.length > 0 && <View style={s.chips}>{feeds.map(i => (
                    <Pressable key={i.id} accessibilityRole="button" accessibilityState={{ selected: feed.itemId === i.id }} onPress={() => setFeed({ ...feed, itemId: i.id })}
                               style={[s.chip, feed.itemId === i.id && s.chipOn]}><Text style={[s.chipText, feed.itemId === i.id && s.chipTextOn]}>{i.name}</Text></Pressable>))}</View>}
                  <Text style={s.label}>{t('aquaculture.feedKg')}</Text>
                  <TextInput accessibilityLabel={t('aquaculture.feedKg')} style={s.input} keyboardType="decimal-pad" value={feed.kg} onChangeText={v => setFeed({ ...feed, kg: v })} />
                  {species.length > 0 && <>
                    <Text style={s.label}>{t('aquaculture.deathsSeen')}</Text>
                    <View style={s.chips}>{species.map((x, i) => (
                      <Pressable key={i} accessibilityRole="button" accessibilityState={{ selected: deaths.index === i }} onPress={() => setDeaths({ ...deaths, index: i })}
                                 style={[s.chip, deaths.index === i && s.chipOn]}><Text style={[s.chipText, deaths.index === i && s.chipTextOn]}>{name(x)}</Text></Pressable>))}</View>
                    <TextInput accessibilityLabel={t('aquaculture.deathsSeen')} style={s.input} keyboardType="number-pad" value={deaths.count}
                               onChangeText={v => setDeaths({ ...deaths, count: v })} />
                  </>}
                  <Button title={busy ? t('mobile.saving') : t('aquaculture.saveDay')} disabled={busy} onPress={() => saveToday(p)} />
                </>}

                <Text style={s.section}>{t('aquaculture.water')}</Text>
                <View style={s.grid}>{(['dissolvedOxygen', 'ph', 'temperature', 'ammonia'] as const).map(k => {
                  const param = { dissolvedOxygen: 'DISSOLVED_OXYGEN', ph: 'PH', temperature: 'TEMPERATURE', ammonia: 'AMMONIA' }[k];
                  return (
                    <View key={k} style={s.half}>
                      <Text style={s.label}>{t(`aquaculture.parameter.${param}`)}</Text>
                      <TextInput accessibilityLabel={t(`aquaculture.parameter.${param}`)} style={s.input} keyboardType="decimal-pad" value={reading[k]}
                                 onChangeText={v => setReading({ ...reading, [k]: v })} />
                    </View>
                  );
                })}</View>
                <Button outline title={t('aquaculture.recordReading')} disabled={busy} onPress={() => {
                  const values = Object.values(reading);
                  if (!values.every(isMeasure) || values.every(v => v.trim() === '')) { Alert.alert(t('aquaculture.water'), t('aquaculture.errors.reading')); return; }
                  void run(t('aquaculture.water'), async () => {
                    const r = await recordReading(tenant.id, p.id, reading);
                    setReading(emptyReading());
                    return r.outOfLimits.length > 0 ? t('aquaculture.readingFlagged', { parameters: r.outOfLimits.map(x => t(`aquaculture.parameter.${x}`)).join(', ') })
                      : t('aquaculture.readingSaved');
                  });
                }} />

                {growing && <>
                  <Text style={s.section}>{t('aquaculture.samples')}</Text>
                  <View style={s.grid}>
                    <View style={s.half}><Text style={s.label}>{t('aquaculture.fishWeighed')}</Text>
                      <TextInput accessibilityLabel={t('aquaculture.fishWeighed')} style={s.input} keyboardType="number-pad" value={sample.fishWeighed}
                                 onChangeText={v => setSample({ ...sample, fishWeighed: v })} /></View>
                    <View style={s.half}><Text style={s.label}>{t('aquaculture.totalKg')}</Text>
                      <TextInput accessibilityLabel={t('aquaculture.totalKg')} style={s.input} keyboardType="decimal-pad" value={sample.kg}
                                 onChangeText={v => setSample({ ...sample, kg: v })} /></View>
                  </View>
                  <Button outline title={t('aquaculture.recordSample')} disabled={busy} onPress={() => {
                    if (!isWhole(sample.fishWeighed) || !isKg(sample.kg)) { Alert.alert(t('aquaculture.samples'), t('aquaculture.errors.sample')); return; }
                    void run(t('aquaculture.samples'), async () => {
                      const r = await recordSample(tenant.id, p.openCycle!.id, today, sample.fishWeighed, sample.kg);
                      setSample({ fishWeighed: '', kg: '' });
                      return t('aquaculture.sampleSaved', { grams: String(r.averageGrams) });
                    });
                  }} />
                </>}
              </View>
            )}
          </Pressable>
        );
      })}
      <Button title={t('mobile.refresh')} onPress={() => void load()} outline />
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
  section: { fontSize: 14, fontWeight: '800', color: '#24352A', marginTop: 14 },
  note: { fontSize: 12, color: '#66816A', marginVertical: 8 },
  flag: { fontSize: 12, color: '#B5532F', fontWeight: '700', marginTop: 4 },
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
