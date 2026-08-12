// Assembles index.html from src.html by inlining every candidate face as a
// data URI. The Artifact CSP blocks external font hosts, so embedding is the
// only way to avoid a silent fallback.
import { readFile, writeFile } from "node:fs/promises"
import { fileURLToPath } from "node:url"

const here = (p) => fileURLToPath(new URL(p, import.meta.url))

const faces = [
  // UI candidates — Inter is what Proton VPN, Tailscale and Mullvad ship.
  { slot: "INTER", file: here("./node_modules/@fontsource-variable/inter/files/inter-latin-wght-normal.woff2") },
  { slot: "JAKARTA", file: here("./node_modules/@fontsource-variable/plus-jakarta-sans/files/plus-jakarta-sans-latin-wght-normal.woff2") },
  { slot: "GEIST", file: here("./node_modules/@fontsource-variable/geist/files/geist-latin-wght-normal.woff2") },
  // Data face — already shipping in the project.
  { slot: "PLEX_400", file: here("../assets/fonts/ibm-plex-mono-400.woff2") },
  { slot: "PLEX_500", file: here("../assets/fonts/ibm-plex-mono-500.woff2") },
]

let html = await readFile(here("./src.html"), "utf8")

for (const face of faces) {
  const b64 = (await readFile(face.file)).toString("base64")
  html = html.replaceAll(`/*${face.slot}*/`, `data:font/woff2;base64,${b64}`)
}

await writeFile(here("./index.html"), html)
console.log(`index.html written (${(html.length / 1024).toFixed(0)} KB)`)
