// Checks a Psiphon bootstrap server list with tunnel-core's own code.
//
// Copied into a psiphon-tunnel-core checkout by native/psiphon/setup.ps1, run
// there, and removed. It borrows tunnel-core's DecodeServerEntryFields and
// VerifySignature rather than reimplementing Ed25519 over Psiphon's field
// encoding: the question is whether tunnel-core will accept these entries, so
// tunnel-core is what answers it. Adapted from the desktop app's
// scripts/psiphon-verify/main.go.
//
// Usage: go run . <server-list> <signature-key-file>
package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"

	"github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/protocol"
)

func main() {
	key, err := os.ReadFile(os.Args[2])
	if err != nil {
		fmt.Println(err)
		os.Exit(2)
	}
	publicKey := strings.TrimSpace(string(key))

	f, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Println(err)
		os.Exit(2)
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 1<<20), 1<<22)
	total, verified, reported := 0, 0, 0
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		total++
		fields, err := protocol.DecodeServerEntryFields(line, "", protocol.SERVER_ENTRY_SOURCE_TARGET)
		if err == nil {
			err = fields.VerifySignature(publicKey)
		}
		if err != nil {
			// A handful is enough to see why; a rotated key fails every entry.
			if reported < 3 {
				fmt.Printf("entry %d: %v\n", total, err)
				reported++
			}
			continue
		}
		verified++
	}
	fmt.Printf("%d/%d server entries verify against the signature key\n", verified, total)
	if total == 0 || verified != total {
		os.Exit(1)
	}
}
