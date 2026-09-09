"""Python client SDK for Project Pravaha.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The surface mirrors the Java SDK deliberately: the same concepts under the same
names, so a team running both does not have to hold two mental models. Wave 1
delivers the connection and result contracts; the gRPC transport that implements
them lands with the gateways in Wave 7.
"""

from pravaha.consistency import Consistency
from pravaha.endpoint import Endpoint, HostPort
from pravaha.errors import PravahaError, MalformedEndpointError, InvalidOptionsError
from pravaha.options import ClientOptions

__all__ = [
    "ClientOptions",
    "Consistency",
    "Endpoint",
    "HostPort",
    "InvalidOptionsError",
    "MalformedEndpointError",
    "PravahaError",
]

__version__ = "0.1.0"
