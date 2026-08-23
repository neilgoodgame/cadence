"""MCP tool scope constants - reuses the exact same scope strings REST already enforces
(OAUTH2_PROVIDER["SCOPES"] in config/settings.py), not a parallel taxonomy. Mirrors the Java
backend's McpScopes.java. ``ACTIVITIES_READ`` is the umbrella "read my training data" scope for
every read tool across every domain (activities/workouts/calendar/gear/races/zones/etc.) - no
per-domain read scopes exist, same product choice as Java's.
"""

ACTIVITIES_READ = "activities:read"
ACTIVITIES_WRITE = "activities:write"
WORKOUTS_WRITE = "workouts:write"
CALENDAR_WRITE = "calendar:write"
GEAR_WRITE = "gear:write"
