# Production-ish Makefile for local dev / CI helpers
#
# Usage:
#   make help
#
# Notes:
# - Uses Docker Compose v2 ("docker compose").
# - Uses SBT for build/run/lint.

SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

PROJECT_NAME := scala-imdb-rest-api
COMPOSE ?= docker compose
COMPOSE_FILE ?= docker-compose.yaml

SBT ?= sbt

# Loader options (used by db-init/db-update)
MINIMAL_DATASET ?= false
export MINIMAL_DATASET

.PHONY: help
help: ## Show all make targets
	@awk 'BEGIN {FS = ":.*##"; printf "\nTargets (run as: make <target>)\n\n"} /^[a-zA-Z0-9_.-]+:.*##/ {printf "  %-26s %s\n", $$1, $$2} ' $(MAKEFILE_LIST)

## --- Docker / DB ----------------------------------------------------------------

.PHONY: docker-up
docker-up: ## Start Postgres only (no loader)
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build postgres

.PHONY: docker-down
docker-down: ## Stop containers (keep volumes)
	$(COMPOSE) -f $(COMPOSE_FILE) down

.PHONY: docker-reset
docker-reset: ## FULL reset (removes volumes)
	# Include profiles so the imdb-downloads cache volume is also removed.
	$(COMPOSE) -f $(COMPOSE_FILE) --profile init --profile update down -v --remove-orphans

.PHONY: db-init
db-init: ## Initialize IMDb DB (one-shot, uses init profile)
	$(COMPOSE) -f $(COMPOSE_FILE) --profile init up -d --build postgres-init

.PHONY: db-update
db-update: ## Incremental update (on-demand, uses update profile)
	$(COMPOSE) -f $(COMPOSE_FILE) --profile update up -d --build postgres-update

.PHONY: db-logs
db-logs: ## Tail Postgres logs
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f --tail=200 postgres

.PHONY: db-psql
db-psql: ## Open psql shell inside the Postgres container
	$(COMPOSE) -f $(COMPOSE_FILE) exec postgres psql -U postgres -d imdb

.PHONY: db-counts
db-counts: ## Print a few table counts
	$(COMPOSE) -f $(COMPOSE_FILE) exec -T postgres psql -U postgres -d imdb -c "select 'name_basics' as t, count(*) from name_basics union all select 'title_basics', count(*) from title_basics union all select 'title_principals', count(*) from title_principals;"

## --- SBT / Scala ----------------------------------------------------------------

.PHONY: clean-api
clean-api: ## Kill any process listening on :8080 (useful before starting the API)
	@echo "Killing any process listening on :8080..."; \
	lsof -ti :8080 | xargs kill -9 2>/dev/null; sleep 2

.PHONY: run
run: ## Start the API in foreground
	$(MAKE) clean-api
	$(MAKE) sbt-run

.PHONY: clean
clean: ## sbt clean
	$(SBT) clean

.PHONY: compile
compile: ## sbt compile
	$(SBT) compile

.PHONY: test
test: ## sbt test
	$(SBT) test

.PHONY: sbt-run
sbt-run: ## Run the API (alias defined in build.sbt: run -> core/run)
	$(SBT) run

.PHONY: restart
restart: ## Restart API via sbt-revolver with auto-restart on changes (default). Use BG=1 for background mode
	@if [ "$(BG)" = "1" ]; then \
		echo "Restarting API in background..."; \
		$(SBT) --client core/reStop 2>/dev/null || true; \
		sleep 1; \
		$(SBT) --client restart; \
		for i in {1..20}; do \
			if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then \
				echo "✓ API is listening on :8080"; \
				exit 0; \
			fi; \
			sleep 1; \
		done; \
		echo "ERROR: API did not start listening on :8080 within 20s"; \
		exit 1; \
	else \
		echo "Starting API with auto-restart on code changes (Ctrl-C to stop)..."; \
		echo "App will restart automatically when you save code changes."; \
		$(SBT) --client core/reStop 2>/dev/null || true; \
		sleep 1; \
		$(SBT) "~reStart"; \
	fi

.PHONY: start
start: ## Start API via sbt-revolver with auto-restart on changes (default). Use BG=1 for background mode
	@if [ "$(BG)" = "1" ]; then \
		echo "Starting API in background..."; \
		$(SBT) --client core/reStart; \
		for i in {1..20}; do \
			if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then \
				echo "✓ API is listening on :8080"; \
				exit 0; \
			fi; \
			sleep 1; \
		done; \
		echo "ERROR: API did not start listening on :8080 within 20s"; \
		exit 1; \
	else \
		echo "Starting API with auto-restart on code changes (Ctrl-C to stop)..."; \
		echo "App will restart automatically when you save code changes."; \
		$(SBT) "~reStart"; \
	fi

.PHONY: stop
stop: ## Stop sbt-revolver (reStop)
	$(SBT) --client core/reStop

.PHONY: status
status: ## Check if API is running via sbt-revolver
	$(SBT) --client core/reStatus

.PHONY: shutdownall
shutdownall: ## Stop all sbt server processes (helps if --client gets stuck)
	$(SBT) shutdownall

.PHONY: fmt
fmt: ## Format code (scalafmt)
	$(SBT) scalafmtAll scalafmtSbt

.PHONY: fmt-check
fmt-check: ## Check formatting (scalafmt --check)
	$(SBT) scalafmtCheckAll scalafmtSbtCheck

.PHONY: lint
lint: ## Check linter (alias defined in build.sbt: runLinter)
	$(SBT) runLinter

.PHONY: lint-fix
lint-fix: ## Apply linter + format (alias defined in build.sbt: fixLinter)
	$(SBT) fixLinter

.PHONY: github-workflow-generate
github-workflow-generate: ## Generate GitHub Actions workflows (sbt-github-actions)
	$(SBT) githubWorkflowGenerate

.PHONY: github-workflow-check
github-workflow-check: ## Validate GitHub Actions workflows (sbt-github-actions)
	$(SBT) githubWorkflowCheck

.PHONY: ci
ci: fmt-check lint sbt-test ## Run a CI-ish suite locally

## --- Convenience ----------------------------------------------------------------

.PHONY: start
start: docker-up ## Start Postgres

.PHONY: restart
restart: docker-down docker-up ## Restart Postgres

.PHONY: init
init: docker-up db-init ## Start Postgres and run one-shot init

.PHONY: update
update: docker-up db-update ## Start Postgres and run update job