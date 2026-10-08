const dateTime = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'UTC',
});

export function formatDateTime(iso: string | null | undefined): string {
  return iso ? `${dateTime.format(new Date(iso))} UTC` : '—';
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : '—';
}

export function humanize(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((word, i) => (i === 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(' ');
}

export function newIdempotencyKey(): string {
  return `ui-${crypto.randomUUID()}`;
}
