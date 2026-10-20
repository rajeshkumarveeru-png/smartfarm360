import { describe, expect, it } from 'vitest';
import en from '../../../shared/i18n/en.json';
import ta from '../../../shared/i18n/ta.json';

type Tree = { [key: string]: string | Tree };

function keys(tree: Tree, prefix = ''): string[] {
  return Object.entries(tree).flatMap(([k, v]) => (typeof v === 'string' ? [`${prefix}${k}`] : keys(v, `${prefix}${k}.`)));
}

function value(tree: Tree, key: string): string {
  return key.split('.').reduce<string | Tree>((node, part) => (node as Tree)[part], tree) as string;
}

/** Error codes the API can return (see platform/web and services). Each needs a translation in both languages. */
const API_ERROR_CODES = [
  'INVALID_CREDENTIALS', 'RATE_LIMITED', 'PASSWORD_TOO_SHORT', 'PASSWORD_TOO_LONG', 'PASSWORD_TOO_WEAK', 'INVALID_RESET_TOKEN',
  'INVALID_CURRENT_PASSWORD', 'INVALID_REFRESH_TOKEN', 'UNAUTHENTICATED', 'ACCESS_DENIED', 'TENANT_NOT_FOUND', 'TENANT_SUSPENDED',
  'MODULE_DISABLED', 'MODULE_NOT_AVAILABLE', 'LAST_OWNER', 'OWNER_REQUIRED', 'ALREADY_MEMBER', 'INVITATION_NOT_FOUND',
  'MEMBER_NOT_FOUND', 'FARM_NOT_FOUND', 'LAND_PARCEL_NOT_FOUND', 'FARM_INACTIVE', 'INVALID_TIMEZONE', 'VALIDATION_FAILED',
  'CONFLICT', 'NOT_FOUND',
];

describe('shared translation catalog', () => {
  it('has exactly the same keys in English and Tamil', () => {
    expect(keys(ta as Tree).sort()).toEqual(keys(en as Tree).sort());
  });

  it('has no empty strings and keeps interpolation placeholders in both languages', () => {
    for (const key of keys(en as Tree)) {
      const e = value(en as Tree, key);
      const t = value(ta as Tree, key);
      expect(e.trim(), key).not.toBe('');
      expect(t.trim(), key).not.toBe('');
      const placeholders = (s: string) => (s.match(/\{\{\w+\}\}/g) ?? []).sort();
      expect(placeholders(t), key).toEqual(placeholders(e));
    }
  });

  it('translates every API error code', () => {
    for (const code of API_ERROR_CODES) {
      expect(value(en as Tree, `errors.${code}`), code).toBeTruthy();
      expect(value(ta as Tree, `errors.${code}`), code).toBeTruthy();
    }
  });

  it('marks Tamil as unreviewed until a fluent reviewer signs it off', () => {
    expect((ta as Tree)._meta).toMatchObject({ reviewStatus: 'UNREVIEWED' });
  });
});
