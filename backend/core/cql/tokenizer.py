import re

from .fields import STOP_WORDS

_QUOTED_RE = re.compile(r'"([^"]*)"')
_PLACEHOLDER_RE = re.compile(r"\x00(\d+)\x00")

# Order matters: each step runs as its own pass over the whole string, in the
# same sequence as the JS reference in Activities.dc.html, so that more
# specific phrases (e.g. "greater than or equal to") are consumed before the
# shorter phrases they contain (e.g. "greater than").
_PHRASE_REPLACEMENTS = [
    (r"average heart rate|avg heart rate|average hr|avg hr|heart rate|heartrate", " hr "),
    (r"max(imum)? heart rate|max hr", " maxhr "),
    (r"training load", " tss "),
    (r"order(ed)? by|sort(ed)? by", " orderby "),
    (r"tagged as|tagged|\btags\b|\btag\b|\blabelled\b|\blabeled\b", " tag "),
    (r"greater than or equal to|at least", " >= "),
    (r"less than or equal to|at most", " <= "),
    (r"not equal to|is not|isn't", " != "),
    (r"longer than|greater than|more than|bigger than|\blonger\b|\bover\b|\babove\b|\bgreater\b|\bmore\b", " > "),
    (r"shorter than|less than|fewer than|smaller than|\bshorter\b|\bunder\b|\bbelow\b|\bless\b|\bfewer\b", " < "),
    (r"equal to|equals|\bequal\b|\bis\b", " = "),
]


def normalize(raw: str) -> str:
    s = " " + raw.lower().replace(",", " ").replace(";", " ") + " "
    for pattern, replacement in _PHRASE_REPLACEMENTS:
        s = re.sub(pattern, replacement, s)
    # Protect multi-char operators behind placeholders before the single-char
    # passes below would otherwise tear them apart (">=" -> "> =").
    s = re.sub(r">=|=>", " ≥ ", s)
    s = re.sub(r"<=|=<", " ≤ ", s)
    s = re.sub(r"!=|<>", " ≠ ", s)
    s = re.sub(r"=", " = ", s)
    s = re.sub(r">", " > ", s)
    s = re.sub(r"<", " < ", s)
    s = re.sub(r"≥", " >= ", s)
    s = re.sub(r"≤", " <= ", s)
    s = re.sub(r"≠", " != ", s)
    return re.sub(r"\s+", " ", s).strip()


def tokenize(raw: str) -> list[str]:
    # Quoted phrases (e.g. `tag "Heat Training"`) are pulled out before normalize() runs,
    # so the phrase-replacement passes and stop-word filter below - built for loose
    # natural-language input - can't reach inside and mangle a literal multi-word value
    # (a tag named "Order By Distance" would otherwise get torn apart by the "order by"
    # phrase rule). Each quoted phrase collapses to exactly one token, spaces intact.
    quoted: list[str] = []

    def _stash(m: re.Match[str]) -> str:
        quoted.append(m.group(1).lower().strip())
        return f" \x00{len(quoted) - 1}\x00 "

    norm = normalize(_QUOTED_RE.sub(_stash, raw))
    if not norm:
        return []
    tokens = []
    for t in norm.split(" "):
        if not t or t in STOP_WORDS:
            continue
        m = _PLACEHOLDER_RE.fullmatch(t)
        tokens.append(quoted[int(m.group(1))] if m else t)
    return tokens
