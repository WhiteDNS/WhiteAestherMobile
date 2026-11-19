# tls.sh IP HELLO : sends a ClientHello so mihomo can read the SNI
(cat "$2"; sleep 4) | nc -w 8 "$1" 443 > /dev/null
