package com.cadence.api.activities;

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
 * A comment on an activity, from the athlete or anyone with read access to their data (a
 * coach or viewer share) - a lightweight social feature, not a data mutation, so it's
 * gated by read access rather than write access (see ActivityCommentController). Role
 * (athlete vs coach) is derived at read time from the relationship to the activity's
 * owner, not stored - it can change (a share could be revoked) and storing it would let
 * it drift from the truth.
 */
@Entity
@Table(name = "activity_comment")
public class ActivityComment extends PrefixedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "activity_id", nullable = false)
	private Activity activity;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;

	@Column(nullable = false)
	private String text;

	@Column(nullable = false)
	private Instant created;

	@PrePersist
	private void onCreate() {
		if (created == null) {
			created = Instant.now();
		}
	}

	@Override
	protected String idPrefix() {
		return "cmt";
	}

	public Activity getActivity() {
		return activity;
	}

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public User getAuthor() {
		return author;
	}

	public void setAuthor(User author) {
		this.author = author;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public Instant getCreated() {
		return created;
	}
}
