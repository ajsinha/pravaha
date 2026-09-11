# The commands a Python developer expects to find.
#
# The console depends on the Pravaha Python SDK, which lives in this repository,
# so `install` points pip at it directly rather than at an index.

.PHONY: help install run test lint typecheck clean

help:
	@echo "make install    create .venv and install the console with dev extras"
	@echo "make run        serve the console on :8090, against an engine on :9090"
	@echo "make test       run the tests (needs the Java side built, and JAVA_HOME set)"
	@echo "make lint       ruff"
	@echo "make typecheck  mypy"

.venv/bin/python:
	python3 -m venv .venv
	.venv/bin/python -m pip install --quiet --upgrade pip

install: .venv/bin/python
	.venv/bin/python -m pip install --quiet -e ../sdk/python[flight]
	.venv/bin/python -m pip install --quiet -e '.[dev]'

run: install
	.venv/bin/python run_pravaha_web.py

# Any configuration key can be overridden on the command line, so a second
# instance pointed somewhere else needs no file of its own.
run-against: install
	.venv/bin/python run_pravaha_web.py --engine.url=$(ENGINE) --server.port=$(PORT)

test: install
	.venv/bin/python -m pytest tests -q

lint: install
	.venv/bin/python -m ruff check core routes tests run_pravaha_web.py

typecheck: install
	.venv/bin/python -m mypy core routes run_pravaha_web.py

clean:
	rm -rf dist build .pytest_cache .mypy_cache .ruff_cache
	find . -name '__pycache__' -type d -prune -exec rm -rf {} +
