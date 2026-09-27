"""Bridges a JKS or PKCS12 keystore to the PEM bytes ``pyarrow.flight.FlightClient`` accepts.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

``FlightClient`` takes only PEM bytes -- ``tls_root_certs``, ``cert_chain``,
``private_key`` -- with no keystore API of its own, in either language's Arrow Flight
client. A JVM shop's keystore is a real, common shape (``TlsOptions`` accepts it
directly for that reason), so this reads one and re-encodes what it finds as PEM.

PKCS12 is read with ``cryptography``, an optional dependency of the ``flight`` extra
kept separate from ``pyarrow`` itself: a client that never touches a keystore should
not need it. JKS -- Java's own proprietary format, with no Python library in wide use
-- is converted to PKCS12 first by shelling out to ``keytool``, which is present on any
host that already has the JVM the keystore came from; the task explicitly frames
keystore support as being for a JVM shop, so assuming a JDK is on the path for the JKS
case specifically is the same assumption the feature is for.
"""

from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path
from typing import Any, Tuple

from pravaha.errors import PravahaError


class TlsUnreadableError(PravahaError):
    """TLS material named in ``TlsOptions`` could not be read."""

    def __init__(self, message: str) -> None:
        super().__init__(1044, message)


def _load_pkcs12(path: Path, password: str) -> Tuple[list[Any], list[Any]]:
    """Returns ``(certificates, private_keys)`` -- usually one of each, or several certificates
    for a truststore with more than one trusted entry."""
    try:
        from cryptography.hazmat.primitives.serialization import pkcs12
    except ImportError as exc:  # pragma: no cover - exercised only without the dependency
        raise TlsUnreadableError(
            "reading a PKCS12 keystore needs the 'cryptography' package. Install it with:\n"
            '    pip install "pravaha[flight]"\n'
            "(it is part of that extra, alongside pyarrow)"
        ) from exc

    try:
        data = path.read_bytes()
    except OSError as exc:
        raise TlsUnreadableError(f"the store {path} is not a readable file") from exc

    try:
        key, cert, additional = pkcs12.load_key_and_certificates(
            data, password.encode("utf-8") if password else None
        )
    except Exception as exc:  # noqa: BLE001 - cryptography raises several exception types here
        raise TlsUnreadableError(
            f"cannot read {path}: {exc}. Check the password and that this is really a PKCS12 file"
        ) from exc

    certificates = ([cert] if cert is not None else []) + list(additional or [])
    keys = [key] if key is not None else []
    return certificates, keys


def _jks_to_pkcs12(path: Path, password: str) -> Tuple[Path, tempfile.TemporaryDirectory[str]]:
    """Converts a JKS keystore to PKCS12 with ``keytool``, in a throwaway temporary directory."""
    workdir = tempfile.TemporaryDirectory(prefix="pravaha-jks-")
    converted = Path(workdir.name) / "converted.p12"
    command = [
        "keytool",
        "-importkeystore",
        "-srckeystore",
        str(path),
        "-srcstoretype",
        "JKS",
        "-srcstorepass",
        password,
        "-destkeystore",
        str(converted),
        "-deststoretype",
        "PKCS12",
        "-deststorepass",
        password,
        "-noprompt",
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
    except FileNotFoundError as exc:
        workdir.cleanup()
        raise TlsUnreadableError(
            f"reading the JKS store {path} needs 'keytool' on the PATH -- it ships with every "
            "JDK, which is the assumption keystore support makes: a JVM shop already has one"
        ) from exc
    if result.returncode != 0:
        workdir.cleanup()
        raise TlsUnreadableError(
            f"cannot convert {path} from JKS to PKCS12: {result.stdout}{result.stderr}"
        )
    return converted, workdir


def _load(path: Path, password: str, store_type: str) -> Tuple[list[Any], list[Any]]:
    if store_type.upper() == "PKCS12":
        return _load_pkcs12(path, password)
    converted, workdir = _jks_to_pkcs12(path, password)
    try:
        return _load_pkcs12(converted, password)
    finally:
        workdir.cleanup()


def _pem_certificate(certificate: Any) -> bytes:
    from cryptography.hazmat.primitives import serialization

    return bytes(certificate.public_bytes(serialization.Encoding.PEM))


def _pem_private_key(key: Any) -> bytes:
    from cryptography.hazmat.primitives import serialization

    return bytes(key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ))


def trusted_certificates_pem(path: Path, password: str, store_type: str) -> bytes:
    """Every trusted certificate in the store, concatenated as one PEM blob."""
    certificates, _keys = _load(path, password, store_type)
    if not certificates:
        raise TlsUnreadableError(f"truststore {path} has no certificate in it at all")
    return b"".join(_pem_certificate(c) for c in certificates)


def client_certificate_and_key_pem(path: Path, password: str, store_type: str) -> Tuple[bytes, bytes]:
    """The client's own certificate and private key, as ``(cert_pem, key_pem)``."""
    certificates, keys = _load(path, password, store_type)
    if not keys:
        raise TlsUnreadableError(
            f"keystore {path} has no private-key entry; a client identity for mutual TLS needs one"
        )
    if not certificates:
        raise TlsUnreadableError(f"keystore {path} has a private key but no certificate chain for it")
    cert_pem = b"".join(_pem_certificate(c) for c in certificates)
    key_pem = _pem_private_key(keys[0])
    return cert_pem, key_pem
