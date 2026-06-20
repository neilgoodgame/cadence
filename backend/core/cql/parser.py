import re

from core.exceptions import CQLParseError

from .fields import ENVIRONMENT_VALUES, FIELD_ALIASES, FIELD_SPECS, SPORT_VALUES, UNIT_FIELDS
from .tokenizer import tokenize

_OPERATORS = {">", "<", ">=", "<=", "=", "!="}
_VALUE_RE = re.compile(r"^([\d.]+)\s*([a-z%/]*)$")
_PACE_RE = re.compile(r"^\d+:\d{2}$")
_NON_ALPHA_RE = re.compile(r"[^a-z]")
_GUARD_LIMIT = 60


class ParseResult:
    def __init__(self, ast=None, order=None, empty=False):
        self.ast = ast
        self.order = order
        self.empty = empty


def _is_op(token):
    return token in _OPERATORS


def _singular(word):
    if len(word) > 3 and word.endswith("s"):
        return word[:-1]
    return word


def _field_key(token):
    return FIELD_ALIASES.get(token) or (token if token in FIELD_SPECS else None)


def parse_t(value):
    """ "M:SS" or "H:MM:SS" -> total seconds, mirroring the JS reference's parseT."""
    parts = [int(p) for p in value.split(":")]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    return parts[0] * 60 + parts[1]


def _parse_value(v):
    """Bare value with an optional unit suffix -> (numeric_value, inferred_field)."""
    if v is None:
        return None, None
    if _PACE_RE.match(v):
        return parse_t(v), "pace"
    m = _VALUE_RE.match(v)
    if not m:
        return None, None
    unit = _NON_ALPHA_RE.sub("", m.group(2))
    return float(m.group(1)), UNIT_FIELDS.get(unit)


class _Parser:
    def __init__(self, tokens):
        self._toks = tokens
        self._pos = 0

    def _peek(self):
        return self._toks[self._pos] if self._pos < len(self._toks) else None

    def _next(self):
        t = self._peek()
        self._pos += 1
        return t

    def parse_or(self):
        left = self.parse_and()
        while self._peek() == "or":
            self._next()
            right = self.parse_and()
            left = {"type": "or", "left": left, "right": right} if left else right
        return left

    def parse_and(self):
        left = self.parse_primary()
        while self._peek() is not None and self._peek() not in ("or", ")"):
            if self._peek() == "and":
                self._next()
            right = self.parse_primary()
            if right is None:
                continue
            left = {"type": "and", "left": left, "right": right} if left else right
        return left

    def parse_primary(self):
        guard = 0
        while guard < _GUARD_LIMIT:
            guard += 1
            t = self._peek()
            if t is None:
                return None
            if t == "(":
                self._next()
                expr = self.parse_or()
                if self._peek() == ")":
                    self._next()
                return expr
            if t in ("and", "or", ")"):
                return None
            if t in SPORT_VALUES:
                self._next()
                return {"type": "cmp", "field": "sport", "op": "=", "value": SPORT_VALUES[t]}
            if t in ENVIRONMENT_VALUES:
                self._next()
                return {"type": "cmp", "field": "environment", "op": "=", "value": ENVIRONMENT_VALUES[t]}
            if t == "tag":
                self._next()
                v = self._next()
                if v is None:
                    raise CQLParseError('Expected a tag name after "tag"')
                return {"type": "cmp", "field": "tag", "op": "=", "value": _singular(v)}
            if _is_op(t):
                op = self._next()
                v = self._next()
                if v is None:
                    raise CQLParseError(f'Expected a value after "{op}"')
                value, field = _parse_value(v)
                if not field:
                    raise CQLParseError(f'Add a unit (e.g. 140bpm, 10km) so I know which field "{v}" means')
                return {"type": "cmp", "field": field, "op": op, "value": value}
            f = _field_key(t)
            if f:
                self._next()
                op = "="
                if _is_op(self._peek()):
                    op = self._next()
                v = self._next()
                if v is None:
                    raise CQLParseError(f'Expected a value for "{t}"')
                if FIELD_SPECS[f]["num"]:
                    value, _unused = _parse_value(v)
                    return {
                        "type": "cmp",
                        "field": f,
                        "op": op,
                        "value": value if value is not None else float(v),
                    }
                value = SPORT_VALUES.get(v, v) if f == "sport" else _singular(v)
                return {"type": "cmp", "field": f, "op": op, "value": value}
            value, field = _parse_value(t)
            if field:
                self._next()
                return {"type": "cmp", "field": field, "op": "=", "value": value}
            self._next()  # unrecognized filler token - skip and keep looking
        return None


def parse(raw):
    tokens = tokenize(raw)
    if not tokens:
        return ParseResult(empty=True)

    order = None
    if "orderby" in tokens:
        oi = tokens.index("orderby")
        rest = tokens[oi + 1 :]
        tokens = tokens[:oi]
        field_word = rest[0] if rest else None
        f = _field_key(field_word) if field_word else None
        direction = "desc"
        if any(re.match(r"^asc", w) for w in rest):
            direction = "asc"
        if any(re.match(r"^desc", w) for w in rest):
            direction = "desc"
        if f:
            order = {"field": f, "dir": direction}
        elif field_word:
            raise CQLParseError(f'Unknown sort field "{field_word}"')

    parser = _Parser(tokens)
    ast = parser.parse_or()
    if ast is None and order is None:
        raise CQLParseError("Could not understand the query")
    return ParseResult(ast=ast, order=order)
