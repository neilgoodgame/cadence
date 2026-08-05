from django.urls import path

from .views import ExportDetailView, ExportDownloadView, ExportView, ImportDetailView, ImportView

urlpatterns = [
    path("v1/export", ExportView.as_view(), name="export-create"),
    path("v1/export/<str:id>", ExportDetailView.as_view(), name="export-detail"),
    path("v1/export/<str:id>/download", ExportDownloadView.as_view(), name="export-download"),
    path("v1/import", ImportView.as_view(), name="import-create"),
    path("v1/import/<str:id>", ImportDetailView.as_view(), name="import-detail"),
]
