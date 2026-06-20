package com.cadence.api.activities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "duration_curve")
public class DurationCurve {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@Column(nullable = false)
	private DurationCurveMetric metric;

	@Column(name = "extends_to", nullable = false)
	private int extendsTo;

	/** Duration-in-seconds (as a string key, matching the wire format) -> best average value. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Double> points;

	public Long getId() {
		return id;
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public DurationCurveMetric getMetric() {
		return metric;
	}

	public void setMetric(DurationCurveMetric metric) {
		this.metric = metric;
	}

	public int getExtendsTo() {
		return extendsTo;
	}

	public void setExtendsTo(int extendsTo) {
		this.extendsTo = extendsTo;
	}

	public Map<String, Double> getPoints() {
		return points;
	}

	public void setPoints(Map<String, Double> points) {
		this.points = points;
	}
}
