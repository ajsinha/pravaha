# The commands a Python developer expects to find, without reading a README first.
#
# The Maven build runs the same pytest invocation through the `python` profile, so a green
# `make test` here and a green `./mvnw -Ppython verify` at the root mean the same thing.

.PHONY: help venv install test lint typecheck build clean

help:
	@echo "make install    create .venv and install the package with dev extras"
	@echo "make test       run the tests (needs the Java side built for transport tests)"
	@echo "make lint       ruff"
	@echo "make typecheck  mypy"
	@echo "make build      build the wheel and sdist into dist/"
	@echo "make clean      remove build artefacts (keeps .venv)"

.venv/bin/python:
	python3 -m venv .venv
	.venv/bin/python -m pip install --quiet --upgrade pip

venv: .venv/bin/python

install: venv
	.venv/bin/python -m pip install --quiet -e '.[dev]'

test: install
	.venv/bin/python -m pytest tests -q

lint: install
	.venv/bin/python -m ruff check pravaha tests

typecheck: install
	.venv/bin/python -m mypy pravaha

build: install
	.venv/bin/python -m build --outdir dist

clean:
	rm -rf dist build .pytest_cache .mypy_cache .ruff_cache
	find . -name '__pycache__' -type d -prune -exec rm -rf {} +
