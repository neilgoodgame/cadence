from .fit import parse_fit
from .gpx import parse_gpx
from .tcx import parse_tcx


class UnsupportedFileType(Exception):
    pass


def parse_file(path, filename):
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    if ext == "fit":
        return parse_fit(path)
    if ext == "gpx":
        return parse_gpx(path)
    if ext == "tcx":
        return parse_tcx(path)
    raise UnsupportedFileType(f"Unsupported file extension '.{ext}'.")
