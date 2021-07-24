.PHONY: all
all:
	./gradlew run --args '$(wildcard 20*/*/5010-r-*e.txt)'
