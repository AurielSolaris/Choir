# Choir — Android build Makefile
# Wraps Gradle for common targets.

.PHONY: all debug release test clean install install-release

all: debug

debug:
	./gradlew assembleDebug

release:
	./gradlew assembleRelease

test:
	./gradlew test

clean:
	./gradlew clean

# Build and push to the attached device.
install:
	./gradlew installDebug

install-release:
	./gradlew installRelease
