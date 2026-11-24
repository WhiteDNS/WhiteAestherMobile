import hashlib
import os
import socket
import ssl
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path


def openssl(data, *args):
    return subprocess.check_output(["openssl", *args], input=data, timeout=10)


def check_validity(edge, start, end, now, warning_days):
    if start > now or end <= now:
        print(f"::error::Gateway {edge} presented a pinned certificate outside its validity period")
        return False
    if end <= now + timedelta(days=warning_days):
        print(f"::warning::Gateway {edge} pinned certificate expires on {end:%Y-%m-%d %H:%M} UTC")
    return True


def main(pin_tests, warning_days):
    # The gateways use a private issuer. The Rust test checks the app's pinned
    # SPKI after TLS proves possession of the key.
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    tls.check_hostname = False
    tls.verify_mode = ssl.CERT_NONE
    tls.minimum_version = ssl.TLSVersion.TLSv1_3
    status = 0
    for edge in ("162.159.197.2", "162.159.198.2"):
        with socket.create_connection((edge, 443), timeout=8) as tcp:
            with tls.wrap_socket(tcp, server_hostname="consumer-masque.cloudflareclient.com") as session:
                cert = ssl.DER_cert_to_PEM_cert(session.getpeercert(binary_form=True)).encode("ascii")
        public_key = openssl(cert, "x509", "-pubkey", "-noout")
        pin = hashlib.sha256(openssl(public_key, "pkey", "-pubin", "-outform", "DER")).hexdigest()
        dates = openssl(cert, "x509", "-noout", "-dates").decode()
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
            print(f"\nGateway {edge}, SPKI `{pin}`", dates, sep="\n", file=summary)

        checked = subprocess.run(
            [pin_tests, "--ignored", "--exact", "tests::live_masque_certificates_match_known_pins"],
            env={**os.environ, "MASQUE_OBSERVED_PINS": pin},
        )
        if checked.returncode:
            status = 1
            continue
        start, end = (
            datetime.strptime(line.partition("=")[2], "%b %d %H:%M:%S %Y GMT").replace(tzinfo=timezone.utc)
            for line in dates.splitlines()
        )
        if not check_validity(edge, start, end, datetime.now(timezone.utc), warning_days):
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], int(sys.argv[2])))
