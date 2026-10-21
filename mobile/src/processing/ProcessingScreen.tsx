import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import type { TenantSummary } from '../auth/session';
import { tr, type Language } from '../i18n/translations';
import { errorText } from '../screens/AuthScreens';
import { newEntryId } from '../storage/entries';
import { addStep, isOptional, isQuantity, loadBatches, loadLots, loadProduce, loadSources, moveIn, recordOutput, type Activity, type Batch, type Item,
  type Lot } from './processingView';

const localToday = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
const STEPS = ['CLEANING', 'DRYING', 'GRADING', 'MILLING', 'PRESSING', 'PACKING'] as const;
const ROLES = ['MAIN', 'BYPRODUCT', 'WASTE'] as const;

/** Processing on the phone (M13): own produce into a batch, a step, and output. */
export default function ProcessingScreen({ tenant, language }: { tenant: TenantSummary; language: Language }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const canRead = tenant.permissions.includes('PROCESSING_READ') && tenant.enabledModules.includes('PROCESSING');
  const canWrite = tenant.permissions.includes('PROCESSING_WRITE');
  const canStock = tenant.permissions.includes('INVENTORY_WRITE');
  const today = localToday();
  const [batches, setBatches] = useState<Batch[] | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [sources, setSources] = useState<Activity[]>([]);
  const [lots, setLots] = useState<Lot[]>([]);
  const [open, setOpen] = useState<Batch | null>(null);
  const [input, setInput] = useState<{ itemId: number | null; lotNo: string | null; fromId: number | null; quantity: string }>({ itemId: null, lotNo: null, fromId: null, quantity: '' });
  const [step, setStep] = useState({ kind: 'DRYING', kgIn: '', kgOut: '', before: '', after: '' });
  const [out, setOut] = useState<{ role: typeof ROLES[number]; itemId: number | null; description: string; quantity: string; finish: boolean }>(
    { role: 'MAIN', itemId: null, description: '', quantity: '', finish: false });
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
    void loadSources(tenant.id).then(setSources);
  }, [tenant.id, tenant.permissions, canRead]);

  if (!canRead) return <Text style={s.note}>{t('mobile.processingNotEnabled')}</Text>;
  if (!batches) return <ActivityIndicator size="large" color="#28734A" style={{ marginTop: 40 }} />;

  async function run(title: string, action: () => Promise<string>) {
    setBusy(true);
    try { const done = await action(); await load(); Alert.alert(title, done); }
    catch (e) { Alert.alert(title, errorText(language, e)); }
    finally { setBusy(false); }
  }

  function pickInputItem(item: Item) {
    setInput({ ...input, itemId: item.id, lotNo: null });
    setLots([]);
    if (item.trackLots) void loadLots(tenant.id, item.id).then(l => { setLots(l); if (l.length === 1) setInput(x => ({ ...x, lotNo: l[0].lotNo })); }).catch(() => setLots([]));
  }

  const chips = <T,>(list: T[], id: (x: T) => string | number, label: (x: T) => string, on: string | number | null, pick: (x: T) => void) => (
    <View style={s.chips}>{list.map(x => (
      <Pressable key={String(id(x))} accessibilityRole="button" accessibilityState={{ selected: on === id(x) }} onPress={() => pick(x)} style={[s.chip, on === id(x) && s.chipOn]}>
        <Text style={[s.chipText, on === id(x) && s.chipTextOn]}>{label(x)}</Text></Pressable>))}</View>
  );
  const money = (v: number) => Number(v).toLocaleString(language === 'ta' ? 'ta-IN' : 'en-IN', { style: 'currency', currency: 'INR' });
  return (
    <View>
      <Text style={s.title}>{t('mobile.processing')}</Text>
      {offline && <Text style={s.note}>{t('mobile.livestockOffline')}</Text>}
      {batches.length === 0 && !offline && <Text style={s.note}>{t('processing.noBatches')}</Text>}
      {batches.map(b => (
        <Pressable key={b.id} style={[s.card, open?.id === b.id && s.selected]} onPress={() => { setOpen(open?.id === b.id ? null : b); setOut(o => ({ ...o, itemId: b.mainItemId })); }}>
          <Text style={s.cardTitle}>{b.code} · {b.name}</Text>
          <Text style={s.cardSub}>{t('processing.mainProduct')}: {b.mainItemName} · {t(`processing.status.${b.status}`)}</Text>
          {open?.id === b.id && canWrite && (
            <View style={{ marginTop: 8 }}>
              {canStock && items.length > 0 && <>
                <Text style={s.section}>{t('processing.moveIn')}</Text>
                {chips(items, i => i.id, i => i.name, input.itemId, pickInputItem)}
                {lots.length > 0 && chips(lots, l => l.lotNo, l => `${l.lotNo} · ${l.quantity}`, input.lotNo, l => setInput({ ...input, lotNo: l.lotNo }))}
                {sources.length > 0 && <>
                  <Text style={s.label}>{t('stock.transfer.from')}</Text>
                  {chips(sources, a => a.id, a => a.name, input.fromId, a => setInput({ ...input, fromId: a.id }))}
                </>}
                <TextInput accessibilityLabel={t('processing.kgIn')} placeholder={t('processing.quantity')} style={s.input} keyboardType="decimal-pad" value={input.quantity}
                           onChangeText={v => setInput({ ...input, quantity: v })} />
                <Button outline title={t('stock.transfer.save')} disabled={busy} onPress={() => {
                  if (input.itemId == null || !isQuantity(input.quantity)) { Alert.alert(t('processing.moveIn'), t('stock.transfer.errors.form')); return; }
                  void run(t('processing.moveIn'), async () => {
                    const r = await moveIn(tenant.id, today, input.itemId!, input.quantity, input.lotNo, input.fromId, b.costObjectId, newEntryId());
                    setInput({ ...input, quantity: '' });
                    return t('stock.transfer.saved', { number: r.number, amount: money(r.costMoved) });
                  });
                }} />
              </>}

              <Text style={s.section}>{t('processing.steps')} · {today}</Text>
              {chips([...STEPS], k => k, k => t(`processing.step.${k}`), step.kind, k => setStep({ ...step, kind: k }))}
              <View style={s.grid}>
                {(['kgIn', 'kgOut'] as const).map(k => (
                  <TextInput key={k} accessibilityLabel={t(`processing.${k}`)} placeholder={t(`processing.${k}`)} style={[s.input, s.half]} keyboardType="decimal-pad"
                             value={step[k]} onChangeText={v => setStep({ ...step, [k]: v })} />))}
                {step.kind === 'DRYING' && (['before', 'after'] as const).map(k => (
                  <TextInput key={k} accessibilityLabel={t(k === 'before' ? 'processing.moistureBefore' : 'processing.moistureAfter')}
                             placeholder={t(k === 'before' ? 'processing.moistureBefore' : 'processing.moistureAfter')} style={[s.input, s.half]} keyboardType="decimal-pad"
                             value={step[k]} onChangeText={v => setStep({ ...step, [k]: v })} />))}
              </View>
              <Button title={busy ? t('mobile.saving') : t('processing.recordStep')} disabled={busy} onPress={() => {
                if (![step.kgIn, step.kgOut, step.before, step.after].every(isOptional)) { Alert.alert(t('processing.steps'), t('processing.errors.step')); return; }
                void run(t('processing.steps'), async () => { await addStep(tenant.id, b.id, today, step.kind, step.kgIn, step.kgOut, step.before, step.after);
                  setStep({ ...step, kgIn: '', kgOut: '', before: '', after: '' }); return t('processing.stepSaved'); });
              }} />

              <Text style={s.section}>{t('processing.outputs')}</Text>
              {chips([...ROLES], r => r, r => t(`processing.role.${r}`), out.role, r => setOut({ ...out, role: r, itemId: r === 'MAIN' ? b.mainItemId : out.itemId }))}
              {out.role === 'BYPRODUCT' && chips(items, i => i.id, i => i.name, out.itemId, i => setOut({ ...out, itemId: i.id }))}
              {out.role === 'WASTE' && <TextInput accessibilityLabel={t('processing.wasteWhat')} placeholder={t('processing.wasteExample')} style={s.input}
                                                  value={out.description} onChangeText={v => setOut({ ...out, description: v })} />}
              <TextInput accessibilityLabel={t('processing.quantity')} placeholder={out.role === 'WASTE' ? t('processing.kg') : t('processing.quantity')} style={s.input}
                         keyboardType="decimal-pad" value={out.quantity} onChangeText={v => setOut({ ...out, quantity: v })} />
              <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: out.finish }} onPress={() => setOut({ ...out, finish: !out.finish })}>
                <Text style={s.note}>{out.finish ? '☑' : '☐'} {t('processing.lastOutput')}</Text></Pressable>
              <Button outline title={t('processing.recordOutput')} disabled={busy || (out.role !== 'WASTE' && !canStock)} onPress={() => {
                if (!isQuantity(out.quantity) || (out.role === 'WASTE' ? !out.description.trim() : out.itemId == null)) { Alert.alert(t('processing.outputs'), t('processing.errors.output')); return; }
                void run(t('processing.outputs'), async () => {
                  const r = await recordOutput(tenant.id, b.id, today, out.role, out.itemId, out.description, out.quantity, out.finish, newEntryId());
                  setOut({ ...out, quantity: '', description: '', finish: false });
                  return r.lotNo ? `${t('processing.outputSaved')} ${t('processing.lot')}: ${r.lotNo}` : t('processing.outputSaved');
                });
              }} />
            </View>
          )}
        </Pressable>
      ))}
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
