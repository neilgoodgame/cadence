from typing import Any

from django.db.models import Q
from django.http import HttpResponse
from django.shortcuts import get_object_or_404
from django.utils import timezone
from django.utils.dateparse import parse_date
from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from accounts.models import User
from core.auth_context import authenticated_user, get_effective_athlete_id
from core.exceptions import ConflictError, PayloadTooLargeError
from core.permissions import user_is_admin, user_may_read, user_may_write

from .models import Bike, Component, ServiceRecord, Shoe, ShoeModel, ShoeModelVersion, ShoePhoto
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
    ShoeImportResultSerializer,
    ShoeImportSerializer,
    ShoeModelCreateSerializer,
    ShoePhotoSerializer,
    ShoeSerializer,
    ShoeUpdateSerializer,
)

# A phone camera JPEG/HEIC is typically 2-8MB - generous enough for that, while still
# bounding growth of this table (stored as a blob directly in Postgres, see ShoePhoto's
# own docstring for why that's an acceptable tradeoff at this app's scale).
MAX_SHOE_PHOTO_BYTES = 8 * 1024 * 1024

ALLOWED_SHOE_PHOTO_CONTENT_TYPES = {"image/jpeg", "image/png", "image/heic", "image/heif"}


def _to_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


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

        if "limit_km" in data:
            limit_km = data["limit_km"]
        else:
            limit_km = User.objects.values_list("default_shoe_limit_km", flat=True).get(pk=athlete_id)

        shoe = Shoe.objects.create(
            athlete_id=athlete_id,
            shoe_model_version=shoe_model_version,
            colourway=data["colourway"],
            name=name,
            limit_km=limit_km,
            image=data.get("image"),
        )
        return Response(ShoeSerializer(shoe).data, status=status.HTTP_201_CREATED)


class ShoeImportView(APIView):
    """Bulk-add gear from a CSV (e.g. a Strava shoe-rotation export), parsed client-side.

    A non-admin's import only adds pairs whose manufacturer+model+version already exists in the
    shared catalog - rows that don't match are skipped rather than silently polluting the shared
    catalog with whatever an arbitrary CSV happens to contain. An admin's import may also create
    the missing catalog model/version first, same as the admin shoe-catalog screen's own bulk
    import (AdminShoeCatalogImportView) - "admin" here is always the real signed-in principal,
    never a delegated athlete, matching AccessGuard.requireAdmin's Java equivalent.

    Idempotent re-upload: a (shoe_model_version, colourway) pair already present in the athlete's
    gear is skipped too, so re-importing the same file twice doesn't create duplicates.
    """

    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)
        is_admin = user_is_admin(authenticated_user(request))
        default_limit_km = User.objects.values_list("default_shoe_limit_km", flat=True).get(pk=athlete_id)

        serializer = ShoeImportSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        entries = serializer.validated_data["entries"]

        shoes_created = 0
        catalog_models_created = 0
        catalog_versions_created = 0
        skipped_no_catalog_match = 0
        skipped_already_in_gear = 0

        for entry in entries:
            manufacturer = entry["manufacturer"]
            model = entry["model"]
            version = entry["version"]
            colourway = entry["colourway"]
            distance_km = entry["distance_km"]

            shoe_model = ShoeModel.objects.filter(manufacturer__iexact=manufacturer, model__iexact=model).first()
            is_new_model = shoe_model is None
            smv = (
                None
                if shoe_model is None
                else ShoeModelVersion.objects.filter(shoe_model=shoe_model, version__iexact=version).first()
            )

            if smv is None:
                if not is_admin:
                    skipped_no_catalog_match += 1
                    continue
                if is_new_model:
                    shoe_model = ShoeModel.objects.create(
                        manufacturer=manufacturer, model=model, created_by_id=athlete_id
                    )
                    catalog_models_created += 1
                smv = ShoeModelVersion.objects.create(shoe_model=shoe_model, version=version)
                catalog_versions_created += 1

            if Shoe.objects.filter(athlete_id=athlete_id, shoe_model_version=smv, colourway__iexact=colourway).exists():
                skipped_already_in_gear += 1
                continue

            base_name = " ".join(part for part in (manufacturer, model, version, colourway) if part)
            name = base_name
            disambiguator = 2
            while Shoe.objects.filter(athlete_id=athlete_id, name__iexact=name).exists():
                name = f"{base_name} ({disambiguator})"
                disambiguator += 1

            Shoe.objects.create(
                athlete_id=athlete_id,
                shoe_model_version=smv,
                colourway=colourway,
                name=name,
                km=round(distance_km) if distance_km is not None else 0,
                limit_km=default_limit_km,
            )
            shoes_created += 1

        result = {
            "shoes_created": shoes_created,
            "catalog_models_created": catalog_models_created,
            "catalog_versions_created": catalog_versions_created,
            "skipped_no_catalog_match": skipped_no_catalog_match,
            "skipped_already_in_gear": skipped_already_in_gear,
        }
        return Response(ShoeImportResultSerializer(result).data, status=status.HTTP_201_CREATED)


class ShoePhotoListCreateView(APIView):
    def get(self, request: Request, id: str) -> Response:
        shoe = get_object_or_404(Shoe, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, shoe.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        photos = shoe.photos.all()
        return Response({"data": ShoePhotoSerializer(photos, many=True).data})

    def post(self, request: Request, id: str) -> Response:
        shoe = get_object_or_404(Shoe, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, shoe.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        image = request.FILES.get("image")
        if image is None:
            raise ValidationError({"image": "This field is required."})
        if image.content_type not in ALLOWED_SHOE_PHOTO_CONTENT_TYPES:
            raise ValidationError({"image": "Must be a JPEG, PNG, HEIC or HEIF image."})
        if image.size > MAX_SHOE_PHOTO_BYTES:
            raise PayloadTooLargeError(f"Images must be {MAX_SHOE_PHOTO_BYTES // (1024 * 1024)}MB or smaller.")

        taken_on_raw = request.data.get("taken_on")
        taken_on = parse_date(taken_on_raw) if taken_on_raw else timezone.localdate()
        if taken_on is None:
            raise ValidationError({"taken_on": "Must be a valid date (YYYY-MM-DD)."})

        km_raw = request.data.get("km")
        km = _to_int(km_raw) if km_raw not in (None, "") else shoe.km
        if km is None:
            raise ValidationError({"km": "Must be a whole number."})

        photo = ShoePhoto.objects.create(
            shoe=shoe,
            image=image.read(),
            content_type=image.content_type,
            taken_on=taken_on,
            km=km,
            notes=request.data.get("notes", ""),
        )
        return Response(ShoePhotoSerializer(photo).data, status=status.HTTP_201_CREATED)


class ShoePhotoImageView(APIView):
    def get(self, request: Request, id: str) -> HttpResponse:
        photo = get_object_or_404(ShoePhoto.objects.select_related("shoe"), pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, photo.shoe.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return HttpResponse(bytes(photo.image), content_type=photo.content_type)


class ShoePhotoDetailView(APIView):
    def delete(self, request: Request, id: str) -> Response:
        photo = get_object_or_404(ShoePhoto.objects.select_related("shoe"), pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, photo.shoe.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        photo.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


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
