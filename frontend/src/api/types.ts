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

/** multisport/transition only occur on activities imported from a multisport FIT file. */
export type Sport = "bike" | "run" | "swim" | "walk" | "multisport" | "transition";
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
  /** Recording device from the file's metadata (FIT file_id), e.g. "Zwift" or
   * "Garmin Edge 830"; empty when the format doesn't carry it (GPX/TCX). */
  device: string;
  moving_time: number;
  distance_km: number;
  distance_source: DistanceSource;
  avg_power: number | null;
  norm_power: number | null;
  intensity: number | null;
  tss: number;
  avg_hr: number | null;
  max_hr: number | null;
  ascent: number | null;
  start_weight_kg: number | null;
  end_weight_kg: number | null;
  fluids_ml: number | null;
  avg_air_temp: number | null;
  avg_humidity: number | null;
  /** Garmin device-computed 0.0-5.0 loads; null unless the upload was a .fit with them. */
  aerobic_training_effect: number | null;
  anaerobic_training_effect: number | null;
  /** Benefit label derived from aerobic_training_effect; empty string when that is null. */
  training_effect_label: string;
  tags: string[];
  workout_id: string | null;
  bike_id: string | null;
  shoe_id: string | null;
  /** Set on each leg of a multisport activity, pointing at its parent; null everywhere else. */
  parent_activity_id: string | null;
  /** For a multisport activity, its per-leg children (transitions included) in start order. */
  child_activity_ids: string[];
  /** Set on a duplicate recording, pointing at the primary that counts for training load. */
  primary_activity_id: string | null;
  /** Duplicate recordings linked to this activity; empty unless it is a primary. */
  duplicate_activity_ids: string[];
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
  /** Mark this activity as a duplicate of the given primary, or null to unlink. */
  primary_activity_id?: string | null;
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
  // openapi.yaml declares both as plain integers, but a real lap with no HR strap or no
  // power meter returns null for the corresponding field - confirmed against live data.
  avg_hr: number | null;
  avg_power: number | null;
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
  /** Null until the athlete sets the underlying threshold (LTHR, FTP, threshold pace). */
  reference: number | null;
  zones: Zone[];
}

export interface ZoneSetUpdateResponse {
  type: ZoneType;
  reference: number | null;
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

export type BikeKind = "road" | "indoor" | "gravel" | "tt";

export interface Bike {
  id: string;
  athlete_id: string;
  name: string;
  kind: BikeKind;
  groupset: string;
  distance_km: number;
  hours: number;
  rides: number;
  components: number;
}

export interface Component {
  id: string;
  bike_id: string;
  name: string;
  km: number;
  limit_km: number;
  model: string;
}

export interface BikeDetail extends Omit<Bike, "components"> {
  components: Component[];
}

export type ServiceAction = "replaced" | "cleaned" | "inspected" | "adjusted";

export interface ServiceRecord {
  id: string;
  component_id: string;
  action: ServiceAction;
  reset: boolean;
  note: string;
  date: string;
}

export interface ShoeCatalogEntry {
  shoe_model_version_id: string;
  manufacturer: string;
  model: string;
  version: string;
  display_name: string;
}

export type UploadStatus = "queued" | "processing" | "ready" | "failed" | "duplicate" | "skipped";

export interface Upload {
  id: string;
  object: "upload";
  status: UploadStatus;
  progress: number | null;
  filename: string;
  activity_id: string | null;
  activity_sport: Sport | null;
  error: { code: string; message: string } | null;
  received_at: string;
  completed_at: string | null;
}

export type UploadBatchStatus = "unpacking" | "processing" | "completed" | "failed";

export interface UploadBatch {
  id: string;
  object: "upload_batch";
  status: UploadBatchStatus;
  filename: string;
  progress: number;
  counts: { total: number; ready: number; processing: number; failed: number; duplicate: number; skipped: number };
  uploads: Upload[];
  received_at: string;
  completed_at: string | null;
}

export interface Shoe {
  id: string;
  athlete_id: string;
  shoe_model_version_id: string;
  manufacturer: string;
  model: string;
  version: string;
  colourway: string;
  name: string;
  image: string | null;
  role: string;
  km: number;
  limit_km: number;
  since: string;
}

export type WorkoutSport = "bike" | "run";
export type StepKind = "warmup" | "block" | "rec" | "cool";
export type StepEndType = "time" | "distance" | "manual";

export interface WorkoutStep {
  kind: StepKind;
  end_type: StepEndType;
  duration: number | null;
  distance: number | null;
  target_pct: number;
  repeat: number;
}

export interface Workout {
  id: string;
  name: string;
  sport: WorkoutSport;
  type: string;
  duration: number;
  tss: number;
}

export interface WorkoutDetail extends Workout {
  steps: WorkoutStep[];
}

export type WorkoutMatchMethod = "auto" | "manual";

export interface WorkoutMatch {
  activity_id: string;
  name: string;
  date: string;
  method: WorkoutMatchMethod;
  confidence: number | null;
  compliance: number | null;
}

export type TimeOfDay = "AM" | "MID" | "PM";
export type ScheduledWorkoutStatus = "planned" | "completed" | "missed";

export interface ScheduledWorkout {
  id: string;
  workout_id: string;
  athlete_id: string;
  assigned_by: string | null;
  date: string;
  // Python returns "" when unset; Java returns null - confirmed against both live.
  time_of_day: TimeOfDay | "" | null;
  status: ScheduledWorkoutStatus;
  activity_id: string | null;
}

/** data only ever contains ScheduledWorkout rows - unplanned_activities covers completed
 * activities in range that were never scheduled or matched to a designed workout. */
export interface CalendarResponse {
  data: ScheduledWorkout[];
  unplanned_activities: Activity[];
}
