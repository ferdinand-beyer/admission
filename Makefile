SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c

MAKEFLAGS += --no-builtin-rules --warn-undefined-variables

.ONESHELL:
.DELETE_ON_ERROR:

SRC := $(shell find src -type f -name "*.clj[sc]")
TEST_SRC := $(shell find test -type f -name "*.clj[sc]")

JAR := oidc-client-ring.jar

.PHONY: all
all: jar

.PHONY: build
build: jar

.PHONY: test
test:
	clojure -M:test -m kaocha.runner

.PHONY: test-watch
test-watch:
	clojure -M:test -m kaocha.runner \
		--watch \
		--fail-fast \
		--no-randomize \
		--plugin :kaocha.plugin/notifier

.PHONY: jar
jar: $(JAR)

.PHONY: clean
clean:
	-rm -f $(JAR)

.PHONY: install
install: jar
	clojure -X:install

.PHONY: repl
repl:
	clj -M:dev:test:repl

.PHONY: release
release:
ifdef VERSION
	clojure -X:jar :version '"$(VERSION)"'
else
	$(error Required variable VERSION is not set for target `$@')
endif

pom.xml: deps.edn
	clojure -Spom

$(JAR): pom.xml $(SRC)
	clojure -X:jar
