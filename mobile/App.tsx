import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator, Alert, KeyboardAvoidingView, Modal, Platform, Pressable, SafeAreaView,
  ScrollView, StatusBar, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { StatusBar as ExpoStatusBar } from 'expo-status-bar';
import { FarmEntry, EntryKind, claimLegacyEntries, loadEntries, newEntryId, removeEntries, saveEntry, scopeKey } from './src/storage/entries';
import { FinanceContext, isOffline, loadFinance, postEntry, syncPending } from './src/finance/quickEntry';
import { loadStock, quantityLabel, type StockView } from './src/stock/stockView';
import CropScreen from './src/crop/CropScreen';
import { errorText } from './src/screens/AuthScreens';
import { API_BASE_URL, apiRequest, isApiConfigured, onSessionEnded, refreshSession, signIn, signOut, type User } from './src/services/api';
import { Language, TranslationKey, tr, translations } from './src/i18n/translations';
import { loadTenants, rememberSelectedTenant, restoreSelectedTenant, type TenantSummary } from './src/auth/session';
import { LoginScreen, TenantPicker } from './src/screens/AuthScreens';

type Tab = 'home' | 'records' | 'crops' | 'livestock' | 'more' | 'stock';
const expenseCategories: TranslationKey[] = ['seed','fertilizer','labour','feed','veterinary','irrigation','transport','other'];
const incomeCategories: TranslationKey[] = ['milk','cropSale','eggs','livestockSale','other'];
const money = (n: number) => `₹${n.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
const today = () => new Date().toISOString().slice(0, 10);

type Session = { user: User; tenants: TenantSummary[]; tenant: TenantSummary | null };

/** Sign-in and tenant selection gate in front of the farm screens. */
export default function App() {
  const [language, setLanguage] = useState<Language>('ta');
  const [status, setStatus] = useState<'loading' | 'signedOut' | 'signedIn'>('loading');
  const [session, setSession] = useState<Session | null>(null);

  const begin = useCallback(async (user: User) => {
    setLanguage(user.preferredLanguage);
    const tenants = await loadTenants();
    setSession({ user, tenants, tenant: await restoreSelectedTenant(user.id, tenants) });
    setStatus('signedIn');
  }, []);

  useEffect(() => {
    onSessionEnded(() => { setSession(null); setStatus('signedOut'); });
    refreshSession()
      .then(restored => (restored ? begin(restored.user) : setStatus('signedOut')))
      .catch(() => setStatus('signedOut'));
  }, [begin]);

  const changeLanguage = (lang: Language) => {
    setLanguage(lang);
    if (session) void apiRequest('/api/v1/auth/me', { method: 'PATCH', body: { preferredLanguage: lang } }).catch(() => undefined);
  };
  const doSignOut = async () => { await signOut(); setSession(null); setStatus('signedOut'); };

  if (status === 'loading') return <SafeAreaView style={styles.safe}><ActivityIndicator size="large" color="#28734A" style={{ marginTop: 80 }} /></SafeAreaView>;
  if (status === 'signedOut' || !session) {
    return <LoginScreen language={language} onLanguage={changeLanguage} onSubmit={async (email, password) => { const s = await signIn(email, password); await begin(s.user); }} />;
  }
  if (!session.tenant) {
    return <TenantPicker language={language} tenants={session.tenants} onSignOut={() => void doSignOut()}
                         onPick={tenant => { void rememberSelectedTenant(session.user.id, tenant.id); setSession({ ...session, tenant }); }} />;
  }
  return <FarmHome key={`${session.user.id}/${session.tenant.id}`} user={session.user} tenant={session.tenant} language={language}
                   onLanguage={changeLanguage} onSignOut={() => void doSignOut()} onSwitchTenant={() => setSession({ ...session, tenant: null })} />;
}

function FarmHome({ user, tenant, language, onLanguage, onSignOut, onSwitchTenant }: {
  user: User; tenant: TenantSummary; language: Language; onLanguage: (l: Language) => void; onSignOut: () => void; onSwitchTenant: () => void;
}) {
  const setLanguage = onLanguage;
  const storageKey = scopeKey(user.id, tenant.id);
  const [tab, setTab] = useState<Tab>('home');
  const [entries, setEntries] = useState<FarmEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState<EntryKind | null>(null);
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState<TranslationKey>('other');
  const [date, setDate] = useState(today());
  const [saving, setSaving] = useState(false);
  const [finance, setFinance] = useState<FinanceContext | null>(null);
  const [stock, setStock] = useState<StockView | null>(null);
  const canSeeStock = tenant.permissions.includes('INVENTORY_READ');
  const canRead = tenant.permissions.includes('FINANCE_READ');
  const canPost = tenant.permissions.includes('FINANCE_WRITE');
  const t = useCallback((key: TranslationKey) => translations[language][key], [language]);

  const refresh = useCallback(async () => {
    setLoading(true);
    const claimed = await claimLegacyEntries(storageKey);
    if (claimed > 0) Alert.alert(t('appName'), tr(language, 'mobile.claimed', { count: String(claimed) }));
    let rows = await loadEntries(storageKey);
    if (canRead) {
      try {
        let context = await loadFinance(tenant.id, today());
        // Entries saved while offline are retried; their ids are idempotency keys, so a retry never doubles them.
        const pending = rows.filter(e => e.syncStatus === 'pending');
        if (canPost && pending.length) {
          const done = await syncPending(tenant.id, pending, context);
          if (done.length) {
            await removeEntries(storageKey, done);
            rows = await loadEntries(storageKey);
            context = await loadFinance(tenant.id, today());
          }
        }
        setFinance(context);
      } catch {
        setFinance(null); // offline or unavailable: show what is on the phone
      }
    }
    if (canSeeStock) {
      try { setStock(await loadStock(tenant.id)); } catch { setStock(null); } // offline: stock needs the server
    }
    setEntries(rows.sort((a,b) => b.createdAt.localeCompare(a.createdAt)));
    setLoading(false);
  // eslint-disable-next-line react-hooks/exhaustive-deps -- load once per user/tenant scope
  }, [storageKey]);
  useEffect(() => { void refresh(); }, [refresh]);

  const totals = useMemo(() => {
    const month = today().slice(0, 7);
    const rows = entries.filter(e => e.date.startsWith(month));
    const income = rows.filter(e => e.kind === 'income').reduce((s,e) => s + e.amount, 0);
    const expenses = rows.filter(e => e.kind === 'expense').reduce((s,e) => s + e.amount, 0);
    if (finance) return { income: Number(finance.summary.monthRevenue), expenses: Number(finance.summary.monthCosts), net: Number(finance.summary.cashTotal) };
    return { income, expenses, net: income - expenses };
  }, [entries, finance]);

  const openEntry = (kind: EntryKind) => {
    setModal(kind); setAmount(''); setDescription(''); setDate(today());
    setCategory(kind === 'expense' ? 'other' : 'cropSale');
  };
  const submitEntry = async () => {
    const value = Number(amount.replace(/,/g, '').trim());
    if (!Number.isFinite(value) || value <= 0 || !description.trim()) {
      Alert.alert(t('appName'), t('required')); return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      Alert.alert(t('appName'), t('dateFormat')); return;
    }
    setSaving(true);
    const entry: FarmEntry = {
      id: newEntryId(),
      kind: modal!, amount: Math.round(value * 100) / 100, description: description.trim(),
      category: category as string, date, createdAt: new Date().toISOString(), syncStatus: 'local-only',
    };
    try {
      if (canPost && finance) {
        try {
          await postEntry(tenant.id, entry, finance);
          setModal(null); await refresh();
          Alert.alert(t('appName'), t('postedToAccounts'));
          return;
        } catch (e) {
          if (!isOffline(e)) { Alert.alert(t('appName'), errorText(language, e)); return; }
          await saveEntry(storageKey, { ...entry, syncStatus: 'pending' }); setModal(null); await refresh();
          Alert.alert(t('appName'), t('savedOffline'));
          return;
        }
      }
      await saveEntry(storageKey, entry); setModal(null); await refresh();
      Alert.alert(t('appName'), t('saved'));
    } catch {
      Alert.alert(t('appName'), t('saveFailed'));
    } finally { setSaving(false); }
  };

  const ScreenTitle = ({ title, subtitle }: {title: string; subtitle?: string}) => (
    <View style={styles.titleWrap}><Text style={styles.screenTitle}>{title}</Text>{subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}</View>
  );
  const Metric = ({ label, value, tone }: {label:string;value:string;tone:'green'|'red'|'dark'}) => (
    <View style={styles.metric}><Text style={styles.metricLabel}>{label}</Text><Text style={[styles.metricValue, tone === 'green' && styles.green, tone === 'red' && styles.red]}>{value}</Text></View>
  );
  const Button = ({ title, onPress, variant='primary' }: {title:string;onPress:()=>void;variant?:'primary'|'secondary'|'outline'}) => (
    <Pressable accessibilityRole="button" onPress={onPress} style={[styles.button, variant === 'secondary' && styles.secondaryButton, variant === 'outline' && styles.outlineButton]}><Text style={[styles.buttonText, variant !== 'primary' && styles.secondaryButtonText]}>{title}</Text></Pressable>
  );
  const renderHome = () => (
    <>
      <View style={styles.hero}><View style={styles.heroTop}><Text style={styles.brand}>{t('appName')}</Text><Pressable onPress={() => setLanguage(language === 'ta' ? 'en' : 'ta')} style={styles.languagePill}><Text style={styles.languageText}>{language === 'ta' ? 'EN' : 'தமிழ்'}</Text></Pressable></View><Text style={styles.heroTitle}>{t('greeting')}</Text><Text style={styles.heroSub}>{t('subtitle')}</Text><View style={styles.leafBadge}><Text style={styles.leafEmoji}>🌱</Text><Text style={styles.leafText}>{t('month')}</Text></View></View>
      <View style={styles.metricsGrid}><Metric label={t('revenue')} value={money(totals.income)} tone="green"/><Metric label={t('expenses')} value={money(totals.expenses)} tone="red"/><Metric label={finance ? tr(language, 'fin.cashAndBank') : t('net')} value={money(totals.net)} tone={totals.net < 0 ? 'red' : 'dark'}/></View>
      <Text style={styles.sectionTitle}>{t('quickActions')}</Text><View style={styles.actionsRow}><Pressable style={styles.actionCard} onPress={() => openEntry('expense')}><Text style={styles.actionIcon}>↘</Text><Text style={styles.actionTitle}>{t('addExpense')}</Text></Pressable><Pressable style={[styles.actionCard, styles.incomeAction]} onPress={() => openEntry('income')}><Text style={[styles.actionIcon, styles.incomeIcon]}>↗</Text><Text style={styles.actionTitle}>{t('addIncome')}</Text></Pressable></View>
      {stock && (stock.lowStock.length > 0 || stock.expiringLots.length > 0) && <Pressable onPress={() => setTab('stock')} style={styles.noteCard}><Text style={styles.noteTitle}>📦 {tr(language, 'stock.title')}</Text>{stock.lowStock.slice(0, 3).map(i => <Text key={i.id} style={styles.noteText}>{tr(language, 'stock.lowStock')}: {i.name} · {quantityLabel(language, i)}</Text>)}{stock.expiringLots.slice(0, 3).map(l => <Text key={l.lotId} style={styles.noteText}>{tr(language, l.expired ? 'stock.expired' : 'stock.expiringSoon')}: {l.itemName} · {l.lotNo} · {l.expiryDate}</Text>)}</Pressable>}
      <View style={styles.sectionHeader}><Text style={styles.sectionTitle}>{t('recent')}</Text><Pressable onPress={() => setTab('records')}><Text style={styles.seeAll}>{t('viewAll')}</Text></Pressable></View>
      {entries.length === 0 ? <View style={styles.emptyCard}><Text style={styles.emptyEmoji}>🧾</Text><Text style={styles.emptyText}>{t('noEntries')}</Text></View> : entries.slice(0,5).map(e => <EntryRow key={e.id} entry={e} language={language} />)}
      <View style={styles.noteCard}><Text style={styles.noteTitle}>ℹ️ {t('localMode')}</Text><Text style={styles.noteText}>{t('safety')}</Text></View>
    </>
  );
  const renderRecords = () => <><ScreenTitle title={t('records')} subtitle={`${entries.length} · ${t('totalEntries')}`}/><View style={styles.actionsRow}><View style={{flex:1}}><Button title={`＋ ${t('expense')}`} onPress={() => openEntry('expense')}/></View><View style={{flex:1}}><Button title={`＋ ${t('income')}`} onPress={() => openEntry('income')} variant="secondary"/></View></View>{entries.length === 0 ? <View style={styles.emptyCard}><Text style={styles.emptyEmoji}>📒</Text><Text style={styles.emptyText}>{t('noEntries')}</Text></View> : entries.map(e => <EntryRow key={e.id} entry={e} language={language}/>)}</>;
  const renderCrops = () => <CropScreen tenant={tenant} language={language} />;
  const renderLivestock = () => <><ScreenTitle title={t('livestockOverview')} subtitle={t('livestockHelp')}/><View style={styles.featureHero}><Text style={styles.featureEmoji}>🐄</Text><Text style={styles.featureHeading}>{t('livestock')}</Text><Text style={styles.featureCopy}>{t('comingSoon')}</Text></View><View style={styles.infoCard}><Text style={styles.infoLabel}>{t('animals')}</Text><Text style={styles.infoValue}>—</Text></View><View style={styles.infoCard}><Text style={styles.infoLabel}>{t('production')}</Text><Text style={styles.infoValue}>—</Text></View><View style={styles.infoCard}><Text style={styles.infoLabel}>{t('feedCost')}</Text><Text style={styles.infoValue}>—</Text></View><Text style={styles.smallNote}>{t('noFarmData')}</Text></>;
  const renderStock = () => <><ScreenTitle title={tr(language, 'stock.title')} subtitle={tr(language, 'stock.subtitle')}/>{!stock ? <View style={styles.emptyCard}><Text style={styles.emptyEmoji}>📦</Text><Text style={styles.emptyText}>{t('stockOffline')}</Text></View> : <><View style={styles.metricsGrid}><Metric label={tr(language, 'stock.totalValue')} value={money(stock.totalValue)} tone="dark"/></View>{stock.items.length === 0 ? <View style={styles.emptyCard}><Text style={styles.emptyText}>{tr(language, 'stock.noStock')}</Text></View> : stock.items.map(i => <View key={i.id} style={styles.infoCard}><Text style={styles.infoLabel}>{i.name}{i.lowStock ? ` · ${tr(language, 'stock.lowStock')}` : ''}</Text><Text style={styles.infoValue}>{quantityLabel(language, i)}</Text><Text style={styles.smallNote}>{Number(i.value) === 0 && Number(i.quantity) > 0 ? tr(language, 'stock.nilValue') : money(Number(i.value))}</Text></View>)}{stock.expiringLots.map(l => <View key={l.lotId} style={styles.infoCard}><Text style={styles.infoLabel}>{tr(language, l.expired ? 'stock.expired' : 'stock.expiringSoon')}</Text><Text style={styles.infoValue}>{l.itemName} · {l.lotNo}</Text><Text style={styles.smallNote}>{l.expiryDate}</Text></View>)}</>}<Button title={t('refresh')} onPress={() => void refresh()} variant="outline"/></>;
  const renderMore = () => <><ScreenTitle title={t('more')} subtitle={t('modules')}/>{canSeeStock && <Pressable onPress={() => setTab('stock')} style={styles.moduleRow}><Text style={styles.moduleText}>📦  {tr(language, 'stock.title')}</Text><Text style={styles.moduleStatus}>›</Text></Pressable>}{['🌱  '+t('crops'),'🐄  '+t('livestock'),'🐓  '+tr(language,'modules.POULTRY'),'🐟  '+tr(language,'modules.AQUACULTURE'),'♻️  '+tr(language,'modules.ORGANIC'),'👩🏽‍🌾  '+tr(language,'modules.WORKFORCE'),'🚜  '+tr(language,'modules.ASSETS')].map((x,i)=><View style={styles.moduleRow} key={i}><Text style={styles.moduleText}>{x}</Text><Text style={styles.moduleStatus}>{i < 2 ? '›' : '·'}</Text></View>)}<Text style={styles.sectionTitle}>{t('settings')}</Text><View style={styles.infoCard}><Text style={styles.infoLabel}>{t('farmProfile')}</Text><Text style={styles.featureCopy}>{t('profileDesc')}</Text></View><View style={styles.infoCard}><Text style={styles.infoLabel}>{t('sync')}</Text><Text style={styles.infoValue}>{isApiConfigured ? t('apiConfigured') : t('apiMissing')}</Text><Text style={styles.smallNote}>{API_BASE_URL || t('localMode')}</Text></View><Button title={language === 'ta' ? 'Switch to English' : 'தமிழுக்கு மாற்றவும்'} onPress={() => setLanguage(language === 'ta' ? 'en' : 'ta')} variant="outline"/><View style={styles.infoCard}><Text style={styles.infoLabel}>{tr(language, 'nav.switchTenant')}</Text><Text style={styles.infoValue}>{tenant.name}</Text><Text style={styles.smallNote}>{tr(language, 'auth.signedInAs', { email: user.email })}</Text></View><View style={{gap:10}}><Button title={tr(language, 'mobileAuth.switchTenant')} onPress={onSwitchTenant} variant="secondary"/><Button title={tr(language, 'auth.signOut')} onPress={onSignOut} variant="outline"/></View></>;

  const screen = tab === 'home' ? renderHome() : tab === 'records' ? renderRecords() : tab === 'crops' ? renderCrops() : tab === 'livestock' ? renderLivestock() : tab === 'stock' ? renderStock() : renderMore();
  return <SafeAreaView style={styles.safe}><ExpoStatusBar style="dark"/><StatusBar barStyle="dark-content" backgroundColor="#F5F7F0"/><View style={styles.appBody}><ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>{loading ? <ActivityIndicator size="large" color="#28734A" style={{marginTop:60}}/> : screen}</ScrollView><View style={styles.tabBar}>{([['home','⌂',t('dashboard')],['records','▤',t('records')],['crops','♧',t('crops')],['livestock','♙',t('livestock')],['more','•••',t('more')]] as [Tab,string,string][]).map(([key,icon,label])=><Pressable key={key} onPress={() => setTab(key)} style={styles.tabItem}><Text style={[styles.tabIcon,tab===key && styles.activeTabIcon]}>{icon}</Text><Text numberOfLines={1} style={[styles.tabLabel,tab===key && styles.activeTabLabel]}>{label}</Text>{tab===key && <View style={styles.activeDot}/>}</Pressable>)}</View></View>
    <Modal visible={modal !== null} animationType="slide" transparent onRequestClose={() => setModal(null)}><KeyboardAvoidingView style={styles.modalShade} behavior={Platform.OS === 'ios' ? 'padding' : undefined}><View style={styles.modalCard}><View style={styles.modalHandle}/><Text style={styles.modalTitle}>{modal === 'expense' ? t('addExpense') : t('addIncome')}</Text><Text style={styles.fieldLabel}>{t('amount')}</Text><TextInput value={amount} onChangeText={setAmount} keyboardType="decimal-pad" placeholder="0.00" style={styles.input} placeholderTextColor="#9AA59B"/><Text style={styles.fieldLabel}>{t('description')}</Text><TextInput value={description} onChangeText={setDescription} placeholder={t('description')} style={styles.input} placeholderTextColor="#9AA59B"/><Text style={styles.fieldLabel}>{t('category')}</Text><ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.categoryScroll}>{(modal === 'expense' ? expenseCategories : incomeCategories).map(c=><Pressable key={c} onPress={() => setCategory(c)} style={[styles.categoryChip,category===c && styles.categorySelected]}><Text style={[styles.categoryText,category===c && styles.categorySelectedText]}>{t(c)}</Text></Pressable>)}</ScrollView><Text style={styles.fieldLabel}>{t('date')}</Text><TextInput value={date} onChangeText={setDate} style={styles.input} placeholder="YYYY-MM-DD"/><View style={styles.modalButtons}><View style={{flex:1}}><Button title={t('cancel')} variant="outline" onPress={() => setModal(null)}/></View><View style={{flex:1}}><Pressable disabled={saving} onPress={() => void submitEntry()} style={[styles.button,saving && {opacity:0.6}]}><Text style={styles.buttonText}>{saving ? '…' : t('save')}</Text></Pressable></View></View></View></KeyboardAvoidingView></Modal>
  </SafeAreaView>;
}

function EntryRow({entry,language}:{entry:FarmEntry;language:Language}) {
  const tr = translations[language]; const isIncome = entry.kind === 'income';
  const categoryLabel = (tr as Record<string,string>)[entry.category] || entry.category;
  return <View style={styles.entryRow}><View style={[styles.entryGlyph,isIncome ? styles.entryGlyphIncome : styles.entryGlyphExpense]}><Text style={[styles.entryGlyphText,isIncome ? styles.green : styles.red]}>{isIncome ? '↗' : '↘'}</Text></View><View style={styles.entryInfo}><Text style={styles.entryDescription} numberOfLines={1}>{entry.description}</Text><Text style={styles.entryMeta}>{categoryLabel} · {entry.date}</Text></View><View style={styles.entryAmountWrap}><Text style={[styles.entryAmount,isIncome ? styles.green : styles.red]}>{isIncome ? '+' : '−'}{money(entry.amount)}</Text><Text style={styles.entrySync}>{entry.syncStatus === 'pending' ? tr.pendingBadge : entry.syncStatus === 'local-only' ? tr.localBadge : tr.syncedBadge}</Text></View></View>;
}

const styles = StyleSheet.create({
  safe:{flex:1,backgroundColor:'#F5F7F0'},appBody:{flex:1},scroll:{paddingHorizontal:18,paddingTop:8,paddingBottom:24},
  hero:{backgroundColor:'#173F2C',borderRadius:26,padding:22,marginBottom:18,overflow:'hidden'},heroTop:{flexDirection:'row',alignItems:'center',justifyContent:'space-between'},brand:{fontSize:17,fontWeight:'800',color:'#FFFFFF',letterSpacing:.2},languagePill:{paddingHorizontal:12,paddingVertical:7,borderRadius:20,backgroundColor:'#315B43'},languageText:{color:'#FFFFFF',fontWeight:'700',fontSize:12},heroTitle:{fontSize:25,fontWeight:'800',color:'#FFFFFF',marginTop:24,lineHeight:32},heroSub:{color:'#D0E3D3',fontSize:13,marginTop:6},leafBadge:{flexDirection:'row',alignItems:'center',alignSelf:'flex-start',gap:7,marginTop:18,paddingHorizontal:11,paddingVertical:7,backgroundColor:'#315B43',borderRadius:20},leafEmoji:{fontSize:13},leafText:{color:'#E1F1E4',fontWeight:'600',fontSize:12},
  metricsGrid:{gap:10,marginBottom:22},metric:{backgroundColor:'#FFFFFF',borderRadius:17,paddingHorizontal:16,paddingVertical:14,borderWidth:1,borderColor:'#E8ECE4'},metricLabel:{fontSize:12,color:'#778277',fontWeight:'600'},metricValue:{fontSize:23,fontWeight:'800',color:'#24352A',marginTop:5},green:{color:'#247447'},red:{color:'#C44E45'},sectionTitle:{fontSize:17,fontWeight:'800',color:'#22372A',marginBottom:12,marginTop:3},actionsRow:{flexDirection:'row',gap:11,marginBottom:22},actionCard:{flex:1,backgroundColor:'#FCEDE7',borderRadius:18,padding:15,minHeight:112,justifyContent:'space-between'},incomeAction:{backgroundColor:'#E5F3E7'},actionIcon:{fontSize:27,fontWeight:'800',color:'#C45B44'},incomeIcon:{color:'#247447'},actionTitle:{fontSize:14,fontWeight:'800',color:'#27392D'},sectionHeader:{flexDirection:'row',alignItems:'center',justifyContent:'space-between',marginBottom:9},seeAll:{color:'#28734A',fontSize:12,fontWeight:'700'},emptyCard:{backgroundColor:'#FFFFFF',borderRadius:18,padding:24,alignItems:'center',borderWidth:1,borderColor:'#E8ECE4'},emptyEmoji:{fontSize:28,marginBottom:8},emptyText:{color:'#7A857B',fontSize:13,textAlign:'center',lineHeight:20},noteCard:{backgroundColor:'#EEF3E9',padding:14,borderRadius:15,marginTop:18},noteTitle:{fontSize:13,fontWeight:'800',color:'#3C5943'},noteText:{fontSize:12,color:'#627064',lineHeight:18,marginTop:6},
  tabBar:{flexDirection:'row',backgroundColor:'#FFFFFF',borderTopWidth:1,borderTopColor:'#E8ECE4',paddingTop:9,paddingBottom:Platform.OS==='ios'?22:9,paddingHorizontal:5},tabItem:{flex:1,alignItems:'center',justifyContent:'center',gap:3,minWidth:0},tabIcon:{fontSize:22,color:'#8C978C',height:26},activeTabIcon:{color:'#28734A'},tabLabel:{fontSize:10,color:'#8C978C',fontWeight:'600'},activeTabLabel:{color:'#28734A',fontWeight:'800'},activeDot:{width:4,height:4,borderRadius:2,backgroundColor:'#28734A',marginTop:1},
  titleWrap:{marginTop:12,marginBottom:20},screenTitle:{fontSize:25,fontWeight:'800',color:'#21372A'},subtitle:{fontSize:13,color:'#778277',lineHeight:20,marginTop:5},button:{backgroundColor:'#28734A',borderRadius:13,paddingHorizontal:14,paddingVertical:13,alignItems:'center',justifyContent:'center',minHeight:46},buttonText:{color:'#FFFFFF',fontSize:13,fontWeight:'800'},secondaryButton:{backgroundColor:'#E4F1E5'},secondaryButtonText:{color:'#28734A'},outlineButton:{backgroundColor:'transparent',borderWidth:1,borderColor:'#D3DDD1'},
  entryRow:{backgroundColor:'#FFFFFF',borderRadius:15,padding:12,marginBottom:9,flexDirection:'row',alignItems:'center',gap:11,borderWidth:1,borderColor:'#EBEEE8'},entryGlyph:{width:40,height:40,borderRadius:13,alignItems:'center',justifyContent:'center'},entryGlyphIncome:{backgroundColor:'#E5F3E7'},entryGlyphExpense:{backgroundColor:'#FCEDE7'},entryGlyphText:{fontSize:21,fontWeight:'800'},entryInfo:{flex:1,minWidth:0},entryDescription:{fontSize:13,fontWeight:'800',color:'#27392D'},entryMeta:{fontSize:10,color:'#899389',marginTop:4},entryAmountWrap:{alignItems:'flex-end'},entryAmount:{fontSize:13,fontWeight:'800'},entrySync:{fontSize:9,color:'#929C91',marginTop:4},
  featureHero:{backgroundColor:'#E6F0E2',borderRadius:22,padding:22,alignItems:'center',marginBottom:14},featureEmoji:{fontSize:42,marginBottom:8},featureHeading:{fontSize:19,fontWeight:'800',color:'#24432F'},featureCopy:{fontSize:12,color:'#6B7D6C',textAlign:'center',lineHeight:19,marginTop:7},infoCard:{backgroundColor:'#FFFFFF',padding:16,borderRadius:15,marginBottom:10,borderWidth:1,borderColor:'#E8ECE4'},infoLabel:{fontSize:12,color:'#7B877B',fontWeight:'600'},infoValue:{fontSize:20,fontWeight:'800',color:'#24382A',marginTop:7},smallNote:{fontSize:11,color:'#8A9489',lineHeight:17,marginVertical:8},moduleRow:{backgroundColor:'#FFFFFF',borderRadius:14,padding:15,marginBottom:8,flexDirection:'row',justifyContent:'space-between',alignItems:'center',borderWidth:1,borderColor:'#E8ECE4'},moduleText:{fontSize:14,fontWeight:'700',color:'#2B3D30'},moduleStatus:{fontSize:23,color:'#8A978A'},
  modalShade:{flex:1,justifyContent:'flex-end',backgroundColor:'rgba(18,34,23,0.42)'},modalCard:{backgroundColor:'#F9FAF6',borderTopLeftRadius:26,borderTopRightRadius:26,padding:22,paddingBottom:Platform.OS==='ios'?30:22},modalHandle:{width:42,height:4,backgroundColor:'#D6DED3',borderRadius:3,alignSelf:'center',marginBottom:18},modalTitle:{fontSize:21,fontWeight:'800',color:'#21372A',marginBottom:18},fieldLabel:{fontSize:12,fontWeight:'800',color:'#506151',marginBottom:7,marginTop:8},input:{backgroundColor:'#FFFFFF',borderRadius:12,borderWidth:1,borderColor:'#DFE6DC',paddingHorizontal:13,paddingVertical:12,fontSize:14,color:'#21372A'},categoryScroll:{marginVertical:3},categoryChip:{borderRadius:20,borderWidth:1,borderColor:'#D9E2D6',paddingHorizontal:12,paddingVertical:9,marginRight:7,backgroundColor:'#FFFFFF'},categorySelected:{backgroundColor:'#28734A',borderColor:'#28734A'},categoryText:{fontSize:11,fontWeight:'700',color:'#506151'},categorySelectedText:{color:'#FFFFFF'},modalButtons:{flexDirection:'row',gap:10,marginTop:20}
});
