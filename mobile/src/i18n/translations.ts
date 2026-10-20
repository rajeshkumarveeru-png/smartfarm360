import en from '../../../shared/i18n/en.json';
import ta from '../../../shared/i18n/ta.json';

/** Strings come from the catalog shared with the web app (shared/i18n). */
export type Language = 'en' | 'ta';
const catalogs = { en, ta } as const;

/** Mobile-screen strings, keyed as before. */
export const translations = { en: en.mobile, ta: ta.mobile } as const;
export type TranslationKey = keyof typeof en.mobile;

/** Any catalog string by dotted path, e.g. tr('ta', 'auth.signIn'). Falls back to English, then to the key. */
export function tr(language: Language, path: string, params: Record<string, string> = {}): string {
  const lookup = (lang: Language) => path.split('.').reduce<unknown>((node, part) =>
    node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined, catalogs[lang]);
  const value = lookup(language) ?? lookup('en');
  const text = typeof value === 'string' ? value : path;
  return text.replace(/\{\{(\w+)\}\}/g, (_, name: string) => params[name] ?? '');
}
