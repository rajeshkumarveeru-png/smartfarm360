import { tr, translations } from '../src/i18n/translations';

test('every mobile string exists in English and Tamil', () => {
  expect(Object.keys(translations.ta).sort()).toEqual(Object.keys(translations.en).sort());
  for (const value of Object.values(translations.ta)) expect(value.trim()).not.toBe('');
});

test('switching language changes shared strings and fills placeholders', () => {
  expect(tr('en', 'auth.signIn')).toBe('Sign in');
  expect(tr('ta', 'auth.signIn')).toBe('உள்நுழை');
  expect(tr('en', 'auth.signedInAs', { email: 'a@b.in' })).toBe('Signed in as a@b.in');
  expect(tr('ta', 'errors.TENANT_SUSPENDED')).toContain('இடைநிறுத்த');
  expect(tr('en', 'no.such.key')).toBe('no.such.key');
});
