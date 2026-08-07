from django.db.models import Count, Q
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from accounts.models import User, UserRelationship
from core.auth_context import authenticated_user
from core.exceptions import ConflictError
from core.permissions import user_is_admin
from gear.models import Shoe, ShoeModel, ShoeModelVersion

from .models import CatalogAuditLogEntry
from .serializers import (
    AdminRelationshipSerializer,
    AdminShoeCatalogCreateSerializer,
    AdminShoeCatalogEntrySerializer,
    AdminShoeVersionCreateSerializer,
    AdminUserSerializer,
    AdminUserUpdateSerializer,
    CatalogAuditLogEntrySerializer,
)


def _require_admin(request: Request) -> User:
    user = authenticated_user(request)
    if not user_is_admin(user):
        raise PermissionDenied("This action requires admin access.")
    return user


def _catalog_entry(shoe_model: ShoeModel) -> dict:
    # usage_count counts every Shoe referencing that version regardless of its retired flag,
    # matching the delete-block check below exactly - it should read as "why can't I delete
    # this", not "how many *active* shoes use it".
    versions = [
        {"version": v.version, "usage_count": v.usage_count}
        for v in shoe_model.versions.annotate(usage_count=Count("shoes")).order_by("version")
    ]
    return {
        "id": shoe_model.id,
        "manufacturer": shoe_model.manufacturer,
        "model": shoe_model.model,
        "versions": versions,
        "added_by": shoe_model.created_by.name if shoe_model.created_by_id else None,
    }


def _append_version(shoe_model: ShoeModel, version: str, admin: User) -> ShoeModelVersion:
    """Shared by the catalog create-or-append endpoint and the dedicated "+" (add
    version) endpoint, so the dedup check and audit-log write live in exactly one place.
    """
    if ShoeModelVersion.objects.filter(shoe_model=shoe_model, version__iexact=version).exists():
        raise ConflictError("This shoe model already has that version.")
    created = ShoeModelVersion.objects.create(shoe_model=shoe_model, version=version)
    CatalogAuditLogEntry.objects.create(
        description=f"{shoe_model.manufacturer} {shoe_model.model} v{version}",
        action=CatalogAuditLogEntry.ACTION_ADDED,
        by=admin,
    )
    return created


class AdminShoeCatalogListCreateView(APIView):
    def get(self, request: Request) -> Response:
        _require_admin(request)
        q = request.query_params.get("q", "").strip()
        models_qs = ShoeModel.objects.select_related("created_by").order_by("manufacturer", "model")
        if q:
            models_qs = models_qs.filter(Q(manufacturer__icontains=q) | Q(model__icontains=q))
        data = [_catalog_entry(m) for m in models_qs]
        return Response({"data": AdminShoeCatalogEntrySerializer(data, many=True).data})

    def post(self, request: Request) -> Response:
        admin = _require_admin(request)
        serializer = AdminShoeCatalogCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        existing = ShoeModel.objects.filter(
            manufacturer__iexact=data["manufacturer"], model__iexact=data["model"]
        ).first()
        if existing:
            _append_version(existing, data["version"], admin)
            return Response(AdminShoeCatalogEntrySerializer(_catalog_entry(existing)).data, status=201)

        shoe_model = ShoeModel.objects.create(manufacturer=data["manufacturer"], model=data["model"], created_by=admin)
        ShoeModelVersion.objects.create(shoe_model=shoe_model, version=data["version"])
        CatalogAuditLogEntry.objects.create(
            description=f"{shoe_model.manufacturer} {shoe_model.model} v{data['version']}",
            action=CatalogAuditLogEntry.ACTION_ADDED,
            by=admin,
        )
        return Response(AdminShoeCatalogEntrySerializer(_catalog_entry(shoe_model)).data, status=201)


class AdminShoeCatalogVersionsView(APIView):
    def post(self, request: Request, id: str) -> Response:
        admin = _require_admin(request)
        shoe_model = get_object_or_404(ShoeModel, pk=id)
        serializer = AdminShoeVersionCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        _append_version(shoe_model, serializer.validated_data["version"], admin)
        return Response(AdminShoeCatalogEntrySerializer(_catalog_entry(shoe_model)).data, status=201)


class AdminShoeCatalogDetailView(APIView):
    def delete(self, request: Request, id: str) -> Response:
        admin = _require_admin(request)
        shoe_model = get_object_or_404(ShoeModel, pk=id)
        if Shoe.objects.filter(shoe_model_version__shoe_model=shoe_model).exists():
            raise ConflictError("This shoe model is still in use by athletes' gear.")
        description = f"{shoe_model.manufacturer} {shoe_model.model}"
        shoe_model.delete()
        CatalogAuditLogEntry.objects.create(
            description=description, action=CatalogAuditLogEntry.ACTION_REMOVED, by=admin
        )
        return Response(status=204)


class AdminUserListView(APIView):
    def get(self, request: Request) -> Response:
        _require_admin(request)
        q = request.query_params.get("q", "").strip()
        users = User.objects.all().order_by("-date_joined")
        if q:
            users = users.filter(Q(name__icontains=q) | Q(email__icontains=q))
        return Response({"data": AdminUserSerializer(users, many=True).data})


class AdminUserDetailView(APIView):
    def patch(self, request: Request, id: str) -> Response:
        _require_admin(request)
        user = get_object_or_404(User, pk=id)
        serializer = AdminUserUpdateSerializer(data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        update_fields = list(serializer.validated_data.keys())
        for field, value in serializer.validated_data.items():
            setattr(user, field, value)
        if update_fields:
            user.save(update_fields=update_fields)
        return Response(AdminUserSerializer(user).data)


class AdminRelationshipListView(APIView):
    def get(self, request: Request) -> Response:
        _require_admin(request)
        relationships = UserRelationship.objects.select_related("owner", "grantee").order_by("-created")
        data = [
            {
                "id": r.id,
                "coach_name": r.grantee.name,
                "athlete_name": r.owner.name,
                "role": r.role,
                "granted": r.created,
            }
            for r in relationships
        ]
        return Response({"data": AdminRelationshipSerializer(data, many=True).data})


class AdminRelationshipDetailView(APIView):
    def delete(self, request: Request, id: str) -> Response:
        _require_admin(request)
        relationship = get_object_or_404(UserRelationship, pk=id)
        relationship.delete()
        return Response(status=204)


class AdminAuditLogView(APIView):
    def get(self, request: Request) -> Response:
        _require_admin(request)
        entries = CatalogAuditLogEntry.objects.select_related("by").all()
        data = [
            {
                "id": e.id,
                "description": e.description,
                "action": e.action,
                "by": e.by.name if e.by_id else None,
                "created": e.created,
            }
            for e in entries
        ]
        return Response({"data": CatalogAuditLogEntrySerializer(data, many=True).data})
