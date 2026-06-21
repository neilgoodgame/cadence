export interface Athlete {
  id: string;
  name: string;
  email: string;
  age: number | null;
  weight_kg: number | null;
  ftp: number | null;
  critical_run_power: number | null;
  threshold_pace: string | null;
  lthr: number | null;
  max_hr: number | null;
  is_coach: boolean;
}

export interface TokenResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  scope: string;
}

export interface AuthResponse {
  athlete: Athlete;
  tokens: TokenResponse;
}

export interface CoachedAthlete {
  relationship_id: string;
  user_id: string;
  name: string;
  role: string;
  compliance: number;
  tsb: number;
  last_activity_at: string | null;
}

export interface Share {
  id: string;
  name: string;
  handle: string | null;
  role: string;
  status: string;
  since: string;
}

export interface Contexts {
  self: Athlete;
  coaching: CoachedAthlete[];
  coached_by: Share[];
}

export interface ApiErrorBody {
  error: {
    type: string;
    code: string;
    message: string;
    param: string | null;
  };
}

export class ApiError extends Error {
  readonly type: string;
  readonly code: string;
  readonly param: string | null;
  readonly status: number;

  constructor(status: number, body: ApiErrorBody) {
    super(body.error.message);
    this.status = status;
    this.type = body.error.type;
    this.code = body.error.code;
    this.param = body.error.param;
  }
}
