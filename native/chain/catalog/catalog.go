package main

import (
	"github.com/metacubex/mihomo/adapter/provider"
)

func handleProxyCatalog(call *MethodCall, response MethodResponse) {
	var raw string
	if !decodeMethodArguments(call, response, &raw) {
		return
	}
	if len(raw) > 2_000_000 {
		response.failure("too_large", "Proxy list is too large", nil)
		return
	}
	proxies, err := provider.ParseProxyCatalog([]byte(raw))
	if err != nil {
		response.failure("invalid_profiles", "No valid proxy profiles were found", nil)
		return
	}
	type item struct {
		Name string `json:"name"`
		Kind string `json:"kind"`
	}
	items := make([]item, 0, min(len(proxies), 5000))
	for i, proxy := range proxies {
		if i < 5000 {
			items = append(items, item{proxy.Name(), proxy.Type().String()})
		}
		_ = proxy.Close()
	}
	response.success(items)
}
