import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { tr, type Language } from '../i18n/translations';
import { errorText } from '../screens/AuthScreens';
import { newEntryId } from '../storage/entries';
import type { TenantSummary } from '../auth/session';
import {
  ACTIVITY_TYPES, OBSERVATION_KINDS, SEVERITIES, addObservation, isQuantity, loadActiveCycles, loadInputs, loadReport, recordActivity, recordHarvest,
  stageLine, type Cycle, type CycleReport, type StockItem,
} from './cropView';

const money = (n: number) => `₹${Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
const today = () => new Date().toISOString().slice(0, 10);
type Form = '' | 'activity' | 'harvest' | 'observation';

/** The Crops tab: cycles in the field, their figures, and recording field work, harvests and observations (online). */
export default function CropScreen({ tenant, language }: { tenant: TenantSummary; language: Language }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const canRead = tenant.permissions.includes('CROP_READ') && tenant.enabledModules.includes('CROP');
  const canRecord = tenant.permissions.includes('CROP_ACTIVITY_WRITE');
  const canUseStock = tenant.permissions.includes('INVENTORY_WRITE');
  const [cycles, setCycles] = useState<Cycle[] | null>(null);
  const [offline, setOffline] = useState(false);
  const [selected, setSelected] = useState<Cycle | null>(null);
  const [report, setReport] = useState<CycleReport | null>(null);
  const [form, setForm] = useState<Form>('');

  const load = useCallback(async () => {
    if (!canRead) return;
    try { setCycles(await loadActiveCycles(tenant.id)); setOffline(false); } catch { setOffline(true); setCycles([]); }
  }, [tenant.id, canRead]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    setReport(null);
    if (selected) loadReport(tenant.id, selected.id).then(setReport).catch(() => setReport(null));
  }, [tenant.id, selected]);

  if (!canRead) return <Text style={s.note}>{t('mobile.cropsNotEnabled')}</Text>;
  if (!cycles) return <ActivityIndicator size="large" color="#28734A" style={{ marginTop: 40 }} />;
  return (
    <View>
      <Text style={s.title}>{t('crop.title')}</Text>
      <Text style={s.subtitle}>{t('crop.sub')}</Text>
      {offline && <Text style={s.note}>{t('mobile.cropsOffline')}</Text>}
      {!offline && cycles.length === 0 && <Text style={s.note}>{t('crop.noCycles')}</Text>}
      {cycles.map(c => (
        <Pressable key={c.id} style={[s.card, selected?.id === c.id && s.selected]} onPress={() => setSelected(selected?.id === c.id ? null : c)}>
          <Text style={s.cardTitle}>{c.name}</Text>
          <Text style={s.cardSub}>{c.cropName} · {c.farmName} / {c.parcelName}</Text>
          <Text style={s.stage}>{stageLine(language, c)}</Text>
          {selected?.id === c.id && (
            <View style={s.detail}>
              {!report ? <ActivityIndicator color="#28734A" /> : <>
                <Row label={t('fin.revenue')} value={money(report.revenueTotal)} />
                <Row label={t('mobile.cropCost')} value={money(report.totalCost)} />
                <Row label={t('crop.profit')} value={money(report.profit)} />
                {report.costPerAcre != null && <Row label={t('crop.costPerAcre')} value={money(report.costPerAcre)} />}
                {report.harvested[0] && <Row label={t('crop.yield')} value={`${Number(report.harvested[0].quantity).toLocaleString('en-IN')} ${t(`stock.unit.${report.harvested[0].baseUnit}`)}`} />}
              </>}
              {canRecord && (
                <View style={s.actions}>
                  <Button title={t('crop.logActivity')} onPress={() => setForm('activity')} />
                  <Button title={t('crop.recordHarvest')} onPress={() => setForm('harvest')} />
                  <Button title={t('crop.addObservation')} onPress={() => setForm('observation')} />
                </View>
              )}
            </View>
          )}
        </Pressable>
      ))}
      <Button title={t('mobile.refresh')} onPress={() => void load()} outline />
      {selected && form !== '' && (
        <RecordModal form={form} cycle={selected} tenant={tenant} language={language} canUseStock={canUseStock}
                     onClose={() => setForm('')} onSaved={() => { setForm(''); void load(); loadReport(tenant.id, selected.id).then(setReport).catch(() => undefined); }} />
      )}
    </View>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return <View style={s.row}><Text style={s.rowLabel}>{label}</Text><Text style={s.rowValue}>{value}</Text></View>;
}

function Button({ title, onPress, outline }: { title: string; onPress: () => void; outline?: boolean }) {
  return <Pressable accessibilityRole="button" onPress={onPress} style={[s.button, outline && s.outline]}><Text style={[s.buttonText, outline && s.outlineText]}>{title}</Text></Pressable>;
}

function Choice<T extends string>({ options, value, onChange, label }: { options: readonly T[]; value: T; onChange: (v: T) => void; label: (v: T) => string }) {
  return (
    <View style={s.choices}>
      {options.map(o => (
        <Pressable key={o} onPress={() => onChange(o)} style={[s.chip, value === o && s.chipOn]}><Text style={[s.chipText, value === o && s.chipTextOn]}>{label(o)}</Text></Pressable>
      ))}
    </View>
  );
}

function RecordModal({ form, cycle, tenant, language, canUseStock, onClose, onSaved }: { form: Form; cycle: Cycle; tenant: TenantSummary; language: Language;
  canUseStock: boolean; onClose: () => void; onSaved: () => void }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const [key] = useState(newEntryId);
  const [date, setDate] = useState(today());
  const [type, setType] = useState<(typeof ACTIVITY_TYPES)[number]>('WEEDING');
  const [kind, setKind] = useState<(typeof OBSERVATION_KINDS)[number]>('PEST');
  const [severity, setSeverity] = useState<(typeof SEVERITIES)[number]>('MEDIUM');
  const [notes, setNotes] = useState('');
  const [quantity, setQuantity] = useState('');
  const [unit, setUnit] = useState('quintal');
  const [items, setItems] = useState<StockItem[]>([]);
  const [itemId, setItemId] = useState<number | null>(null);
  const [inputQty, setInputQty] = useState('');
  const [saving, setSaving] = useState(false);
  useEffect(() => { if (form === 'activity' && canUseStock) loadInputs(tenant.id).then(setItems).catch(() => setItems([])); }, [form, canUseStock, tenant.id]);

  const save = async () => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) { Alert.alert(t('mobile.appName'), t('mobile.dateFormat')); return; }
    if (form === 'harvest' && !isQuantity(quantity)) { Alert.alert(t('mobile.appName'), t('stock.errors.quantity')); return; }
    if (form === 'activity' && itemId != null && !isQuantity(inputQty)) { Alert.alert(t('mobile.appName'), t('stock.errors.quantity')); return; }
    setSaving(true);
    try {
      if (form === 'activity') {
        await recordActivity(tenant.id, { cycleId: cycle.id, activityType: type, activityDate: date, notes,
          inputs: itemId != null ? [{ itemId, quantity: inputQty.trim() }] : [] }, key);
      } else if (form === 'harvest') {
        await recordHarvest(tenant.id, { cycleId: cycle.id, harvestDate: date, quantity: quantity.trim(), unit }, key);
      } else {
        await addObservation(tenant.id, cycle.id, { observedOn: date, kind, severity, notes });
      }
      Alert.alert(t('mobile.appName'), t('mobile.cropSaved'));
      onSaved();
    } catch (e) {
      Alert.alert(t('mobile.appName'), errorText(language, e));
    } finally {
      setSaving(false);
    }
  };

  const title = form === 'activity' ? t('crop.logActivity') : form === 'harvest' ? t('crop.recordHarvest') : t('crop.addObservation');
  return (
    <Modal transparent animationType="slide" onRequestClose={onClose}>
      <View style={s.backdrop}>
        <ScrollView style={s.sheet} contentContainerStyle={{ gap: 10 }}>
          <Text style={s.title}>{title}</Text>
          <Text style={s.cardSub}>{cycle.name}</Text>
          <Text style={s.label}>{t('mobile.date')}</Text>
          <TextInput style={s.input} value={date} onChangeText={setDate} />
          {form === 'activity' && <>
            <Text style={s.label}>{t('crop.activityType')}</Text>
            <Choice options={ACTIVITY_TYPES} value={type} onChange={setType} label={v => t(`crop.activity.${v}`)} />
            {items.length > 0 && <>
              <Text style={s.label}>{t('crop.inputsFromStock')}</Text>
              <Choice options={items.map(i => String(i.id))} value={itemId == null ? '' : String(itemId)}
                      onChange={v => setItemId(itemId === Number(v) ? null : Number(v))} label={v => items.find(i => String(i.id) === v)?.name ?? v} />
              {itemId != null && <TextInput style={s.input} keyboardType="decimal-pad" value={inputQty} onChangeText={setInputQty}
                placeholder={`${t('stock.quantity')} (${t(`stock.unit.${items.find(i => i.id === itemId)?.baseUnit ?? 'kg'}`)})`} />}
            </>}
          </>}
          {form === 'harvest' && <>
            <Text style={s.label}>{t('crop.quantity')}</Text>
            <TextInput style={s.input} keyboardType="decimal-pad" value={quantity} onChangeText={setQuantity} />
            <Choice options={['quintal', 'kg', 'tonne'] as const} value={unit as 'quintal'} onChange={setUnit} label={v => t(`stock.unit.${v}`)} />
          </>}
          {form === 'observation' && <>
            <Text style={s.label}>{t('crop.observationKind')}</Text>
            <Choice options={OBSERVATION_KINDS} value={kind} onChange={setKind} label={v => t(`crop.observation.${v}`)} />
            <Text style={s.label}>{t('crop.severityLabel')}</Text>
            <Choice options={SEVERITIES} value={severity} onChange={setSeverity} label={v => t(`crop.severity.${v}`)} />
          </>}
          {form !== 'harvest' && <>
            <Text style={s.label}>{t('crop.notes')}</Text>
            <TextInput style={[s.input, { minHeight: 60 }]} multiline value={notes} onChangeText={setNotes} maxLength={500} />
          </>}
          <View style={s.actions}>
            <Button title={t('mobile.cancel')} onPress={onClose} outline />
            <Button title={saving ? '…' : t('mobile.save')} onPress={() => { if (!saving) void save(); }} />
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}

const s = StyleSheet.create({
  title: { fontSize: 22, fontWeight: '800', color: '#173D2B', marginBottom: 4 },
  subtitle: { fontSize: 13, color: '#66816A', marginBottom: 12 },
  note: { fontSize: 13, color: '#66816A', marginVertical: 12 },
  card: { backgroundColor: '#FFFFFF', borderRadius: 14, padding: 14, marginBottom: 10, borderWidth: 1, borderColor: '#E1E8DF' },
  selected: { borderColor: '#80AD7B' },
  cardTitle: { fontSize: 16, fontWeight: '800', color: '#182A21' },
  cardSub: { fontSize: 12, color: '#7C8C7F', marginTop: 2 },
  stage: { fontSize: 12, color: '#386A3F', fontWeight: '700', marginTop: 6 },
  detail: { marginTop: 10, gap: 6 },
  row: { flexDirection: 'row', justifyContent: 'space-between' },
  rowLabel: { fontSize: 13, color: '#526456' },
  rowValue: { fontSize: 13, fontWeight: '800', color: '#182A21' },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  button: { backgroundColor: '#28633E', borderRadius: 10, paddingVertical: 10, paddingHorizontal: 12, marginTop: 6 },
  buttonText: { color: '#FFFFFF', fontWeight: '700', fontSize: 13 },
  outline: { backgroundColor: 'transparent', borderWidth: 1, borderColor: '#28633E' },
  outlineText: { color: '#28633E' },
  backdrop: { flex: 1, backgroundColor: '#0006', justifyContent: 'flex-end' },
  sheet: { backgroundColor: '#F5F7F0', borderTopLeftRadius: 20, borderTopRightRadius: 20, padding: 20, maxHeight: '85%' },
  label: { fontSize: 12, fontWeight: '700', color: '#526456' },
  input: { backgroundColor: '#FFFFFF', borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 10, padding: 10, fontSize: 14 },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip: { borderWidth: 1, borderColor: '#DCE5DA', borderRadius: 16, paddingVertical: 6, paddingHorizontal: 10, backgroundColor: '#FFFFFF' },
  chipOn: { backgroundColor: '#28633E', borderColor: '#28633E' },
  chipText: { fontSize: 12, color: '#31583E' },
  chipTextOn: { color: '#FFFFFF', fontWeight: '700' },
});
