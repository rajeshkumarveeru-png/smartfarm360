import { describe, expect, it } from 'vitest';
import { formatDate, formatMoney, formatNumber } from './format';

describe('formatting', () => {
  it('formats rupees with Indian digit grouping and two decimals', () => {
    expect(formatMoney(1234567.5, 'en')).toBe('₹12,34,567.50');
    expect(formatMoney('99.999', 'en')).toBe('₹100.00');
    expect(formatMoney(null, 'en')).toBe('₹0.00');
    expect(formatMoney('not-a-number', 'en')).toBe('₹0.00');
  });

  it('uses the Tamil locale for Tamil users without changing the amount', () => {
    const ta = formatMoney(1234567.5, 'ta');
    expect(ta).toContain('12,34,567.50');
    expect(ta).toContain('₹');
  });

  it('formats quantities and farm-local dates', () => {
    expect(formatNumber(2.5, 'en')).toBe('2.5');
    expect(formatNumber(1.23456, 'en')).toBe('1.235');
    // A YYYY-MM-DD farm date must not shift a day because of the browser timezone.
    expect(formatDate('2026-01-05', 'en')).toBe('5 Jan 2026');
    expect(formatDate('', 'en')).toBe('');
  });
});
