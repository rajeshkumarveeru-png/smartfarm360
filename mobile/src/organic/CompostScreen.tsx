import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import type { TenantSummary } from '../auth/session';
import { tr, type Language } from '../i18n/translations';
import { errorText } from '../screens/AuthScreens';
import { newEntryId } from '../storage/entries';
import { addLog, collectManure, isQuantity, loadActivities, loadBatches, loadProduce, recordOutput, spread, type Activity, type Batch, type Item } from './organicView';

const localToday = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
const LOG_KINDS = ['TURNED', 'WATERED', 'ADDED', 'CHECKED'] as const;

/** Organic manure on the phone (L13): a batch log entry, output, compost spread on a field, and manure collected from the herd. */
export default function CompostScreen({ tenant, language }: { tenant: TenantSummary; language: Language }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const canRead = tenant.permissions.includes('ORGANIC_READ') && tenant.enabledModules.includes('ORGANIC');
  const canWrite = tenant.permissions.includes('ORGANIC_WRITE');
  const canStock = tenant.permissions.includes('INVENTORY_WRITE');
  const today = localToday();
  const [batches, setBatches] = useState<Batch[] | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [activities, setActivities] = useState<Activity[]>([]);
  const [open, setOpen] = useState<Batch | null>(null);
  const [log, setLog] = useState<{ kind: string; kg: string; notes: string }>({ kind: 'TURNED', kg: '', notes: '' });
  const [out, setOut] = useState<{ itemId: number | null; quantity: string; finish: boolean }>({ itemId: null, quantity: '', finish: false });
  const [field, setField] = useState<{ toId: number | null; quantity: string }>({ toId: null, quantity: '' });
  const [manure, setManure] = useState<{ fromId: number | null; itemId: number | null; quantity: string }>({ fromId: null, itemId: null, quantity: '' });
  const [offline, setOffline] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!canRead) return;
    try { setBatches(await loadBatches(tenant.id)); setOffline(false); } catch { setOffline(true); setBatches([]); }
  }, [tenant.id, canRead]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!canRead || !tenant.permissions.includes('INVENTORY_READ')) return;
    void loadProduce(tenant.id).then(setItems).catch(() => setItems([]));
    void loadActivities(tenant.id).then(setActivities);
  }, [tenant.id, tenant.permissions, canRead]);

  if (!canRead) return <Text style={s.note}>{t('mobile.organicNotEnabled')}</Text>;
  if (!batches) return <ActivityIndicator size="large" color="#28734A" style={{ marginTop: 40 }} />;

  async function run(title: string, action: () => Promise<string>) {
    setBusy(true);
    try { const done = await action(); await load(); Alert.alert(title, done); }
    catch (e) { Alert.alert(title, errorText(language, e)); }
    finally { setBusy(false); }
  }

  const chips = <T,>(list: T[], id: (x: T) => number, label: (x: T) => string, on: number | null, pick: (n: number) => void) => (
    <View style={s.chips}>{list.map(x => (
      <Pressable key={id(x)} accessibilityRole="button" accessibilityState={{ selected: on === id(x) }} onPress={() => pick(id(x))} style={[s.chip, on === id(x) && s.chipOn]}>
        <Text style={[s.chipText, on === id(x) && s.chipTextOn]}>{label(x)}</Text></Pressable>))}</View>
  );
  const fields = activities.filter(a => a.kind === 'CROP_CYCLE' || a.kind === 'POND_CYCLE');
  const sources = activities.filter(a => a.kind === 'ANIMAL_GROUP' || a.kind === 'ORGANIC_BATCH');
  return (
    <View>
      <Text style={s.title}>{t('mobile.compost')}</Text>
      {offline && <Text style={s.note}>{t('mobile.livestockOffline')}</Text>}
      {batches.length === 0 && !offline && <Text style={s.note}>{t('organic.noBatches')}</Text>}
      {batches.map(b => (
        <Pressable key={b.id} style={[s.card, open?.id === b.id && s.selected]} onPress={() => setOpen(open?.id === b.id ? null : b)}>
          <Text style={s.cardTitle}>{b.name}</Text>
          <Text style={s.cardSub}>{t(`organic.kind.${b.kind}`)} · {t(`organic.status.${b.status}`)}</Text>
          {open?.id === b.id && canWrite && (
            <View style={{ marginTop: 8 }}>
              <Text style={s.section}>{t('organic.log')} · {today}</Text>
              <View style={s.chips}>{LOG_KINDS.map(k => (
                <Pressable key={k} accessibilityRole="button" accessibilityState={{ selected: log.kind === k }} onPress={() => setLog({ ...log, kind: k })}
                           style={[s.chip, log.kind === k && s.chipOn]}><Text style={[s.chipText, log.kind === k && s.chipTextOn]}>{t(`organic.logKind.${k}`)}</Text></Pressable>))}</View>
              {log.kind === 'ADDED' && <TextInput accessibilityLabel={t('organic.kg')} placeholder={t('organic.kg')} style={s.input} keyboardType="decimal-pad"
                                                  value={log.kg} onChangeText={v => setLog({ ...log, kg: v })} />}
              <TextInput accessibilityLabel={t('organic.notes')} placeholder={t('organic.notes')} style={s.input} value={log.notes} onChangeText={v => setLog({ ...log, notes: v })} />
              <Button title={busy ? t('mobile.saving') : t('organic.recordLog')} disabled={busy} onPress={() => {
                if (log.kg.trim() !== '' && !isQuantity(log.kg)) { Alert.alert(t('organic.log'), t('organic.errors.log')); return; }
                void run(t('organic.log'), async () => { await addLog(tenant.id, b.id, today, log.kind, log.kg, log.notes); setLog({ kind: log.kind, kg: '', notes: '' });
                  return t('organic.logSaved'); });
              }} />

              {canStock && items.length > 0 && <>
                <Text style={s.section}>{t('organic.outputs')}</Text>
                {chips(items, i => i.id, i => i.name, out.itemId, n => setOut({ ...out, itemId: n }))}
                <TextInput accessibilityLabel={t('organic.quantity')} placeholder={t('organic.quantity')} style={s.input} keyboardType="decimal-pad" value={out.quantity}
                           onChangeText={v => setOut({ ...out, quantity: v })} />
                <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: out.finish }} onPress={() => setOut({ ...out, finish: !out.finish })}>
                  <Text style={s.note}>{out.finish ? '☑' : '☐'} {t('organic.lastOutput')}</Text></Pressable>
                <Button outline title={t('organic.recordOutput')} disabled={busy} onPress={() => {
                  if (out.itemId == null || !isQuantity(out.quantity)) { Alert.alert(t('organic.outputs'), t('organic.errors.output')); return; }
                  void run(t('organic.outputs'), async () => { await recordOutput(tenant.id, b.id, today, out.itemId!, out.quantity, out.finish, newEntryId());
                    setOut({ ...out, quantity: '', finish: false }); return t('organic.outputSaved'); });
                }} />

                {fields.length > 0 && <>
                  <Text style={s.section}>{t('mobile.spreadOnField')}</Text>
                  {chips(fields, a => a.id, a => a.name, field.toId, n => setField({ ...field, toId: n }))}
                  <TextInput accessibilityLabel={t('mobile.spreadOnField')} placeholder={t('organic.quantity')} style={s.input} keyboardType="decimal-pad"
                             value={field.quantity} onChangeText={v => setField({ ...field, quantity: v })} />
                  <Button outline title={t('stock.transfer.save')} disabled={busy} onPress={() => {
                    if (out.itemId == null || field.toId == null || !isQuantity(field.quantity)) { Alert.alert(t('mobile.spreadOnField'), t('stock.transfer.errors.form')); return; }
                    void run(t('mobile.spreadOnField'), async () => {
                      const r = await spread(tenant.id, today, out.itemId!, field.quantity, b.costObjectId, field.toId!, newEntryId());
                      setField({ ...field, quantity: '' });
                      return t('stock.transfer.saved', { number: r.number, amount: Number(r.costMoved).toLocaleString(language === 'ta' ? 'ta-IN' : 'en-IN', { style: 'currency', currency: 'INR' }) });
                    });
                  }} />
                </>}
              </>}
            </View>
          )}
        </Pressable>
      ))}

      {canStock && sources.length > 0 && items.length > 0 && (
        <View style={s.card}>
          <Text style={s.cardTitle}>{t('organic.collectManure')}</Text>
          <Text style={s.label}>{t('organic.collectedFrom')}</Text>
          {chips(sources, a => a.id, a => a.name, manure.fromId, n => setManure({ ...manure, fromId: n }))}
          <Text style={s.label}>{t('organic.item')}</Text>
          {chips(items, i => i.id, i => i.name, manure.itemId, n => setManure({ ...manure, itemId: n }))}
          <TextInput accessibilityLabel={t('organic.quantity')} placeholder={t('organic.quantity')} style={s.input} keyboardType="decimal-pad" value={manure.quantity}
                     onChangeText={v => setManure({ ...manure, quantity: v })} />
          <Button title={t('organic.recordManure')} disabled={busy} onPress={() => {
            if (manure.fromId == null || manure.itemId == null || !isQuantity(manure.quantity)) { Alert.alert(t('organic.collectManure'), t('organic.errors.manure')); return; }
            void run(t('organic.collectManure'), async () => { await collectManure(tenant.id, today, manure.fromId!, manure.itemId!, manure.quantity, newEntryId());
              setManure({ ...manure, quantity: '' }); return t('organic.manureSaved', { date: today }); });
          }} />
        </View>
      )}
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
  card: { backgroundColor: '#FFFFFF', borderRadius: 14, padding: 14, marginBottom: 10, borderWidth: 1, borderColor: '#E1E8DF' },
  selected: { borderColor: '#80AD7B' },
  cardTitle: { fontSize: 15, fontWeight: '800', color: '#182A21' },
  cardSub: { fontSize: 12, color: '#7C8C7F', marginTop: 2 },
  label: { fontSize: 12, fontWeight: '700', color: '#526456', marginTop: 8 },
  input: { backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 10, padding: 10, fontSize: 15, marginTop: 6 },
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
