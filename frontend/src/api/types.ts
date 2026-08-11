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
  /** Optional - only used for the Karvonen heart-rate-reserve % stat on Activity Analysis. */
  resting_hr: number | null;
  best_effort_top_n: number;
  is_coach: boolean;
  is_admin: boolean;
  /** When an upload auto-matches a scheduled workout, replace the activity's name with the
   * workout's name instead of leaving the device/default name. */
  rename_matched_activities: boolean;
  /** Also appends " - YYYY-MM-DD" to the matched name. Only has an effect when
   * rename_matched_activities is also true. */
  append_match_date_to_name: boolean;
  /** When an upload auto-matches a scheduled workout, also copies the workout's tags onto the
   * activity. Independent of the naming preferences above. */
  copy_matched_workout_tags: boolean;
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

export interface DelegatedTokenResponse {
  token: string;
  token_type: string;
  expires_in: number;
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
export type Sport = "bike" | "run" | "swim" | "walk" | "row" | "multisport" | "transition";
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
  max_power: number | null;
  avg_cadence: number | null;
  max_cadence: number | null;
  /** km/h. */
  max_speed: number | null;
  /** Metres. */
  total_descent: number | null;
  /** Metres. */
  elevation_min: number | null;
  /** Metres. */
  elevation_max: number | null;
  /** Power-based estimate only; null for activities with no power data. */
  calories: number | null;
  /** Edwards' TRIMP - sum over HR zones of (minutes in zone * zone number 1-5). */
  trimp: number | null;
  /** Mean % of power from the left leg (dual-sided power meters only). Right % is 100 minus this. */
  avg_left_balance_pct: number | null;
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
  weight_kg?: number;
  ftp?: number;
  critical_run_power?: number;
  threshold_pace?: string;
  lthr?: number;
  max_hr?: number;
  resting_hr?: number;
  best_effort_top_n?: number;
  rename_matched_activities?: boolean;
  append_match_date_to_name?: boolean;
  copy_matched_workout_tags?: boolean;
}

export interface ActivityComment {
  id: string;
  activity_id: string;
  author_id: string;
  author_name: string;
  author_role: "athlete" | "coach" | "viewer";
  text: string;
  created: string;
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

export type BestEffortKind = "cycling_hr" | "cycling_power" | "running_hr" | "running_pace" | "running_power";
export type BestEffortPeriod = "4w" | "3m" | "16w" | "1y" | "all";

export interface Tag {
  id: string;
  name: string;
  origin: "manual" | "auto";
  color: string;
  count: number;
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

export type ExportStatus = "queued" | "processing" | "ready" | "failed";

// The 5 sections both export_writer.py/ExportWriter.java and import_reader.py/ImportReader.java
// process in - null before processing starts (or once a job reaches a terminal status).
export type DataTransferStep = "equipment" | "workouts" | "activities" | "races" | "scheduled_workouts";

export interface ExportJob {
  id: string;
  object: "export";
  status: ExportStatus;
  current_step: DataTransferStep | null;
  file_size_bytes: number | null;
  error_message: string | null;
  created_at: string;
  completed_at: string | null;
}

export type ImportStatus = "queued" | "processing" | "ready" | "failed";

export interface ImportCounts {
  activities_imported: number;
  races_imported: number;
  workouts_imported: number;
  scheduled_workouts_imported: number;
  bikes_imported: number;
  shoes_imported: number;
  components_imported: number;
  items_skipped: number;
}

export interface ImportJob {
  id: string;
  object: "import";
  status: ImportStatus;
  current_step: DataTransferStep | null;
  counts: ImportCounts;
  error_message: string | null;
  created_at: string;
  completed_at: string | null;
}

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
export type TargetType = "power" | "hr" | "pace" | "cadence" | "open";
export type Target2Type = "cadence" | "none";

export interface LeafStep {
  kind: StepKind;
  end_type: StepEndType;
  duration: number | null;
  distance: number | null;
  target_type: TargetType;
  target_low: number | null;
  target_high: number | null;
  target2_type: Target2Type;
  target2_low: number | null;
  target2_high: number | null;
  note: string;
}

export interface RepeatGroup {
  kind: "repeat";
  repeat: number;
  note: string;
  children: WorkoutStep[];
}

export type WorkoutStep = LeafStep | RepeatGroup;

export interface Workout {
  id: string;
  name: string;
  sport: WorkoutSport;
  type: string;
  duration: number;
  tss: number;
  folder_id: string | null;
  tags: string[];
  chart_preview: number[];
  updated_at: string;
}

export interface WorkoutFolder {
  id: string;
  name: string;
  count: number;
}

export type WorkoutSort = "recent" | "name" | "duration" | "tss" | "used";

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
  tss: number;
  moving_time: number;
  distance_km: number;
  avg_power: number | null;
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

export interface Race {
  id: string;
  athlete_id: string;
  name: string;
  date: string;
  sport: string;
  distance_km: number | null;
  goal_time: string | null;
  result_time: string | null;
  activity_id: string | null;
  url: string | null;
  results_url: string | null;
  notes: string;
}

export interface RaceCreate {
  name: string;
  date: string;
  sport?: string;
  distance_km?: number | null;
  goal_time?: string | null;
  result_time?: string | null;
  activity_id?: string | null;
  url?: string | null;
  results_url?: string | null;
  notes?: string;
}

export interface ShoeCatalogVersionUsage {
  version: string;
  usage_count: number;
}

export interface AdminShoeCatalogEntry {
  id: string;
  manufacturer: string;
  model: string;
  versions: ShoeCatalogVersionUsage[];
  added_by: string | null;
}

export interface AdminUser {
  id: string;
  name: string;
  email: string;
  date_joined: string;
  is_coach: boolean;
  is_admin: boolean;
}

export interface AdminUserUpdate {
  is_coach?: boolean;
  is_admin?: boolean;
}

export interface AdminRelationship {
  id: string;
  coach_name: string;
  athlete_name: string;
  role: ShareRole;
  granted: string;
}

export type CatalogAuditAction = "added" | "removed";

export interface CatalogAuditLogEntry {
  id: string;
  description: string;
  action: CatalogAuditAction;
  by: string | null;
  created: string;
}

export type RaceUpdate = Partial<RaceCreate>;
