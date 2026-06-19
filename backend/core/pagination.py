from rest_framework.pagination import CursorPagination
from rest_framework.response import Response


class CadenceCursorPagination(CursorPagination):
    """Cursor pagination matching the spec's List envelope.

    {"object": "list", "has_more": bool, "next_cursor": str|null, "data": [...]}
    """

    page_size = 50
    max_page_size = 200
    page_size_query_param = "limit"
    cursor_query_param = "cursor"
    ordering = "-id"

    def get_paginated_response(self, data):
        next_cursor = self._extract_cursor_param(self.get_next_link())
        return Response(
            {
                "object": "list",
                "has_more": next_cursor is not None,
                "next_cursor": next_cursor,
                "data": data,
            }
        )

    def _extract_cursor_param(self, url):
        if not url:
            return None
        from urllib.parse import urlparse, parse_qs

        query = parse_qs(urlparse(url).query)
        values = query.get(self.cursor_query_param)
        return values[0] if values else None
