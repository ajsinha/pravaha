# The commands a Python developer expects to find.
#
# The console depends on the Pravaha Python SDK, which lives in this repository, so `install`
# points pip at it directly rather than at an index.

.PHONY: help install run test lint typecheck clean

help:
	@echo "make install    create .venv and install the console with dev extras"
	@echo "make run        start the console against grpc://localhost:9090"
	@echo "make test       run the tests (needs the Java side built)"
	@echo "make lint       ruff"
	@echo "make typecheck  mypy"

.venv/bin/python:
	python3 -m venv .venv
	.venv/bin/python -m pip install --quiet --upgrade pip

install: .venv/bin/python
	.venv/bin/python -m pip install --quiet -e ../sdk/python[flight]
	.venv/bin/python -m pip install --quiet -e '.[dev]'

run: install
	.venv/bin/python -m pravaha_console --engine grpc://localhost:9090 --port 8080

test: install
	.venv/bin/python -m pytest tests -q

lint: install
	.venv/bin/python -m ruff check pravaha_console tests

typecheck: install
	.venv/bin/python -m mypy pravaha_console

clean:
	rm -rf dist build .pytest_cache .mypy_cache .ruff_cache
	find . -name '__pycache__' -type d -prune -exec rm -rf {} +
