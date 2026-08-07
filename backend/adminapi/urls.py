from django.urls import path

from .views import (
    AdminAuditLogView,
    AdminRelationshipDetailView,
    AdminRelationshipListView,
    AdminShoeCatalogDetailView,
    AdminShoeCatalogListCreateView,
    AdminShoeCatalogVersionsView,
    AdminUserDetailView,
    AdminUserListView,
)

urlpatterns = [
    path("v1/admin/shoe-catalog", AdminShoeCatalogListCreateView.as_view(), name="admin-shoe-catalog"),
    path("v1/admin/shoe-catalog/<str:id>", AdminShoeCatalogDetailView.as_view(), name="admin-shoe-catalog-detail"),
    path(
        "v1/admin/shoe-catalog/<str:id>/versions",
        AdminShoeCatalogVersionsView.as_view(),
        name="admin-shoe-catalog-versions",
    ),
    path("v1/admin/users", AdminUserListView.as_view(), name="admin-users"),
    path("v1/admin/users/<str:id>", AdminUserDetailView.as_view(), name="admin-user-detail"),
    path("v1/admin/relationships", AdminRelationshipListView.as_view(), name="admin-relationships"),
    path("v1/admin/relationships/<str:id>", AdminRelationshipDetailView.as_view(), name="admin-relationship-detail"),
    path("v1/admin/audit-log", AdminAuditLogView.as_view(), name="admin-audit-log"),
]
