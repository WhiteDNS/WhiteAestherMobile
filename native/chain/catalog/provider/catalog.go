package provider

import (
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

func ParseProxyCatalog(raw []byte) ([]C.Proxy, error) {
	parse, err := NewProxiesParser("catalog", tunnel.Tunnel, "", "", "", "", overrideSchema{}, "")
	if err != nil {
		return nil, err
	}
	return parse(raw)
}
