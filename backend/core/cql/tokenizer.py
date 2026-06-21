import re

from .fields import STOP_WORDS

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
    norm = normalize(raw)
    if not norm:
        return []
    return [t for t in norm.split(" ") if t and t not in STOP_WORDS]
