import i18n from 'i18next';
import { initReactI18next, useTranslation } from 'react-i18next';
import en from '../../shared/i18n/en.json';
import ta from '../../shared/i18n/ta.json';

export type Lang = 'en' | 'ta';
const STORAGE_KEY = 'sf360.lang';

function storedLanguage(): Lang {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === 'ta' ? 'ta' : 'en';
  } catch {
    return 'en';
  }
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, ta: { translation: ta } },
  lng: storedLanguage(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false }, // React already escapes output
  returnNull: false,
  showSupportNotice: false,
});

export function setLanguage(lang: Lang) {
  try { localStorage.setItem(STORAGE_KEY, lang); } catch { /* storage unavailable: keep in memory */ }
  void i18n.changeLanguage(lang);
  document.documentElement.lang = lang;
}

export function currentLanguage(): Lang { return i18n.language === 'ta' ? 'ta' : 'en'; }

/** Translator bound to one namespace of the catalog, e.g. useNs('crop')('addCrop'). */
export function useNs(ns: string) {
  const { t } = useTranslation();
  return (key: string, options?: Record<string, unknown>) => t(`${ns}.${key}`, options ?? {});
}

export default i18n;
