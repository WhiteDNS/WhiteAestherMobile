// Renders the v2 frames as 390x844 stills via headless Chrome + CDP,
// mirroring design/capture-preview.mjs so the two boards stay comparable.
import { spawn } from "node:child_process"
import { mkdir, writeFile } from "node:fs/promises"
import { fileURLToPath } from "node:url"
import path from "node:path"

const chromePath = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
const port = 9341
const here = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.join(here, "frames")
const fileUrl = `file:///${path.join(here, "index.html").replace(/\\/g, "/")}`

const FRAMES = [
  ["01-home-idle", "?frame=1&state=idle"],
  ["02-home-connecting", "?frame=1&state=work"],
  ["03-home-connected", "?frame=1&state=live"],
  ["04-home-failed", "?frame=1&state=fail"],
  ["05-routes", "?frame=1&screen=routes"],
  ["06-routes-advanced", "?frame=1&screen=routes&adv=1"],
  ["07-endpoint", "?frame=1&screen=endpoint"],
  ["08-endpoint-scanned", "?frame=1&screen=endpoint&ep=scanned"],
  ["09-traffic", "?frame=1&screen=traffic&adv=1"],
  ["10-settings", "?frame=1&screen=settings"],
  ["11-diagnostics", "?frame=1&screen=diagnostics"],
  ["11b-diagnostics-send", "?frame=1&screen=diagnostics&scroll=700"],
  ["12-light-home", "?frame=1&state=live&ui=light"],
  ["13-light-routes", "?frame=1&screen=routes&ui=light"],
  ["14-light-diagnostics", "?frame=1&screen=diagnostics&ui=light"],
]

const delay = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = spawn(chromePath, [
  "--headless=new",
  "--disable-gpu",
  "--hide-scrollbars",
  `--remote-debugging-port=${port}`,
  "--remote-allow-origins=*",
  "--user-data-dir=C:\\Users\\alexa\\AppData\\Local\\Temp\\WhiteAestherV2Cdp",
  "about:blank",
], { stdio: "ignore", windowsHide: true })

class Cdp {
  constructor(url) {
    this.socket = new WebSocket(url)
    this.id = 1
    this.pending = new Map()
  }
  async open() {
    await new Promise((resolve, reject) => {
      this.socket.addEventListener("open", resolve, { once: true })
      this.socket.addEventListener("error", reject, { once: true })
    })
    this.socket.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data)
      const req = msg.id && this.pending.get(msg.id)
      if (!req) return
      this.pending.delete(msg.id)
      msg.error ? req.reject(new Error(msg.error.message)) : req.resolve(msg.result)
    })
  }
  send(method, params = {}) {
    const id = this.id++
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }
  close() { this.socket.close() }
}

async function waitForBrowser() {
  for (let i = 0; i < 60; i += 1) {
    try {
      if ((await fetch(`http://127.0.0.1:${port}/json/version`)).ok) return
    } catch { /* still starting */ }
    await delay(120)
  }
  throw new Error("Chrome DevTools endpoint did not become ready.")
}

async function capture(name, query) {
  const res = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: "PUT" })
  const target = await res.json()
  const cdp = new Cdp(target.webSocketDebuggerUrl)
  await cdp.open()
  await cdp.send("Page.enable")
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 390, height: 844, deviceScaleFactor: 2, mobile: false,
    screenWidth: 390, screenHeight: 844, positionX: 0, positionY: 0, scale: 1,
  })
  await cdp.send("Page.navigate", { url: fileUrl + query })
  await delay(1400)
  const shot = await cdp.send("Page.captureScreenshot", { format: "png" })
  await writeFile(path.join(outDir, `${name}.png`), Buffer.from(shot.data, "base64"))
  cdp.close()
  await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`)
  console.log(`  ${name}.png`)
}

await mkdir(outDir, { recursive: true })
await waitForBrowser()
console.log("capturing:")
for (const [name, query] of FRAMES) await capture(name, query)
browser.kill()
console.log("done")
