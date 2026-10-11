package com.cadence.api.courses;

import com.cadence.api.activities.Activity;
import com.cadence.api.common.id.PrefixedIdEntity;
import com.cadence.api.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "course")
public class Course extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "athlete_id", nullable = false)
	private User athlete;

	@Column(nullable = false)
	private String name;

	@Column(name = "location_name", nullable = false)
	private String locationName = "";

	@Column(name = "start_lat")
	private Double startLat;

	@Column(name = "start_lng")
	private Double startLng;

	@Column(name = "distance_km")
	private Double distanceKm;

	@Column(name = "elevation_gain_m")
	private Integer elevationGainM;

	@Column(name = "elevation_loss_m")
	private Integer elevationLossM;

	@Column(name = "elevation_max_m")
	private Integer elevationMaxM;

	@Column(name = "elevation_min_m")
	private Integer elevationMinM;

	@Column(name = "is_loop", nullable = false)
	private boolean isLoop;

	@Column(nullable = false)
	private CourseSource source;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_activity_id")
	private Activity sourceActivity;

	// A path under the configured uploads media root (see UploadIngestService's storeFile for
	// the same Path/Files-based convention) - not a DB blob. Blank for a course created from an
	// activity (no original file to keep); the GPX download endpoint regenerates one from
	// routePolyline/elevationProfile in that case.
	@Column(name = "gpx_file", nullable = false)
	private String gpxFile = "";

	@Column(name = "gpx_filename", nullable = false)
	private String gpxFilename = "";

	/** Simplified [lat, lng] points (Douglas-Peucker), ~300-500 points. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "route_polyline", nullable = false)
	private List<List<Double>> routePolyline = new ArrayList<>();

	/** [km, elevation_m] pairs, ~200 points evenly spaced by distance. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "elevation_profile", nullable = false)
	private List<List<Double>> elevationProfile = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<Climb> climbs = new ArrayList<>();

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@PrePersist
	private void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "course";
	}

	public User getAthlete() { return athlete; }
	public void setAthlete(User athlete) { this.athlete = athlete; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public String getLocationName() { return locationName; }
	public void setLocationName(String locationName) { this.locationName = locationName; }

	public Double getStartLat() { return startLat; }
	public void setStartLat(Double startLat) { this.startLat = startLat; }

	public Double getStartLng() { return startLng; }
	public void setStartLng(Double startLng) { this.startLng = startLng; }

	public Double getDistanceKm() { return distanceKm; }
	public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }

	public Integer getElevationGainM() { return elevationGainM; }
	public void setElevationGainM(Integer elevationGainM) { this.elevationGainM = elevationGainM; }

	public Integer getElevationLossM() { return elevationLossM; }
	public void setElevationLossM(Integer elevationLossM) { this.elevationLossM = elevationLossM; }

	public Integer getElevationMaxM() { return elevationMaxM; }
	public void setElevationMaxM(Integer elevationMaxM) { this.elevationMaxM = elevationMaxM; }

	public Integer getElevationMinM() { return elevationMinM; }
	public void setElevationMinM(Integer elevationMinM) { this.elevationMinM = elevationMinM; }

	public boolean isLoop() { return isLoop; }
	public void setLoop(boolean loop) { this.isLoop = loop; }

	public CourseSource getSource() { return source; }
	public void setSource(CourseSource source) { this.source = source; }

	public Activity getSourceActivity() { return sourceActivity; }
	public void setSourceActivity(Activity sourceActivity) { this.sourceActivity = sourceActivity; }

	public String getGpxFile() { return gpxFile; }
	public void setGpxFile(String gpxFile) { this.gpxFile = gpxFile; }

	public String getGpxFilename() { return gpxFilename; }
	public void setGpxFilename(String gpxFilename) { this.gpxFilename = gpxFilename; }

	public List<List<Double>> getRoutePolyline() { return routePolyline; }
	public void setRoutePolyline(List<List<Double>> routePolyline) { this.routePolyline = routePolyline; }

	public List<List<Double>> getElevationProfile() { return elevationProfile; }
	public void setElevationProfile(List<List<Double>> elevationProfile) { this.elevationProfile = elevationProfile; }

	public List<Climb> getClimbs() { return climbs; }
	public void setClimbs(List<Climb> climbs) { this.climbs = climbs; }

	public Instant getCreatedAt() { return createdAt; }
}
