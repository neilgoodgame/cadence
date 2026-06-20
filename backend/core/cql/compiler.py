from collections.abc import Callable

from django.db.models import Q

from core.exceptions import CQLParseError

from .fields import FIELD_SPECS
from .parser import CQLNode

_NUMERIC_LOOKUPS = {"=": "exact", "!=": "exact", ">": "gt", "<": "lt", ">=": "gte", "<=": "lte"}


def compile_ast_to_q(ast: CQLNode | None, field_map: dict[str, str], tag_filter: Callable[[str], Q] | None = None) -> Q:
    """Compiles a CQL AST (see parser.parse) into a Django Q object.

    field_map maps CQL field keys (hr, distance, sport, ...) to the ORM
    lookup path on the caller's model, e.g. {"hr": "avg_hr", "distance": "distance_m"}.
    tag_filter, if the caller supports tag filtering, is a callable(value) -> Q
    that builds a containment check (e.g. an EXISTS subquery against the
    activity's tags) so tag filters compose correctly under arbitrary
    AND/OR/NOT nesting instead of needing a `.distinct()` join.
    """
    if ast is None:
        return Q()
    if ast["type"] == "and":
        return compile_ast_to_q(ast["left"], field_map, tag_filter) & compile_ast_to_q(
            ast["right"], field_map, tag_filter
        )
    if ast["type"] == "or":
        return compile_ast_to_q(ast["left"], field_map, tag_filter) | compile_ast_to_q(
            ast["right"], field_map, tag_filter
        )

    field, op, value = ast["field"], ast["op"], ast["value"]
    spec = FIELD_SPECS.get(field)
    if spec is None:
        raise CQLParseError(f'Unknown field "{field}"')

    if field == "tag":
        if tag_filter is None:
            raise CQLParseError("Tag filtering is not available here")
        q = tag_filter(value)
        return ~q if op == "!=" else q

    orm_field = field_map.get(field)
    if orm_field is None:
        raise CQLParseError(f'Field "{field}" is not filterable here')

    if spec["num"]:
        lookup = _NUMERIC_LOOKUPS[op]
        q = Q(**{f"{orm_field}__{lookup}": value})
        return ~q if op == "!=" else q

    if field == "name":
        q = Q(**{f"{orm_field}__icontains": value})
        return ~q if op == "!=" else q

    q = Q(**{f"{orm_field}__exact": value})
    return ~q if op == "!=" else q


def resolve_order_by(order: dict[str, str] | None, field_map: dict[str, str]) -> str | None:
    if order is None:
        return None
    orm_field = field_map.get(order["field"])
    if orm_field is None:
        raise CQLParseError(f'Field "{order["field"]}" is not sortable here')
    return f"-{orm_field}" if order["dir"] == "desc" else orm_field
