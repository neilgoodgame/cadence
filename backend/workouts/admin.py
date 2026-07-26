from django.contrib import admin

from .models import Workout, WorkoutFolder, WorkoutStep

admin.site.register(Workout)
admin.site.register(WorkoutFolder)
admin.site.register(WorkoutStep)
