from .fit import parse_fit
from .gpx import parse_gpx
from .tcx import parse_tcx
from .types import ParsedActivity

__all__ = ["ParsedActivity", "UnsupportedFileType", "parse_file"]


class UnsupportedFileType(Exception):
    pass


def parse_file(path: str, filename: str) -> list[ParsedActivity]:
    """Almost always a single-element list; a multisport FIT file yields the
    parent activity first followed by one child per sport/transition leg.
    """
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    if ext == "fit":
        return parse_fit(path)
    if ext == "gpx":
        return [parse_gpx(path)]
    if ext == "tcx":
        return [parse_tcx(path)]
    raise UnsupportedFileType(f"Unsupported file extension '.{ext}'.")
