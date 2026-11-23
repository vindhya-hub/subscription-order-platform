const moneyFormats = new Map<string, Intl.NumberFormat>();

/** Formats a Decimal string such as "33.00" in its currency, e.g. "$33.00". */
export function formatMoney(amount: string, currency: string): string {
  let format = moneyFormats.get(currency);
  if (!format) {
    format = new Intl.NumberFormat(undefined, { style: 'currency', currency });
    moneyFormats.set(currency, format);
  }
  const value = Number(amount);
  return Number.isFinite(value) ? format.format(value) : `${amount} ${currency}`;
}

// Calendar dates are server (UTC) dates: format them in UTC so they never shift by a day.
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' });
const dateTimeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });

export function formatDate(isoDate: string): string {
  return dateFormat.format(new Date(`${isoDate}T00:00:00Z`));
}

export function formatDateTime(isoInstant: string | null): string {
  return isoInstant ? dateTimeFormat.format(new Date(isoInstant)) : '—';
}

/** "BIWEEKLY" -> "Biweekly". */
export function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}

export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

export function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}
