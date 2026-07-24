from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.exceptions import PermissionDenied
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write

from .models import Race
from .serializers import RaceCreateSerializer, RaceSerializer, RaceUpdateSerializer


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
        races = Race.objects.filter(athlete_id=athlete_id)
        return Response({"data": RaceSerializer(races, many=True).data})

    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)
        serializer = RaceCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        race = Race.objects.create(athlete_id=athlete_id, **serializer.validated_data)
        return Response(RaceSerializer(race).data, status=status.HTTP_201_CREATED)


class RaceDetailView(APIView):
    def patch(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, race.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        serializer = RaceUpdateSerializer(race, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return Response(RaceSerializer(race).data)

    def delete(self, request: Request, id: str) -> Response:
        race = get_object_or_404(Race, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, race.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        race.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)
