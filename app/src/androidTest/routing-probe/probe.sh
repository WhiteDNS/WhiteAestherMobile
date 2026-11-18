# probe.sh HOST [CONNECT_TO] [PATH] : plain HTTP as the shell user, stdin held open for toybox nc
T=${2:-$1}; P=${3:-/}
(printf "GET $P HTTP/1.1\r\nHost: $1\r\nUser-Agent: curl/8\r\nConnection: close\r\n\r\n"; sleep 3) | nc -w 10 "$T" 80
