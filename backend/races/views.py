from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from athletes import threshold_suggestions
from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write

from . import splits as splits_module
from .blocks import get_race_block
from .comments import resolve_parent_comment, snapshot_role
from .models import Race, RaceComment
from .serializers import (
    RaceCommentCreateSerializer,
    RaceCommentSerializer,
    RaceCreateSerializer,
    RaceSerializer,
    RaceUpdateSerializer,
)

SPLITS_MODES = ("laps", "1", "5")


def _require_read(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class RaceListCreateView(APIView):
    def get(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)
        races = Race.objects.filter(athlete_id=athlete_id).select_related("athlete")
        return Response({"data": RaceSerializer(races, many=True).data})

    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)
        serializer = RaceCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        race = Race.objects.create(athlete_id=athlete_id, **serializer.validated_data)
        threshold_suggestions.invalidate(athlete_id)
        return Response(RaceSerializer(race).data, status=status.HTTP_201_CREATED)


class RaceDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, race.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response(RaceSerializer(race).data)

    def patch(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, race.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        serializer = RaceUpdateSerializer(race, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        threshold_suggestions.invalidate(race.athlete_id)
        return Response(RaceSerializer(race).data)

    def delete(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, race.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        athlete_id = race.athlete_id
        race.delete()
        threshold_suggestions.invalidate(athlete_id)
        return Response(status=status.HTTP_204_NO_CONTENT)


class RaceBlockView(APIView):
    def get(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, race.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response(get_race_block(race))


class RaceSplitsView(APIView):
    def get(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, race.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        mode = request.query_params.get("mode", "laps")
        if mode not in SPLITS_MODES:
            raise ValidationError({"mode": f"Must be one of {', '.join(SPLITS_MODES)}."})
        return Response(splits_module.get_splits(race, mode))


class RaceCommentListView(APIView):
    """Coach/athlete race-plan comments - gated by the same self-or-coach-with-grant
    permission as the rest of the races resource (unlike activity comments, which any
    viewer can post to), matching RaceComment.role's athlete/coach-only choices."""

    def get(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, race.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        comments = race.comments.select_related("author").order_by("created_at")
        return Response({"data": RaceCommentSerializer(comments, many=True).data})

    def post(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, race.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = RaceCommentCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        parent = resolve_parent_comment(race, serializer.validated_data.get("parent_id"))

        comment = RaceComment.objects.create(
            race=race,
            author_id=sub,
            role=snapshot_role(race, sub),
            parent=parent,
            text=serializer.validated_data["text"],
            week=serializer.validated_data.get("week"),
        )
        return Response(RaceCommentSerializer(comment).data, status=status.HTTP_201_CREATED)


class RaceCommentDetailView(APIView):
    def delete(self, request: Request, id: str, comment_id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, race.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        comment = get_object_or_404(RaceComment, pk=comment_id, race=race)
        if comment.author_id != sub:
            raise PermissionDenied("You can only delete your own comments.")
        comment.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)
