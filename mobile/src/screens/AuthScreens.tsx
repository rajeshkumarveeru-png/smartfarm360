import React, { useState } from 'react';
import { ActivityIndicator, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { ApiError, isApiConfigured } from '../services/api';
import type { TenantSummary } from '../auth/session';
import { Language, tr } from '../i18n/translations';

export function errorText(language: Language, error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'NETWORK') return tr(language, 'errors.network');
    if (error.code === 'API_NOT_CONFIGURED') return tr(language, 'mobileAuth.apiMissing');
    const text = tr(language, `errors.${error.code}`);
    if (text !== `errors.${error.code}`) return text;
  }
  return tr(language, 'errors.generic');
}

function LanguageButton({ language, onChange }: { language: Language; onChange: (l: Language) => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={tr(language, 'common.language')} onPress={() => onChange(language === 'ta' ? 'en' : 'ta')} style={styles.lang}>
      <Text style={styles.langText}>{language === 'ta' ? 'EN' : 'தமிழ்'}</Text>
    </Pressable>
  );
}

export function LoginScreen({ language, onLanguage, onSubmit }: {
  language: Language; onLanguage: (l: Language) => void; onSubmit: (email: string, password: string) => Promise<void>;
}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit() {
    if (!email.trim() || !password) { setError(tr(language, 'common.required')); return; }
    setBusy(true); setError('');
    try {
      await onSubmit(email.trim(), password);
    } catch (e) {
      setError(errorText(language, e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={styles.safe}>
      <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
        <View style={styles.header}><Text style={styles.brand}>{tr(language, 'common.appName')}</Text><LanguageButton language={language} onChange={onLanguage} /></View>
        <Text style={styles.title}>{tr(language, 'auth.signInTitle')}</Text>
        {!isApiConfigured && <Text style={styles.error}>{tr(language, 'mobileAuth.apiMissing')}</Text>}
        <Text style={styles.label}>{tr(language, 'auth.email')}</Text>
        <TextInput accessibilityLabel={tr(language, 'auth.email')} value={email} onChangeText={setEmail} autoCapitalize="none" autoComplete="email" keyboardType="email-address" style={styles.input} />
        <Text style={styles.label}>{tr(language, 'auth.password')}</Text>
        <TextInput accessibilityLabel={tr(language, 'auth.password')} value={password} onChangeText={setPassword} secureTextEntry autoComplete="password" style={styles.input} />
        {error ? <Text accessibilityRole="alert" style={styles.error}>{error}</Text> : null}
        <Pressable accessibilityRole="button" disabled={busy || !isApiConfigured} onPress={() => void submit()} style={[styles.button, (busy || !isApiConfigured) && styles.disabled]}>
          {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>{tr(language, 'auth.signIn')}</Text>}
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

export function TenantPicker({ language, tenants, onPick, onSignOut }: {
  language: Language; tenants: TenantSummary[]; onPick: (t: TenantSummary) => void; onSignOut: () => void;
}) {
  const usable = tenants.filter(t => t.active);
  return (
    <SafeAreaView style={styles.safe}>
      <ScrollView contentContainerStyle={styles.body}>
        <Text style={styles.title}>{tr(language, 'mobileAuth.chooseTenant')}</Text>
        {usable.length === 0 && <Text style={styles.note}>{tr(language, 'mobileAuth.noTenants')}</Text>}
        {usable.map(t => (
          <Pressable key={t.id} accessibilityRole="button" onPress={() => onPick(t)} style={styles.tenant}>
            <Text style={styles.tenantName}>{t.name}</Text>
            <Text style={styles.note}>{t.roles.map(r => tr(language, `roles.${r}`)).join(', ')}</Text>
          </Pressable>
        ))}
        <Pressable accessibilityRole="button" onPress={onSignOut} style={[styles.button, styles.outline]}>
          <Text style={[styles.buttonText, styles.outlineText]}>{tr(language, 'auth.signOut')}</Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: '#F5F7F0' },
  body: { padding: 22, paddingTop: 48, gap: 10 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 },
  brand: { fontSize: 18, fontWeight: '800', color: '#173F2C' },
  lang: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 20, backgroundColor: '#E4F1E5' },
  langText: { color: '#28734A', fontWeight: '700' },
  title: { fontSize: 24, fontWeight: '800', color: '#21372A', marginBottom: 12 },
  label: { fontSize: 13, fontWeight: '700', color: '#506151', marginTop: 6 },
  input: { backgroundColor: '#fff', borderRadius: 12, borderWidth: 1, borderColor: '#DFE6DC', paddingHorizontal: 14, paddingVertical: 13, fontSize: 16, color: '#21372A' },
  button: { backgroundColor: '#28734A', borderRadius: 13, minHeight: 50, alignItems: 'center', justifyContent: 'center', marginTop: 14 },
  buttonText: { color: '#fff', fontSize: 15, fontWeight: '800' },
  disabled: { opacity: 0.6 },
  outline: { backgroundColor: 'transparent', borderWidth: 1, borderColor: '#D3DDD1' },
  outlineText: { color: '#28734A' },
  error: { color: '#B23B30', fontSize: 13, marginTop: 6 },
  note: { color: '#6B7D6C', fontSize: 13, lineHeight: 19 },
  tenant: { backgroundColor: '#fff', borderRadius: 14, borderWidth: 1, borderColor: '#E8ECE4', padding: 16, minHeight: 56 },
  tenantName: { fontSize: 16, fontWeight: '800', color: '#24382A', marginBottom: 4 },
});
