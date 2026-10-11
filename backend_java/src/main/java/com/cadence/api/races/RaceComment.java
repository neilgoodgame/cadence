package com.cadence.api.races;

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

/**
 * Coach/athlete comments on a race plan - mirrors {@link com.cadence.api.activities.ActivityComment}'s
 * shape and read-gated permission model, except {@code role} is stored rather than derived at
 * read time: a race comment is meant to keep showing the role its author had *at the time they
 * posted it*, even if a coaching relationship is later revoked - unlike an activity comment's
 * author_role, which intentionally reflects the *current* relationship.
 */
@Entity
@Table(name = "race_comment")
public class RaceComment extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "race_id", nullable = false)
	private Race race;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;

	@Column(nullable = false)
	private RaceCommentRole role;

	@Column(nullable = false)
	private String text;

	private Integer week;

	/** Null for a top-level comment. Single-level threading only - enforced in
	 * RaceCommentService, not the DB. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_id")
	private RaceComment parent;

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
		return "rcm";
	}

	public Race getRace() {
		return race;
	}

	public void setRace(Race race) {
		this.race = race;
	}

	public User getAuthor() {
		return author;
	}

	public void setAuthor(User author) {
		this.author = author;
	}

	public RaceCommentRole getRole() {
		return role;
	}

	public void setRole(RaceCommentRole role) {
		this.role = role;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public Integer getWeek() {
		return week;
	}

	public void setWeek(Integer week) {
		this.week = week;
	}

	public RaceComment getParent() {
		return parent;
	}

	public void setParent(RaceComment parent) {
		this.parent = parent;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
