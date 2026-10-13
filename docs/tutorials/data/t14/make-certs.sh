#!/usr/bin/env bash
# Tutorial 14 (docs/tutorials/14-running-in-production.md): a test CA and a server certificate for the
# node, in $PRAVAHA_HOME/conf/tls. For a test environment only: production certificates come from your
# own CA.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
set -euo pipefail
dir="${1:-conf/tls}"
mkdir -p "$dir"
cd "$dir"
# A CA, ten years. Its key usage is stated: strict verifiers (Python 3.13's ssl, which `pravaha doctor`
# uses) refuse a CA certificate without it, where OpenSSL and gRPC accept one.
openssl req -x509 -newkey rsa:2048 -days 3650 -nodes -keyout ca-key.pem -out ca.pem \
  -subj "/CN=pravaha-tutorial-ca" -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
# The server's key and certificate. The subjectAltName is the part people leave out: a certificate
# without one fails hostname verification whatever its CN says. PKCS#8 keys only (openssl's default).
openssl req -newkey rsa:2048 -nodes -keyout server-key.pem -out server.csr -subj "/CN=localhost" 2>/dev/null
printf '%s\n' 'subjectAltName=DNS:localhost,IP:127.0.0.1' 'keyUsage=critical,digitalSignature,keyEncipherment' \
  'extendedKeyUsage=serverAuth' 'authorityKeyIdentifier=keyid,issuer' > server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -out server.pem \
  -days 825 -extfile server.ext 2>/dev/null
rm -f server.csr server.ext ca.srl
chmod 600 ca-key.pem server-key.pem
ls
