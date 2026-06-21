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
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return fetch(`${BASE_URL}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await rawFetch(path, options, options.anonymous ? null : accessToken);

  if (response.status === 401 && !options.anonymous && refreshHandler) {
    const newToken = await refreshHandler();
    if (newToken) {
      response = await rawFetch(path, options, newToken);
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await parseErrorBody(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
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
