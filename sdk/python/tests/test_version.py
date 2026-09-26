"""
Pravaha Python SDK -- the package says which version it is.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Found while writing the API guide: ``pravaha.__version__`` was the literal "0.1.0", which the release
script never changed, so the 0.1.1 wheel reported 0.1.0.
"""
def test_the_package_reports_the_version_it_was_installed_as_not_a_literal():
    from importlib.metadata import PackageNotFoundError, version

    import pravaha

    try:
        expected = version("pravaha")
    except PackageNotFoundError:
        expected = "unknown"
    assert pravaha.__version__ == expected
