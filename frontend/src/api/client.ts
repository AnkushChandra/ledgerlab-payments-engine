export interface FieldError {
  field: string;
  message: string;
}

/** A failed API call, carrying the server's RFC 7807 problem details. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly title: string,
    readonly detail: string,
    readonly fieldErrors: FieldError[],
    readonly correlationId: string | null,
  ) {
    super(detail || title);
    this.name = 'ApiError';
  }
}

type TokenProvider = () => string | null;
type UnauthorizedHandler = () => void;

let tokenProvider: TokenProvider = () => null;
let onUnauthorized: UnauthorizedHandler = () => {};

export function configureApiClient(provider: TokenProvider, unauthorized: UnauthorizedHandler) {
  tokenProvider = provider;
  onUnauthorized = unauthorized;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH';
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

const BASE = '/api/v1';

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export async function apiDownload(path: string): Promise<{ blob: Blob; fileName: string }> {
  const response = await send(path, {});
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename="?([^";]+)"?/.exec(disposition);
  return { blob: await response.blob(), fileName: match?.[1] ?? 'download.csv' };
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenProvider();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] = options.idempotencyKey;
  }
  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body,
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new ApiError(0, 'NETWORK_ERROR', 'Network error', 'The server could not be reached.', [], null);
  }

  if (!response.ok) {
    const problem = await readProblem(response);
    if (response.status === 401 && path !== '/auth/login') {
      onUnauthorized();
    }
    throw problem;
  }
  return response;
}

async function readProblem(response: Response): Promise<ApiError> {
  const correlationId = response.headers.get('X-Correlation-Id');
  try {
    const problem = (await response.json()) as {
      code?: string;
      title?: string;
      detail?: string;
      errors?: FieldError[];
      correlationId?: string;
    };
    return new ApiError(
      response.status,
      problem.code ?? 'UNKNOWN',
      problem.title ?? response.statusText,
      problem.detail ?? '',
      problem.errors ?? [],
      problem.correlationId ?? correlationId,
    );
  } catch {
    return new ApiError(
      response.status,
      'UNKNOWN',
      response.statusText || 'Request failed',
      'The server returned an unexpected response.',
      [],
      correlationId,
    );
  }
}

export function toQuery(params: Record<string, string | number | string[] | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((v) => search.append(key, v));
    } else {
      search.set(key, String(value));
    }
  }
  const query = search.toString();
  return query ? `?${query}` : '';
}
