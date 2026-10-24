#!/usr/bin/env python3
"""Generate or reuse a user-approved repair-only RSA key. Never prints private key material."""
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from pathlib import Path
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: generate_repair_key.py PRIVATE_LOCAL_DIRECTORY")
    directory = Path(sys.argv[1]).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    key_path = directory / "repair-local-key.pem"
    cert_path = directory / "repair-local-cert.pem"
    if key_path.exists() or cert_path.exists():
        if not key_path.exists() or not cert_path.exists():
            raise SystemExit("Refusing to replace a partially existing signing identity")
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        if key.public_key().public_numbers() != cert.public_key().public_numbers():
            raise SystemExit("Existing repair key/certificate do not match")
    else:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        now = datetime.now(timezone.utc)
        subject = issuer = x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, "W8PremiumYT user-authorized repair"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Repair-only signing identity"),
        ])
        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(issuer)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=365 * 30))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=False,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CODE_SIGNING]), critical=False)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
            .sign(key, hashes.SHA256())
        )
        key_path.write_bytes(key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ))
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        os.chmod(key_path, 0o600)
        os.chmod(cert_path, 0o600)
    fingerprint = sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    (directory / "repair-cert-sha256.txt").write_text(fingerprint + "\n")
    os.chmod(directory / "repair-cert-sha256.txt", 0o600)
    print("Repair-only signing identity ready; private key was not displayed.")
    print("Certificate SHA-256:", fingerprint)


if __name__ == "__main__":
    main()
