import { describe, expect, it } from 'vitest';
import { formatMoney, parseMoneyInput, toInputValue } from './money';

describe('formatMoney', () => {
  it('formats cents as USD without using floating point', () => {
    expect(formatMoney(0)).toBe('$0.00');
    expect(formatMoney(1)).toBe('$0.01');
    expect(formatMoney(123456)).toBe('$1,234.56');
    expect(formatMoney(-50)).toBe('-$0.50');
  });

  it('renders a dash for missing amounts', () => {
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney(undefined)).toBe('—');
  });
});

describe('parseMoneyInput', () => {
  it('accepts dollars, cents and thousand separators', () => {
    expect(parseMoneyInput('12')).toBe(1200);
    expect(parseMoneyInput('12.3')).toBe(1230);
    expect(parseMoneyInput('1,234.56')).toBe(123456);
    expect(parseMoneyInput('$9.99')).toBe(999);
  });

  it('rejects zero, too-large and malformed values', () => {
    expect(parseMoneyInput('0')).toBeNull();
    expect(parseMoneyInput('0.00')).toBeNull();
    expect(parseMoneyInput('1000000.01')).toBeNull();
    expect(parseMoneyInput('12.345')).toBeNull();
    expect(parseMoneyInput('abc')).toBeNull();
  });
});

describe('toInputValue', () => {
  it('round-trips minor units into an editable string', () => {
    expect(toInputValue(1234)).toBe('12.34');
    expect(toInputValue(5)).toBe('0.05');
  });
});
