/**
 * Money helpers. Amounts are integer minor units (cents) end to end; user input is parsed as a
 * string so floating-point rounding never touches a monetary value.
 */
const formatter = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export const MAX_AMOUNT_MINOR = 100_000_000;

export function formatMoney(minor: number | null | undefined): string {
  if (minor === null || minor === undefined) {
    return '—';
  }
  const negative = minor < 0;
  const abs = Math.abs(minor);
  const dollars = Math.trunc(abs / 100);
  const cents = abs % 100;
  const text = formatter.format(dollars).replace(/\.00$/, '') + '.' + String(cents).padStart(2, '0');
  return negative ? `-${text}` : text;
}

/** Parses "12", "12.3" or "1,234.56" into minor units; returns null if the input is not a valid amount. */
export function parseMoneyInput(input: string): number | null {
  const normalized = input.trim().replace(/,/g, '').replace(/^\$/, '');
  const match = /^(\d{1,7})(?:\.(\d{1,2}))?$/.exec(normalized);
  if (!match) {
    return null;
  }
  const dollars = Number(match[1]);
  const cents = Number((match[2] ?? '').padEnd(2, '0'));
  const minor = dollars * 100 + cents;
  if (minor < 1 || minor > MAX_AMOUNT_MINOR) {
    return null;
  }
  return minor;
}

/** Formats minor units for an editable input field, e.g. 1234 -> "12.34". */
export function toInputValue(minor: number): string {
  return `${Math.trunc(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;
}
