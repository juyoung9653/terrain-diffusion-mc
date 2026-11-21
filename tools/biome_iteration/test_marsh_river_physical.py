"""Small semantic regression checks for marsh_river_physical's face predicate."""
from marsh_river_physical import exposed_to_open_air


def test_wet_seagrass_is_not_an_open_face() -> None:
    assert not exposed_to_open_air(("minecraft:water", True), ("minecraft:seagrass", True))
    assert not exposed_to_open_air(("minecraft:water", True), ("mod:waterlogged_reed", True))


def test_air_and_dry_soft_grass_are_open_faces() -> None:
    assert exposed_to_open_air(("minecraft:water", True), ("minecraft:air", False))
    assert exposed_to_open_air(("minecraft:water", True), ("greeneries:medium_grass", False))
