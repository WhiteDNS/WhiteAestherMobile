import type { ConnectionProfile } from "../types";

export function buildCoreCommand(profile: ConnectionProfile): string {
  const args = [
    profile.protocol === "masque" ? "--masque" : profile.protocol === "wg" ? "--wg" : "--gool",
    profile.protocol === "masque" && profile.masqueTransport === "h2" ? "--h2" : "",
    "--scan", profile.scanMode,
    "--ip", profile.ipFamily,
    "--bind", quote(profile.socksAddress),
    profile.quickReconnect ? "--quick-reconnect" : "--no-quick-reconnect",
    "--validate-secs", String(profile.validateSecs),
    "--startup-secs", String(profile.startupSecs),
    "--reconnect-secs", String(profile.reconnectSecs),
    "--dns", profile.dns.join(","),
    "--noize", profile.noize,
    "--keepalive", String(profile.keepaliveSecs),
    "--log-level", profile.logLevel,
    profile.dataCheck ? "" : "--no-data-check",
    profile.fragmentClientHello && profile.protocol === "masque" && profile.masqueTransport === "h2"
      ? `--fragment --fragment-size ${quote(profile.fragmentSize)} --fragment-delay ${quote(profile.fragmentDelay)}` : "",
    profile.profileRetry ? "" : "--no-profile-retry",
    option("--peer", profile.peer),
    option("--wg-peer", profile.wgPeer),
    option("--h2-peer", profile.h2Peer),
    profile.ech && profile.ech !== "off" ? option("--ech", profile.ech) : "",
    option("--tls-groups", profile.tlsGroups),
    profile.performanceProfile === "auto" ? "" : `--perf ${profile.performanceProfile}`,
    option("--route-block", profile.routeBlock),
    option("--route-direct", profile.routeDirect),
    option("--routes", profile.routesFile),
    profile.gateway ? "--gateway" : "",
  ].filter(Boolean);
  return `aether ${args.join(" ")}`;
}

function option(name: string, value: string | null): string {
  return value?.trim() ? `${name} ${quote(value)}` : "";
}

function quote(value: string): string {
  return /^[A-Za-z0-9._,:/@+\-=]+$/.test(value) ? value : JSON.stringify(value);
}
