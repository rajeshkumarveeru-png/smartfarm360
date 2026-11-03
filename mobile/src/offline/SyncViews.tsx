import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { tr, type Language } from '../i18n/translations';
import { ApiError } from '../services/api';
import { CLOCK_WARNING_MS, STALE_CACHE_MS, statusLine } from './engine';
import { applyEdits, dayFigures, editableFields, fieldKey, kindKey, sections, whenText, type EditableField, type Figure } from './figures';
import { discardRecord, editAndResend, getState, keepMineAndResend, records, serverCopy, subscribe, syncNow, type HubState } from './hub';
import type { OutboxRecord } from './types';
import { APP_VERSION } from './version';

/** The status line and the Sync screen (mobile offline design, N7, N8, N13). */

export function useHubState(): HubState {
  const [state, setState] = useState(getState);
  useEffect(() => subscribe(setState), []);
  return state;
}

/** Shown on every screen: offline and waiting, sending, needs attention, or all sent (N13). */
export function SyncStatusLine({ language, onPress }: { language: Language; onPress: () => void }) {
  const s = useHubState();
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const line = statusLine(s);
  const parts = [t(line.key, line.params)];
  if (!s.sending) parts.push(s.lastSync ? t('mobile.syncLast', { time: whenText(s.lastSync) }) : t('mobile.syncNever'));
  const warn = s.attention > 0 || s.upgradeRequired || s.sessionExpired;
  const old = s.cachedAsOf != null && Date.now() - Date.parse(s.cachedAsOf) > STALE_CACHE_MS;
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={[st.line, warn && st.lineWarn, !s.online && st.lineOffline]}>
      <Text style={[st.lineText, warn && st.lineTextWarn]}>{parts.join(' · ')}</Text>
      {!s.online && s.cachedAsOf && <Text style={st.lineSub}>{t('mobile.syncAsOf', { time: whenText(s.cachedAsOf) })}{old ? ` · ${t('mobile.syncAsOfOld')}` : ''}</Text>}
    </Pressable>
  );
}

/** The badge on a record: waiting, sent, needs attention (N13). */
export function RecordBadge({ language, record }: { language: Language; record: Pick<OutboxRecord, 'state'> }) {
  const key = { waiting: 'mobile.syncBadgeWaiting', sent: 'mobile.syncBadgeSent', attention: 'mobile.syncBadgeAttention', locked: 'mobile.syncBadgeLocked',
    discarded: 'mobile.syncBadgeDiscarded' }[record.state];
  return <Text style={[st.badge, record.state === 'attention' && st.badgeWarn, record.state === 'sent' && st.badgeOk]}>{tr(language, key)}</Text>;
}

/** The server's reason, in the user's language when the catalog has it (N8). */
export function reasonText(language: Language, r: OutboxRecord): string {
  if (!r.error) return '';
  const text = tr(language, `errors.${r.error.code}`);
  if (text !== `errors.${r.error.code}`) return text;
  return r.error.message || tr(language, 'errors.generic');
}

/** More → Sync: the outbox, Sync now, the last sync, the clock check and the app version (N13). */
export function SyncScreen({ language }: { language: Language }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const s = useHubState();
  const [list, setList] = useState<OutboxRecord[]>([]);
  const load = useCallback(async () => setList(await records()), []);
  useEffect(() => { void load(); }, [load, s.waiting, s.attention, s.sent, s.discarded, s.locked]);
  const groups = sections(list);
  const skewMinutes = s.clockSkewMs != null && Math.abs(s.clockSkewMs) > CLOCK_WARNING_MS ? Math.round(Math.abs(s.clockSkewMs) / 60_000) : null;
  const line = statusLine(s);
  return (
    <View>
      <Text style={st.title}>{t('mobile.syncTitle')}</Text>
      <Text style={st.subtitle}>{t('mobile.syncHelp')}</Text>
      <View style={st.card}>
        <Text style={st.cardTitle}>{t(line.key, line.params)}</Text>
        <Text style={st.cardSub}>{s.lastSync ? t('mobile.syncLast', { time: whenText(s.lastSync) }) : t('mobile.syncNever')}</Text>
        {skewMinutes != null && <Text style={st.warn}>{t('mobile.syncClockOff', { minutes: String(skewMinutes) })}</Text>}
        <Text style={st.cardSub}>{t('mobile.syncAppVersion', { version: APP_VERSION })}</Text>
        <Button label={t('mobile.syncNow')} onPress={() => void syncNow().then(load)} disabled={!!s.sending} />
      </View>
      <Section title={t('mobile.syncSectionAttention')} empty={t('mobile.syncEmpty')}>
        {groups.attention.map(r => <AttentionRow key={r.id} record={r} language={language} onDone={load} />)}
      </Section>
      <Section title={t('mobile.syncSectionWaiting')} empty={t('mobile.syncEmpty')}>
        {groups.waiting.map(r => <Row key={r.id} record={r} language={language} />)}
      </Section>
      <Section title={t('mobile.syncSectionSent')} empty={t('mobile.syncEmpty')}>
        {groups.sent.map(r => <Row key={r.id} record={r} language={language} />)}
      </Section>
      <Section title={t('mobile.syncSectionDiscarded')} empty={t('mobile.syncEmpty')}>
        {groups.discarded.map(r => <Row key={r.id} record={r} language={language} />)}
      </Section>
    </View>
  );
}

function Section({ title, empty, children }: { title: string; empty: string; children: React.ReactNode[] }) {
  return <View><Text style={st.section}>{title}</Text>{children.length === 0 ? <Text style={st.note}>{empty}</Text> : children}</View>;
}

function Row({ record, language, children }: { record: OutboxRecord; language: Language; children?: React.ReactNode }) {
  return (
    <View style={st.card}>
      <View style={st.rowTop}><Text style={st.cardTitle}>{tr(language, kindKey(record.kind))}</Text><RecordBadge language={language} record={record} /></View>
      <Text style={st.cardSub}>{record.summary}</Text>
      {children}
    </View>
  );
}

function Figures({ title, figures, language }: { title: string; figures: Figure[]; language: Language }) {
  return <View style={st.half}><Text style={st.figTitle}>{title}</Text>{figures.map((f, i) => <Text key={i} style={st.cardSub}>{tr(language, f.label)}: {f.value}</Text>)}</View>;
}

/** A refused record: the reason, then compare (day records), edit and resend, or discard (N7, N8). */
function AttentionRow({ record, language, onDone }: { record: OutboxRecord; language: Language; onDone: () => Promise<void> }) {
  const t = (path: string, params?: Record<string, string>) => tr(language, path, params);
  const [server, setServer] = useState<unknown>(undefined);
  const [fields, setFields] = useState<EditableField[] | null>(null);
  const [busy, setBusy] = useState(false);
  const locked = record.state === 'locked';
  const stale = record.error?.code === 'STALE_RECORD' && !!record.day;

  const act = async (work: () => Promise<void>) => {
    setBusy(true);
    try { await work(); await onDone(); } catch (e) {
      Alert.alert(t('mobile.syncTitle'), e instanceof ApiError ? reasonText(language, { ...record, error: { status: e.status, code: e.code, message: e.message } }) : t('errors.generic'));
    } finally { setBusy(false); }
  };
  const confirmDiscard = () => Alert.alert(t('mobile.syncTitle'), t('mobile.syncDiscardConfirm'), [
    { text: t('mobile.cancel'), style: 'cancel' },
    { text: t('mobile.syncDiscard'), style: 'destructive', onPress: () => void act(() => discardRecord(record)) },
  ]);

  return (
    <Row record={record} language={language}>
      <Text style={st.warn}>{locked ? t('mobile.syncLockedHelp') : reasonText(language, record)}</Text>
      {stale && server !== undefined && (
        <View style={st.compare}>
          <Figures title={t('mobile.syncYours')} figures={dayFigures(record.kind, record.day, record.body)} language={language} />
          <Figures title={t('mobile.syncServer')} figures={dayFigures(record.kind, record.day, server)} language={language} />
        </View>
      )}
      {fields && (
        <View>
          {fields.map((f, i) => (
            <View key={f.path.join('.')}>
              <Text style={st.label}>{tr(language, fieldKey(f.key)) === fieldKey(f.key) ? t('mobile.syncFieldValue') : tr(language, fieldKey(f.key))}</Text>
              <TextInput value={f.value} style={st.input} onChangeText={v => setFields(list => list!.map((x, j) => (j === i ? { ...x, value: v } : x)))} />
            </View>
          ))}
          <View style={st.buttons}>
            <Button label={t('mobile.cancel')} outline onPress={() => setFields(null)} />
            <Button label={t('mobile.syncResend')} disabled={busy} onPress={() => void act(() => editAndResend(record, applyEdits(record.body, fields)))} />
          </View>
        </View>
      )}
      {!fields && (
        <View style={st.buttons}>
          {stale && server === undefined && <Button label={t('mobile.syncCompare')} outline disabled={busy}
            onPress={() => void act(async () => setServer((await serverCopy(record)).body))} />}
          {stale && server !== undefined && <Button label={t('mobile.syncKeepMine')} disabled={busy} onPress={() => void act(() => keepMineAndResend(record))} />}
          {stale && server !== undefined && <Button label={t('mobile.syncUseServer')} outline disabled={busy} onPress={() => void act(() => discardRecord(record))} />}
          {!locked && <Button label={t('mobile.syncEdit')} outline disabled={busy} onPress={() => setFields(editableFields(record.body))} />}
          <Button label={t('mobile.syncDiscard')} outline disabled={busy} onPress={confirmDiscard} />
        </View>
      )}
    </Row>
  );
}

function Button({ label, onPress, outline, disabled }: { label: string; onPress: () => void; outline?: boolean; disabled?: boolean }) {
  return (
    <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[st.button, outline && st.buttonOutline, disabled && { opacity: 0.5 }]}>
      <Text style={[st.buttonText, outline && st.buttonTextOutline]}>{label}</Text>
    </Pressable>
  );
}

const st = StyleSheet.create({
  line: { backgroundColor: '#E4F1E5', borderRadius: 12, paddingHorizontal: 12, paddingVertical: 8, marginBottom: 12 },
  lineWarn: { backgroundColor: '#FCEDE7' }, lineOffline: { backgroundColor: '#EEF0EA' },
  lineText: { fontSize: 12, fontWeight: '700', color: '#28734A' }, lineTextWarn: { color: '#B5442F' }, lineSub: { fontSize: 11, color: '#6B7D6C', marginTop: 3 },
  title: { fontSize: 25, fontWeight: '800', color: '#21372A', marginTop: 12 }, subtitle: { fontSize: 13, color: '#778277', lineHeight: 20, marginTop: 5, marginBottom: 14 },
  section: { fontSize: 16, fontWeight: '800', color: '#22372A', marginTop: 14, marginBottom: 8 },
  note: { fontSize: 12, color: '#7A857B', marginBottom: 6 },
  card: { backgroundColor: '#FFFFFF', borderRadius: 15, padding: 14, marginBottom: 9, borderWidth: 1, borderColor: '#E8ECE4' },
  rowTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8 },
  cardTitle: { fontSize: 14, fontWeight: '800', color: '#27392D', flexShrink: 1 }, cardSub: { fontSize: 12, color: '#6B7D6C', marginTop: 4 },
  warn: { fontSize: 12, color: '#B5442F', marginTop: 6 },
  badge: { fontSize: 10, fontWeight: '700', color: '#7A857B' }, badgeWarn: { color: '#B5442F' }, badgeOk: { color: '#247447' },
  compare: { flexDirection: 'row', gap: 10, marginTop: 8 }, half: { flex: 1, backgroundColor: '#F5F7F0', borderRadius: 10, padding: 8 },
  figTitle: { fontSize: 12, fontWeight: '800', color: '#3C5943' },
  label: { fontSize: 12, fontWeight: '800', color: '#506151', marginTop: 8, marginBottom: 4 },
  input: { backgroundColor: '#FFFFFF', borderRadius: 10, borderWidth: 1, borderColor: '#DFE6DC', paddingHorizontal: 10, paddingVertical: 8, fontSize: 14, color: '#21372A' },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 },
  button: { backgroundColor: '#28734A', borderRadius: 11, paddingHorizontal: 12, paddingVertical: 9 }, buttonOutline: { backgroundColor: 'transparent', borderWidth: 1, borderColor: '#D3DDD1' },
  buttonText: { color: '#FFFFFF', fontSize: 12, fontWeight: '800' }, buttonTextOutline: { color: '#28734A' },
});
