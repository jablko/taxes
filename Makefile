.PHONY: all
all:
	./gradlew :calc:run --args '$(wildcard 20*/*/5010-r-*e.txt)'
