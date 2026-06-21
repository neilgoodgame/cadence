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
  role: ShareRole;
  compliance: number;
  tsb: number;
  last_activity_at: string | null;
}

export interface Share {
  id: string;
  name: string;
  handle: string | null;
  role: ShareRole;
  status: "pending" | "active";
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

export type Sport = "bike" | "run" | "swim" | "walk";
export type Environment = "outdoor" | "indoor";
export type DistanceSource = "gps" | "footpod" | "trainer" | "manual";
export type ZoneType = "heart_rate" | "bike_power" | "run_power" | "pace";
export type ShareRole = "viewer" | "coach";

/** The cursor-paginated envelope - only GET /v1/activities uses this. */
export interface List<T> {
  object: "list";
  has_more: boolean;
  next_cursor: string | null;
  data: T[];
}

/** The plain `{data: [...]}` wrapper every other list endpoint (laps, tags, zones, ...) uses. */
export interface DataList<T> {
  data: T[];
}

export interface Activity {
  id: string;
  athlete_id: string;
  sport: Sport;
  environment: Environment;
  has_gps: boolean;
  name: string;
  start_date: string;
  source: string;
  moving_time: number;
  distance_km: number;
  distance_source: DistanceSource;
  avg_power: number | null;
  norm_power: number | null;
  intensity: number;
  tss: number;
  avg_hr: number;
  max_hr: number;
  ascent: number;
  start_weight_kg: number | null;
  end_weight_kg: number | null;
  fluids_ml: number | null;
  avg_air_temp: number | null;
  avg_humidity: number | null;
  tags: string[];
  workout_id: string | null;
  bike_id: string | null;
  shoe_id: string | null;
}

export interface AthleteUpdate {
  name?: string;
  age?: number;
  ftp?: number;
  critical_run_power?: number;
  threshold_pace?: string;
  lthr?: number;
  max_hr?: number;
}

export interface AthleteUpdateResponse extends Athlete {
  zones_recomputed: ZoneType[];
}

export interface ActivityUpdate {
  name?: string;
  sport?: Sport;
  workout_id?: string | null;
  start_weight_kg?: number | null;
  end_weight_kg?: number | null;
  fluids_ml?: number | null;
  avg_air_temp?: number | null;
  avg_humidity?: number | null;
}

export interface Lap {
  index: number;
  duration: number;
  distance_km: number;
  avg_hr: number;
  avg_power: number;
}

export interface StreamsResponse {
  object: "streams";
  resolution: "high" | "medium" | "low";
  fields: Record<string, (number | null)[]>;
}

export interface DurationCurve {
  metric: "power" | "heartrate";
  extends_to: number;
  /** Keys are duration-in-seconds, encoded as strings (JSON object keys). */
  points: Record<string, number>;
}

export interface BestEffort {
  window: string;
  value: number;
  unit: string;
  date: string;
  activity_id: string;
}

export type BestEffortKind = "cycling_power" | "running_pace" | "running_power";
export type BestEffortPeriod = "3m" | "1y" | "all";

export interface Tag {
  id: string;
  name: string;
  origin: "manual" | "auto";
  color: string;
}

export interface FitnessPoint {
  date: string;
  ctl: number;
  atl: number;
  tsb: number;
}

export interface Zone {
  name: string;
  low_pct: number;
  high_pct: number;
}

export interface ZoneSet {
  type: ZoneType;
  reference: number;
  zones: Zone[];
}

export interface ZoneSetUpdateResponse {
  type: ZoneType;
  reference: number;
  updated: boolean;
}

export interface AccessToken {
  id: string;
  name: string;
  prefix: string;
  scopes: string[];
  created: string;
  expires_at: string | null;
  last_used: string | null;
}

export interface AccessTokenWithSecret extends AccessToken {
  secret: string;
}
