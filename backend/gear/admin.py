from django.contrib import admin

from .models import Bike, Component, ServiceRecord, Shoe, ShoeModel, ShoeModelVersion, ShoePhoto

admin.site.register(Bike)
admin.site.register(Component)
admin.site.register(ServiceRecord)
admin.site.register(Shoe)
admin.site.register(ShoeModel)
admin.site.register(ShoeModelVersion)
admin.site.register(ShoePhoto)
