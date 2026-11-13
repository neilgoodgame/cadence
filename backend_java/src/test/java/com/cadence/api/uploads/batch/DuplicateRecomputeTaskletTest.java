package com.cadence.api.uploads.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cadence.api.athletes.ThresholdField;
import com.cadence.api.athletes.ThresholdHistory;
import com.cadence.api.athletes.ThresholdHistoryKind;
import com.cadence.api.athletes.ThresholdHistoryRepository;
import com.cadence.api.common.domain.Sport;
import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicateRecomputeTaskletTest {

	private final ThresholdHistoryRepository repo = mock(ThresholdHistoryRepository.class);
	private final DuplicateRecomputeTasklet tasklet = new DuplicateRecomputeTasklet(null, null, repo, null, null);

	private static ThresholdHistory row(ThresholdField field, ThresholdHistoryKind kind) {
		ThresholdHistory row = new ThresholdHistory();
		row.setField(field);
		row.setKind(kind);
		return row;
	}

	private UploadJobContext.DuplicateChange change(Sport sport) {
		return new UploadJobContext.DuplicateChange("act_demoted", sport);
	}

	@Test
	void duplicateThatNeverDefinedAThresholdAffectsNothing() {
		when(repo.findBySourceActivityId("act_demoted")).thenReturn(List.of());
		assertThat(tasklet.affectedFields(change(Sport.BIKE))).isEmpty();
	}

	@Test
	void onlyFieldsTheDemotedActivityWasAMeasuredSourceForAreRebuilt() {
		when(repo.findBySourceActivityId("act_demoted")).thenReturn(List.of(
				row(ThresholdField.CRITICAL_RUN_POWER, ThresholdHistoryKind.MEASURED),
				row(ThresholdField.THRESHOLD_PACE, ThresholdHistoryKind.MANUAL)));
		assertThat(tasklet.affectedFields(change(Sport.RUN))).containsExactly(ThresholdField.CRITICAL_RUN_POWER);
	}

	@Test
	void fieldsOutsideTheActivitysSportAreIgnoredEvenIfALedgerRowExists() {
		when(repo.findBySourceActivityId("act_demoted"))
				.thenReturn(List.of(row(ThresholdField.FTP, ThresholdHistoryKind.MEASURED)));
		assertThat(tasklet.affectedFields(change(Sport.RUN))).isEmpty();
	}

	@Test
	void sportsWithNoThresholdsNeverTouchTheRepository() {
		assertThat(tasklet.affectedFields(change(Sport.SWIM))).isEmpty();
	}
}
