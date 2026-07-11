from django.db.models import Q
from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework import status
from rest_framework.exceptions import PermissionDenied
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import get_effective_athlete_id
from core.exceptions import ConflictError
from core.permissions import user_may_read, user_may_write

from .models import Bike, Component, ServiceRecord, Shoe, ShoeModel, ShoeModelVersion
from .serializers import (
    BikeCreateSerializer,
    BikeDetailSerializer,
    BikeSerializer,
    BikeUpdateSerializer,
    ComponentCreateSerializer,
    ComponentSerializer,
    ComponentUpdateSerializer,
    ServiceRecordCreateSerializer,
    ServiceRecordSerializer,
    ShoeCatalogEntrySerializer,
    ShoeCreateSerializer,
    ShoeModelCreateSerializer,
    ShoeSerializer,
    ShoeUpdateSerializer,
)


def _display_name(manufacturer: str, model: str, version: str) -> str:
    parts = [manufacturer, model]
    if version:
        parts.append(version)
    return " ".join(parts)


def _require_read(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class BikeListCreateView(APIView):
    def get(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)
        bikes = Bike.objects.filter(athlete_id=athlete_id).order_by("-id")
        return Response({"data": BikeSerializer(bikes, many=True).data})

    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        serializer = BikeCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        bike = Bike.objects.create(athlete_id=athlete_id, **serializer.validated_data)
        return Response(BikeSerializer(bike).data, status=status.HTTP_201_CREATED)


class BikeDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        bike = get_object_or_404(Bike, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, bike.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response(BikeDetailSerializer(bike).data)

    def patch(self, request: Request, id: str) -> Response:
        bike = get_object_or_404(Bike, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, bike.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = BikeUpdateSerializer(bike, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return Response(BikeSerializer(bike).data)

    def delete(self, request: Request, id: str) -> Response:
        bike = get_object_or_404(Bike, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, bike.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        bike.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class ComponentListCreateView(APIView):
    def post(self, request: Request, id: str) -> Response:
        bike = get_object_or_404(Bike, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, bike.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = ComponentCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        component = Component.objects.create(bike=bike, **serializer.validated_data)
        return Response(ComponentSerializer(component).data, status=status.HTTP_201_CREATED)


class ComponentDetailView(APIView):
    def patch(self, request: Request, id: str) -> Response:
        component = get_object_or_404(Component, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, component.bike.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = ComponentUpdateSerializer(component, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return Response(ComponentSerializer(component).data)

    def delete(self, request: Request, id: str) -> Response:
        component = get_object_or_404(Component, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, component.bike.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        component.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class ComponentServiceView(APIView):
    def post(self, request: Request, id: str) -> Response:
        component = get_object_or_404(Component, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, component.bike.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = ServiceRecordCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        reset = data.get("reset", True)
        record = ServiceRecord.objects.create(
            component=component,
            action=data.get("action", ""),
            reset=reset,
            note=data.get("note", ""),
            date=data.get("date") or timezone.localdate(),
        )

        if reset:
            component.km = 0
            component.save(update_fields=["km"])

        return Response(ServiceRecordSerializer(record).data, status=status.HTTP_201_CREATED)


class ShoeListCreateView(APIView):
    def get(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)
        shoes = (
            Shoe.objects.filter(athlete_id=athlete_id, retired=False)
            .select_related("shoe_model_version", "shoe_model_version__shoe_model")
            .order_by("-id")
        )
        return Response({"data": ShoeSerializer(shoes, many=True).data})

    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        serializer = ShoeCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        shoe_model_version = get_object_or_404(
            ShoeModelVersion.objects.select_related("shoe_model"), pk=data["shoe_model_version_id"]
        )

        name = data.get("name") or (
            f"{shoe_model_version.shoe_model.manufacturer} {shoe_model_version.shoe_model.model} "
            f"{shoe_model_version.version} {data['colourway']}"
        )

        if Shoe.objects.filter(athlete_id=athlete_id, name=name).exists():
            raise ConflictError("A pair of shoes with that name already exists.")

        shoe = Shoe.objects.create(
            athlete_id=athlete_id,
            shoe_model_version=shoe_model_version,
            colourway=data["colourway"],
            name=name,
            limit_km=data.get("limit_km", 0),
            image=data.get("image"),
        )
        return Response(ShoeSerializer(shoe).data, status=status.HTTP_201_CREATED)


class ShoeDetailView(APIView):
    def patch(self, request: Request, id: str) -> Response:
        shoe = get_object_or_404(
            Shoe.objects.select_related("shoe_model_version", "shoe_model_version__shoe_model"), pk=id
        )
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, shoe.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = ShoeUpdateSerializer(shoe, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)

        name = serializer.validated_data.get("name")
        if name and Shoe.objects.filter(athlete_id=shoe.athlete_id, name=name).exclude(pk=shoe.pk).exists():
            raise ConflictError("A pair of shoes with that name already exists.")

        serializer.save()
        return Response(ShoeSerializer(shoe).data)

    def delete(self, request: Request, id: str) -> Response:
        shoe = get_object_or_404(Shoe, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, shoe.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        shoe.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class ShoeCatalogView(APIView):
    def get(self, request: Request) -> Response:
        q = request.query_params.get("q", "").strip()
        versions = ShoeModelVersion.objects.select_related("shoe_model").order_by(
            "shoe_model__manufacturer", "shoe_model__model"
        )
        if q:
            versions = versions.filter(Q(shoe_model__manufacturer__icontains=q) | Q(shoe_model__model__icontains=q))

        data = [
            {
                "shoe_model_version_id": version.id,
                "manufacturer": version.shoe_model.manufacturer,
                "model": version.shoe_model.model,
                "version": version.version,
                "display_name": _display_name(
                    version.shoe_model.manufacturer, version.shoe_model.model, version.version
                ),
            }
            for version in versions
        ]
        return Response({"data": ShoeCatalogEntrySerializer(data, many=True).data})

    def post(self, request: Request) -> Response:
        sub, _ = get_effective_athlete_id(request)
        serializer = ShoeModelCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        shoe_model = ShoeModel.objects.create(
            manufacturer=data["manufacturer"],
            model=data["model"],
            created_by_id=sub,
        )
        version = ShoeModelVersion.objects.create(shoe_model=shoe_model, version=data["version"])

        entry = {
            "shoe_model_version_id": version.id,
            "manufacturer": shoe_model.manufacturer,
            "model": shoe_model.model,
            "version": version.version,
            "display_name": _display_name(shoe_model.manufacturer, shoe_model.model, version.version),
        }
        return Response(ShoeCatalogEntrySerializer(entry).data, status=status.HTTP_201_CREATED)
