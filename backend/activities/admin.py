from django.contrib import admin

from .models import Activity, ActivityTag, BestEffort, DurationCurve, Lap, Record, Tag

admin.site.register(Activity)
admin.site.register(Lap)
admin.site.register(Tag)
admin.site.register(ActivityTag)
admin.site.register(Record)
admin.site.register(DurationCurve)
admin.site.register(BestEffort)
