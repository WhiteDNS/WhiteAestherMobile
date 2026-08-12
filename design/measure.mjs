// Measures tab-bar geometry in a real render so centring is verified, not assumed.
import { spawn } from "node:child_process"
import { fileURLToPath } from "node:url"
import path from "node:path"

const port = 9343
const here = path.dirname(fileURLToPath(import.meta.url))
const url = `file:///${path.join(here, "index.html").replace(/\\/g, "/")}?frame=1`
const delay = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = spawn("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", [
  "--headless=new", "--disable-gpu", "--hide-scrollbars",
  `--remote-debugging-port=${port}`, "--remote-allow-origins=*",
  "--user-data-dir=C:\\Users\\alexa\\AppData\\Local\\Temp\\WhiteAestherMeasure", "about:blank",
], { stdio: "ignore", windowsHide: true })

for (let i = 0; i < 60; i += 1) {
  try { if ((await fetch(`http://127.0.0.1:${port}/json/version`)).ok) break } catch { /* starting */ }
  await delay(120)
}

const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: "PUT" })).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((res) => ws.addEventListener("open", res, { once: true }))
let id = 1
const pending = new Map()
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data)
  const r = m.id && pending.get(m.id)
  if (r) { pending.delete(m.id); r(m.result) }
})
const send = (method, params = {}) =>
  new Promise((res) => { pending.set(id, res); ws.send(JSON.stringify({ id: id++, method, params })) })

await send("Page.enable")
await send("Emulation.setDeviceMetricsOverride", {
  width: 390, height: 844, deviceScaleFactor: 1, mobile: false,
  screenWidth: 390, screenHeight: 844, positionX: 0, positionY: 0, scale: 1,
})
await send("Page.navigate", { url })
await delay(1600)

const expression = `JSON.stringify((() => {
  const bar = document.querySelector('.tabbar');
  const ind = document.querySelector('.tab-ind');
  const b = bar.getBoundingClientRect(), ib = ind.getBoundingClientRect();
  return [...document.querySelectorAll('.tab')].map((t) => {
    const c = t.getBoundingClientRect();
    const svg = t.querySelector('svg').getBoundingClientRect();
    const lbl = t.querySelector('span').getBoundingClientRect();
    const sel = t.getAttribute('aria-selected') === 'true';
    const r = (n) => Math.round(n * 100) / 100;
    return {
      tab: t.textContent.trim(),
      cellCx: r(c.left + c.width / 2 - b.left),
      iconCx: r(svg.left + svg.width / 2 - b.left),
      labelCx: r(lbl.left + lbl.width / 2 - b.left),
      above: r(svg.top - c.top),
      below: r(c.bottom - lbl.bottom),
      indCx: sel ? r(ib.left + ib.width / 2 - b.left) : null,
      indAbove: sel ? r(ib.top - c.top) : null,
      indBelow: sel ? r(c.bottom - ib.bottom) : null,
    };
  });
})())`

const res = await send("Runtime.evaluate", { expression, returnByValue: true })
console.table(JSON.parse(res.result.value))
browser.kill()
