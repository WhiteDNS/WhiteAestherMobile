# Writes a TLS ClientHello for api.ipify.org, which tls.sh replays so mihomo can read its SNI.
import ssl
ctx = ssl.create_default_context()
incoming, outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
tls = ctx.wrap_bio(incoming, outgoing, server_hostname="api.ipify.org")
try:
    tls.do_handshake()
except ssl.SSLWantReadError:
    pass
open("hello_api.ipify.org.bin", "wb").write(outgoing.read())
