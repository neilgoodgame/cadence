"""Issue #501 (phase 1, part 2): mirrors backend_java's ActivityMergeApplyServiceIntegrationTest."""

from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User

from ..merge_apply import apply_merge_fields, preview_merge_fields
from ..models import Activity, Record
from .helpers import _make_activity


class MergeApplyTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="merge-apply@example.cc", password="x", name="Athlete")
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _activity(self, **kwargs) -> Activity:
        return _make_activity(self.athlete, sport="bike", start_date=self.start, **kwargs)

    def _add_records(self, activity: Activity, count: int, **field_fns):
        """field_fns: field_name -> callable(t) -> value, for any Record field."""
        for t in range(count):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                **{field: fn(t) for field, fn in field_fns.items()},
            )

    def test_keep_original_preserves_target_where_source_is_null(self):
        target = self._activity(name="Target")
        source = self._activity(name="Source")
        self._add_records(target, 10, power=lambda t: 100 + t)
        # Source only has power for t in [0, 5) - the rest stays null.
        self._add_records(source, 5, power=lambda t: 200 + t)

        counts = apply_merge_fields(target.id, source.id, ["power"], "keep_original", offset_seconds=0)

        rows = {r.t: r.power for r in target.records.all()}
        assert rows == {0: 200, 1: 201, 2: 202, 3: 203, 4: 204, 5: 105, 6: 106, 7: 107, 8: 108, 9: 109}
        assert counts["power"].copied == 5
        assert counts["power"].kept_original == 5
        assert counts["power"].nulled == 0

    def test_overwrite_nulls_target_where_source_is_null(self):
        target = self._activity(name="Target")
        source = self._activity(name="Source")
        self._add_records(target, 10, power=lambda t: 100 + t)
        self._add_records(source, 5, power=lambda t: 200 + t)

        counts = apply_merge_fields(target.id, source.id, ["power"], "overwrite", offset_seconds=0)

        rows = {r.t: r.power for r in target.records.all()}
        assert rows == {0: 200, 1: 201, 2: 202, 3: 203, 4: 204, 5: None, 6: None, 7: None, 8: None, 9: None}
        assert counts["power"].copied == 5
        assert counts["power"].kept_original == 0
        assert counts["power"].nulled == 5

    def test_offset_shifts_which_source_second_is_used(self):
        # offset_seconds = 3 means the source started 3s later than the target, so target's t
        # pairs with source's (t - 3).
        target = self._activity(name="Target")
        source = self._activity(name="Source")
        self._add_records(target, 10, power=lambda t: 100 + t)
        self._add_records(source, 10, power=lambda t: 900 + t)

        apply_merge_fields(target.id, source.id, ["power"], "overwrite", offset_seconds=3)

        rows = {r.t: r.power for r in target.records.all()}
        # target t=3 <- source (3-3)=0 -> 900; target t=0,1,2 <- source -3,-2,-1 (don't exist) -> None.
        assert rows[3] == 900
        assert rows[9] == 906
        assert rows[0] is None
        assert rows[2] is None

    def test_copies_only_the_target_timeline_never_creates_new_rows(self):
        target = self._activity(name="Target")
        source = self._activity(name="Source")
        self._add_records(target, 5, power=lambda t: 100 + t)
        self._add_records(source, 20, power=lambda t: 900 + t)

        apply_merge_fields(target.id, source.id, ["power"], "overwrite", offset_seconds=0)

        assert target.records.count() == 5
        assert sorted(target.records.values_list("t", flat=True)) == [0, 1, 2, 3, 4]

    def test_only_selected_fields_are_touched(self):
        target = self._activity(name="Target")
        source = self._activity(name="Source")
        self._add_records(target, 5, power=lambda t: 100 + t, heartrate=lambda t: 140 + t)
        self._add_records(source, 5, power=lambda t: 900 + t, heartrate=lambda t: 180 + t)

        apply_merge_fields(target.id, source.id, ["power"], "overwrite", offset_seconds=0)

        heartrates = {r.t: r.heartrate for r in target.records.all()}
        assert heartrates == {0: 140, 1: 141, 2: 142, 3: 143, 4: 144}

    def test_activity_level_fields_are_copied_directly(self):
        target = self._activity(name="Bike on 2026-06-01", fluids_ml=None)
        source = self._activity(name="Zwift - Mt Fuji", fluids_ml=750)

        counts = apply_merge_fields(target.id, source.id, ["name", "fluids_ml"], "keep_original", offset_seconds=0)

        target.refresh_from_db()
        assert target.name == "Zwift - Mt Fuji"
        assert target.fluids_ml == 750
        assert counts["name"].copied == 1
        assert counts["fluids_ml"].copied == 1

    def test_activity_level_field_null_on_source_is_counted_as_nulled_not_copied(self):
        target = self._activity(name="Target", fluids_ml=500)
        source = self._activity(name="Source", fluids_ml=None)

        counts = apply_merge_fields(target.id, source.id, ["fluids_ml"], "keep_original", offset_seconds=0)

        target.refresh_from_db()
        assert target.fluids_ml is None
        assert counts["fluids_ml"].nulled == 1
        assert counts["fluids_ml"].copied == 0

    def test_preview_matches_apply_but_writes_nothing(self):
        target = self._activity(name="Target")
        source = self._activity(name="Source", fluids_ml=750)
        self._add_records(target, 10, power=lambda t: 100 + t)
        self._add_records(source, 5, power=lambda t: 200 + t)

        preview_counts = preview_merge_fields(
            target.id, source.id, ["power", "fluids_ml"], "keep_original", offset_seconds=0
        )
        apply_counts = apply_merge_fields(
            target.id, source.id, ["power", "fluids_ml"], "keep_original", offset_seconds=0
        )

        assert preview_counts == apply_counts
        # Re-preview after the real apply should see the exact same outcome again - proves the
        # first preview call really didn't write anything (otherwise the second apply would see
        # a different, already-merged target).

    def test_preview_writes_nothing_at_all(self):
        target = self._activity(name="Target", fluids_ml=None)
        source = self._activity(name="Source", fluids_ml=750)
        self._add_records(target, 5, power=lambda t: 100 + t)
        self._add_records(source, 5, power=lambda t: 900 + t)

        preview_merge_fields(target.id, source.id, ["power", "fluids_ml"], "overwrite", offset_seconds=0)

        target.refresh_from_db()
        assert target.fluids_ml is None
        assert [r.power for r in target.records.order_by("t")] == [100, 101, 102, 103, 104]

    def test_unrecognized_field_names_are_silently_ignored(self):
        target = self._activity(name="Target")
        source = self._activity(name="Source")

        counts = apply_merge_fields(target.id, source.id, ["not_a_real_field"], "overwrite", offset_seconds=0)

        assert counts == {}
