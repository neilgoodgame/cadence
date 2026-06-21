"""Auto-tags every collected test as `unit` or `integration`.

A test is `integration` if it needs a real database connection: either it's
a django.test.TestCase (or TransactionTestCase) subclass, or a plain
function/class using the `django_db` fixture/marker. Everything else
(django.test.SimpleTestCase subclasses, plain functions with no DB) is
`unit`. This is automatic rather than hand-tagged so a new TestCase nobody
remembers to mark doesn't silently end up in the wrong bucket.
"""

from django.test import TransactionTestCase
from pytest import Item


def _needs_database(item: Item) -> bool:
    if item.get_closest_marker("django_db") is not None:
        return True
    test_class = getattr(item, "cls", None)
    return test_class is not None and issubclass(test_class, TransactionTestCase)


def pytest_collection_modifyitems(items: list[Item]) -> None:
    for item in items:
        item.add_marker("integration" if _needs_database(item) else "unit")
