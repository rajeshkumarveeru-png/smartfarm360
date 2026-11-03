import { describe, expect, it } from 'vitest';
import en from '../../../shared/i18n/en.json';
import ta from '../../../shared/i18n/ta.json';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

type Tree = { [key: string]: string | Tree };

function keys(tree: Tree, prefix = ''): string[] {
  return Object.entries(tree).flatMap(([k, v]) => (typeof v === 'string' ? [`${prefix}${k}`] : keys(v, `${prefix}${k}.`)));
}

function value(tree: Tree, key: string): string {
  return key.split('.').reduce<string | Tree>((node, part) => (node as Tree)[part], tree) as string;
}

/** Every `code` the backend can return and every audit action it records, read from the Java source. */
function backendCodes(): { errors: string[]; actions: string[] } {
  // Tests run from frontend/ (npm test, CI); jsdom does not give a file: import.meta.url.
  const root = resolve(process.cwd(), '../backend/src/main/java');
  const files: string[] = [];
  const walk = (dir: string) => readdirSync(dir, { withFileTypes: true }).forEach(e => (e.isDirectory() ? walk(join(dir, e.name)) : e.name.endsWith('.java') && files.push(join(dir, e.name))));
  walk(root);
  const errors = new Set<string>(), actions = new Set<string>();
  for (const file of files) {
    const src = readFileSync(file, 'utf8');
    for (const m of src.matchAll(/ApiException\.\w+\("([A-Z_]+)"|new ApiException\(HttpStatus\.\w+, "([A-Z_]+)"/g)) errors.add(m[1] ?? m[2]);
    for (const m of src.matchAll(/audit\.record\([^"]*?"([A-Z_]+)"(?: : "([A-Z_]+)")?/g)) { actions.add(m[1]); if (m[2]) actions.add(m[2]); }
  }
  return { errors: [...errors], actions: [...actions] };
}

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

  it('translates every error code and audit action the backend uses', () => {
    const { errors, actions } = backendCodes();
    expect(errors.length).toBeGreaterThan(50);
    for (const code of errors) {
      expect(value(en as Tree, `errors.${code}`), code).toBeTruthy();
      expect(value(ta as Tree, `errors.${code}`), code).toBeTruthy();
    }
    for (const action of actions) {
      expect(value(en as Tree, `auditAction.${action}`), action).toBeTruthy();
      expect(value(ta as Tree, `auditAction.${action}`), action).toBeTruthy();
    }
  });

  it('marks Tamil as unreviewed until a fluent reviewer signs it off', () => {
    expect((ta as Tree)._meta).toMatchObject({ reviewStatus: 'UNREVIEWED' });
  });
});
