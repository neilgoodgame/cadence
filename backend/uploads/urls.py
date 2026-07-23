from django.urls import path

from .views import UploadBatchDetailView, UploadDetailView, UploadHistoryView

urlpatterns = [
    path("v1/uploads/history", UploadHistoryView.as_view(), name="upload-history"),
    path("v1/uploads/batches/<str:id>", UploadBatchDetailView.as_view(), name="upload-batch-detail"),
    path("v1/uploads/<str:id>", UploadDetailView.as_view(), name="upload-detail"),
]
