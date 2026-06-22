import { ApiError, type ApiErrorBody } from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL;

let accessToken: string | null = null;

/** Set by AuthContext - attempts a silent refresh, returns the new access token or null if it failed. */
let refreshHandler: (() => Promise<string | null>) | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function setRefreshHandler(handler: (() => Promise<string | null>) | null): void {
  refreshHandler = handler;
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Skip the Authorization header and the refresh-on-401 retry - only auth endpoints need this. */
  anonymous?: boolean;
}

async function parseErrorBody(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  }
  catch {
    return { error: { type: "api_error", code: "error", message: response.statusText, param: null } };
  }
}

async function rawFetch(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const isFormData = options.body instanceof FormData;
  const headers: Record<string, string> = {};
  // A FormData body needs the browser to set its own multipart/form-data boundary header -
  // setting Content-Type ourselves (or JSON.stringify-ing a FormData object) breaks the request.
  if (!isFormData) {
    headers["Content-Type"] = "application/json";
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return fetch(`${BASE_URL}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: isFormData ? (options.body as FormData) : options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
}

async function fetchWithRefresh(path: string, options: RequestOptions): Promise<Response> {
  let response = await rawFetch(path, options, options.anonymous ? null : accessToken);

  if (response.status === 401 && !options.anonymous && refreshHandler) {
    const newToken = await refreshHandler();
    if (newToken) {
      response = await rawFetch(path, options, newToken);
    }
  }
  return response;
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await fetchWithRefresh(path, options);

  if (!response.ok) {
    throw new ApiError(response.status, await parseErrorBody(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Like apiFetch, but also surfaces the Retry-After header - needed for polling an async
 * upload job, which is the only thing that currently needs anything beyond the parsed body. */
export async function apiFetchWithHeaders<T>(
  path: string,
  options: RequestOptions = {},
): Promise<{ data: T; retryAfterSeconds: number | null }> {
  const response = await fetchWithRefresh(path, options);

  if (!response.ok) {
    throw new ApiError(response.status, await parseErrorBody(response));
  }
  const retryAfterHeader = response.headers.get("Retry-After");
  const retryAfterSeconds = retryAfterHeader ? Number(retryAfterHeader) : null;
  const data = response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  return { data, retryAfterSeconds };
}

export async function apiFetchForm<T>(path: string, form: Record<string, string>): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(form).toString(),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await parseErrorBody(response));
  }
  return (await response.json()) as T;
}
