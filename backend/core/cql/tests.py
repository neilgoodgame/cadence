from django.db.models import Q
from django.test import SimpleTestCase

from core.exceptions import CQLParseError

from . import compile_ast_to_q, parse, resolve_order_by
from .tokenizer import normalize, tokenize


class NormalizeTests(SimpleTestCase):
    def test_phrase_synonyms_become_field_tokens(self):
        self.assertIn("hr", normalize("average heart rate").split())
        self.assertIn("hr", normalize("avg hr").split())
        self.assertIn("maxhr", normalize("max hr").split())
        self.assertIn("tss", normalize("training load").split())

    def test_comparison_phrases_become_operators(self):
        self.assertIn(">=", normalize("at least 140bpm").split())
        self.assertIn("<=", normalize("at most 140bpm").split())
        self.assertIn(">", normalize("longer than 30 mins").split())
        self.assertIn("<", normalize("shorter than 30 mins").split())
        self.assertIn("!=", normalize("hr is not 140bpm").split())

    def test_symbolic_operators_get_padded_not_split(self):
        tokens = normalize("hr>=140bpm").split()
        self.assertIn(">=", tokens)
        self.assertNotIn(">", tokens)
        self.assertNotIn("=", tokens)

    def test_commas_and_semicolons_become_separators(self):
        tokens = normalize("run, indoor; tag race").split()
        self.assertIn("run", tokens)
        self.assertIn("indoor", tokens)

    def test_order_by_synonyms_normalize(self):
        self.assertIn("orderby", normalize("sorted by distance").split())
        self.assertIn("orderby", normalize("order by distance").split())

    def test_max_heart_rate_unabbreviated_does_not_collapse(self):
        # Reference quirk (Activities.dc.html qNormalize): the "heart rate" -> "hr"
        # rule runs before the "max hr" -> "maxhr" rule, so it eats the "heart
        # rate" substring first and leaves "max" and "hr" as separate tokens.
        # Only the pre-abbreviated "max hr" collapses correctly. Ported as-is.
        tokens = normalize("max heart rate").split()
        self.assertEqual(tokens, ["max", "hr"])

    def test_parens_without_surrounding_spaces_stick_to_words(self):
        # Reference quirk: qNormalize never pads "(" / ")" with spaces, so
        # "(indoor" and "outdoor)" survive tokenization as single tokens and
        # grouping only works if the query text already has spaces around
        # the parens. Ported as-is.
        tokens = normalize("(indoor or outdoor)").split()
        self.assertIn("(indoor", tokens)
        self.assertIn("outdoor)", tokens)


class TokenizeTests(SimpleTestCase):
    def test_stopwords_are_filtered(self):
        tokens = tokenize("show me all my runs")
        self.assertNotIn("show", tokens)
        self.assertNotIn("me", tokens)
        self.assertNotIn("all", tokens)
        self.assertNotIn("my", tokens)
        self.assertIn("runs", tokens)

    def test_empty_query_tokenizes_to_empty_list(self):
        self.assertEqual(tokenize(""), [])
        self.assertEqual(tokenize("   "), [])


class ParseTests(SimpleTestCase):
    def test_empty_query_is_empty_result(self):
        result = parse("")
        self.assertTrue(result.empty)
        self.assertIsNone(result.ast)

    def test_simple_field_comparison(self):
        result = parse("hr > 140bpm")
        self.assertEqual(result.ast, {"type": "cmp", "field": "hr", "op": ">", "value": 140.0})

    def test_bare_value_with_unit_infers_field(self):
        result = parse("over 140bpm")
        self.assertEqual(result.ast["field"], "hr")
        self.assertEqual(result.ast["op"], ">")
        self.assertEqual(result.ast["value"], 140.0)

    def test_bare_sport_word(self):
        result = parse("running")
        self.assertEqual(result.ast, {"type": "cmp", "field": "sport", "op": "=", "value": "run"})

    def test_bare_environment_word(self):
        result = parse("outdoors")
        self.assertEqual(result.ast, {"type": "cmp", "field": "environment", "op": "=", "value": "outdoor"})

    def test_tag_field(self):
        result = parse("tag race")
        self.assertEqual(result.ast, {"type": "cmp", "field": "tag", "op": "=", "value": "race"})

    def test_tag_synonym_phrases_normalize_too(self):
        result = parse("tagged as long-run")
        self.assertEqual(result.ast["field"], "tag")
        self.assertEqual(result.ast["value"], "long-run")

    def test_and_chaining(self):
        result = parse("running and indoor")
        self.assertEqual(result.ast["type"], "and")
        self.assertEqual(result.ast["left"], {"type": "cmp", "field": "sport", "op": "=", "value": "run"})
        self.assertEqual(
            result.ast["right"], {"type": "cmp", "field": "environment", "op": "=", "value": "indoor"}
        )

    def test_implicit_and_without_keyword(self):
        result = parse("running indoor")
        self.assertEqual(result.ast["type"], "and")

    def test_or_binds_looser_than_and(self):
        result = parse("running and indoor or swimming")
        self.assertEqual(result.ast["type"], "or")
        self.assertEqual(result.ast["left"]["type"], "and")
        self.assertEqual(result.ast["right"], {"type": "cmp", "field": "sport", "op": "=", "value": "swim"})

    def test_parentheses_group_explicitly(self):
        # Parens must be space-separated from neighboring words - see the
        # tokenizer quirk test documenting why "(indoor" glued together fails.
        result = parse("running and ( indoor or outdoor )")
        self.assertEqual(result.ast["type"], "and")
        self.assertEqual(result.ast["right"]["type"], "or")

    def test_pace_value_parses_as_seconds(self):
        result = parse("pace < 5:30")
        self.assertEqual(result.ast["field"], "pace")
        self.assertEqual(result.ast["value"], 5 * 60 + 30)

    def test_distance_with_unit(self):
        result = parse("distance > 42.2km")
        self.assertEqual(result.ast, {"type": "cmp", "field": "distance", "op": ">", "value": 42.2})

    def test_natural_language_comparison_phrase(self):
        result = parse("hr greater than 140bpm")
        self.assertEqual(result.ast["op"], ">")
        self.assertEqual(result.ast["value"], 140.0)

    def test_order_by_extracted_and_stripped_from_ast(self):
        result = parse("running order by distance")
        self.assertEqual(result.ast, {"type": "cmp", "field": "sport", "op": "=", "value": "run"})
        self.assertEqual(result.order, {"field": "distance", "dir": "desc"})

    def test_order_by_asc_direction(self):
        result = parse("order by distance asc")
        self.assertEqual(result.order, {"field": "distance", "dir": "asc"})

    def test_order_by_with_no_filter_is_still_valid(self):
        result = parse("order by tss desc")
        self.assertIsNone(result.ast)
        self.assertEqual(result.order, {"field": "tss", "dir": "desc"})

    def test_unknown_sort_field_raises(self):
        with self.assertRaises(CQLParseError):
            parse("order by bananas")

    def test_dangling_operator_without_value_raises(self):
        with self.assertRaises(CQLParseError):
            parse("hr >")

    def test_named_field_comparison_tolerates_missing_unit(self):
        result = parse("hr > 140")
        self.assertEqual(result.ast, {"type": "cmp", "field": "hr", "op": ">", "value": 140.0})

    def test_bare_operator_without_unit_raises(self):
        with self.assertRaises(CQLParseError):
            parse("over 140")

    def test_nonsense_query_raises(self):
        with self.assertRaises(CQLParseError):
            parse("the quick brown fox")


class CompilerTests(SimpleTestCase):
    FIELD_MAP = {
        "hr": "avg_hr",
        "distance": "distance_m",
        "duration": "moving_time_s",
        "sport": "sport",
        "environment": "environment",
        "name": "name",
    }

    def test_simple_numeric_gt(self):
        ast = parse("hr > 140bpm").ast
        q = compile_ast_to_q(ast, self.FIELD_MAP)
        self.assertEqual(q, Q(avg_hr__gt=140.0))

    def test_numeric_operators_map_to_lookups(self):
        cases = {
            "=": "exact",
            ">": "gt",
            "<": "lt",
            ">=": "gte",
            "<=": "lte",
        }
        for op, lookup in cases.items():
            ast = {"type": "cmp", "field": "hr", "op": op, "value": 140}
            q = compile_ast_to_q(ast, self.FIELD_MAP)
            self.assertEqual(q, Q(**{f"avg_hr__{lookup}": 140}))

    def test_not_equal_negates(self):
        ast = {"type": "cmp", "field": "sport", "op": "!=", "value": "run"}
        q = compile_ast_to_q(ast, self.FIELD_MAP)
        self.assertEqual(q, ~Q(sport__exact="run"))

    def test_name_uses_icontains(self):
        ast = {"type": "cmp", "field": "name", "op": "=", "value": "morning"}
        q = compile_ast_to_q(ast, self.FIELD_MAP)
        self.assertEqual(q, Q(name__icontains="morning"))

    def test_and_or_compose(self):
        ast = parse("running and indoor").ast
        q = compile_ast_to_q(ast, self.FIELD_MAP)
        self.assertEqual(q, Q(sport__exact="run") & Q(environment__exact="indoor"))

    def test_tag_requires_callback(self):
        ast = {"type": "cmp", "field": "tag", "op": "=", "value": "race"}
        with self.assertRaises(CQLParseError):
            compile_ast_to_q(ast, self.FIELD_MAP)

    def test_tag_uses_provided_callback(self):
        ast = {"type": "cmp", "field": "tag", "op": "=", "value": "race"}
        q = compile_ast_to_q(ast, self.FIELD_MAP, tag_filter=lambda v: Q(tags__name=v))
        self.assertEqual(q, Q(tags__name="race"))

    def test_tag_not_equal_negates_callback_result(self):
        ast = {"type": "cmp", "field": "tag", "op": "!=", "value": "race"}
        q = compile_ast_to_q(ast, self.FIELD_MAP, tag_filter=lambda v: Q(tags__name=v))
        self.assertEqual(q, ~Q(tags__name="race"))

    def test_unfilterable_field_raises(self):
        ast = {"type": "cmp", "field": "power", "op": "=", "value": 200}
        with self.assertRaises(CQLParseError):
            compile_ast_to_q(ast, self.FIELD_MAP)

    def test_none_ast_compiles_to_empty_q(self):
        self.assertEqual(compile_ast_to_q(None, self.FIELD_MAP), Q())


class ResolveOrderByTests(SimpleTestCase):
    FIELD_MAP = {"distance": "distance_m", "tss": "tss"}

    def test_desc_prefixes_with_minus(self):
        self.assertEqual(resolve_order_by({"field": "distance", "dir": "desc"}, self.FIELD_MAP), "-distance_m")

    def test_asc_has_no_prefix(self):
        self.assertEqual(resolve_order_by({"field": "tss", "dir": "asc"}, self.FIELD_MAP), "tss")

    def test_none_order_returns_none(self):
        self.assertIsNone(resolve_order_by(None, self.FIELD_MAP))

    def test_unsortable_field_raises(self):
        with self.assertRaises(CQLParseError):
            resolve_order_by({"field": "power", "dir": "desc"}, self.FIELD_MAP)
