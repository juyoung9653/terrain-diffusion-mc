# Streams structure-shield geometry test

Run `& tools/streams_shield/run-test.ps1` with JDK 21. It compiles only the pure
tuple helper and verifies collision preservation, malformed/missing-input safety,
three-block padding, entrance-piece protection, and the large-underground-box
regression. Compiled classes remain under `build/streams-shield-tests`; it does
not start Minecraft or modify a world.
