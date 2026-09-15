package com.whitedns.whiteaesther.core

import android.content.Context
import android.telephony.TelephonyManager
import com.whitedns.whiteaesther.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * The configuration psiphon-tunnel-core is started with.
 *
 * Kept small deliberately. Every field here is one this app has a reason to set;
 * tunnel-core has dozens more and its defaults are chosen against live censored
 * networks by people who measure them, which is not something to second-guess
 * from here.
 */
object PsiphonConfig {
    /**
     * Psiphon's own documented values for a client that has not been issued its
     * own.
     *
     * `PropagationChannelId` and `SponsorId` identify who distributed a client,
     * so that Psiphon can attribute usage and target server capacity. Real ones
     * are issued by Psiphon Inc. to partners; these two are the all-Fs and
     * all-1s placeholders that appear throughout tunnel-core's own tests and
     * every open-source client that has not asked for a channel of its own.
     *
     * They work, and they are not credentials -- nothing here is authenticated
     * by them. What they cost is that this app's sessions are indistinguishable
     * from every other unattributed client, so Psiphon cannot tell our users
     * apart from anyone else's when they plan capacity. If this carrier turns
     * out to matter to the people using it, asking Psiphon for a channel is the
     * honest next step rather than a technical one.
     */
    private const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
    private const val SPONSOR_ID = "1111111111111111"

    /**
     * Psiphon's public keys for the server entries and server lists it signs.
     *
     * Public halves, so nothing here is a secret or a credential: they let
     * tunnel-core check that a server it was handed came from Psiphon, and do
     * nothing else. Without the first, every server tunnel-core discovered after
     * connecting was thrown away -- the first report that carried Psiphon's
     * notices said so in as many words, "DSLStoreServerEntry ... VerifySignature
     * ... missing public key" -- which left this app on the list it shipped with
     * while Psiphon's own app kept learning new ones.
     *
     * Psiphon issues these to integrators rather than publishing them. These are
     * byte-identical to the ones in Instagram's own Psiphon configuration and in
     * several independent open-source clients.
     *
     * The server-entry key lives in native/psiphon/server_entry_signature_key.txt
     * and reaches this class through BuildConfig. That file is also what
     * native/psiphon/setup.ps1 checks the shipped list against, with
     * tunnel-core's own code, before packaging it: 430 of 430 entries of the
     * pinned list verify, and a random key verifies none.
     */
    private const val SERVER_ENTRY_SIGNATURE_KEY = BuildConfig.PSIPHON_SERVER_ENTRY_SIGNATURE_KEY
    private const val REMOTE_SERVER_LIST_SIGNATURE_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    /**
     * Where Psiphon publishes its current server list.
     *
     * Fetched before any tunnel exists, so a first connection on a network that
     * has blocked every server this build shipped with still has somewhere to
     * start. Maintained by Psiphon: the file was two days old when this was
     * written, and it is what the placeholder-channel clients above use too.
     */
    private const val REMOTE_SERVER_LIST_URL =
        "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"

    /** Where the embedded bootstrap list is packaged. */
    const val SERVER_ENTRIES_ASSET = "psiphon_server_entries.txt"

    /**
     * The exit countries Psiphon last said it had, newest answer wins.
     *
     * Written by the Psiphon process and read by the screen, which are two
     * processes -- hence a file rather than a field. tunnel-core derives it
     * from the server entries it holds and the protocols the network allows,
     * so it can arrive before a tunnel is established as well as after one --
     * but on a fresh install, before tunnel-core has ever run, there is nothing
     * here at all and the screen has only "best available" to offer.
     */
    fun regionsFile(context: Context): File = File(dataDirectory(context), "regions.txt")

    fun availableRegions(context: Context): List<String> = runCatching {
        regionsFile(context).readLines().map { it.trim() }.filter { it.length == 2 }.sorted()
    }.getOrDefault(emptyList())

    /**
     * Records what tunnel-core says it has, if it said anything.
     *
     * An empty answer is not news that there are no countries: tunnel-core can
     * report one early in a session, and writing it through emptied the list
     * the screen was showing -- taking "best available" down with it, because
     * the picker only drew that option when it had countries to draw beside
     * it. The last answer that named something is kept instead.
     *
     * Written to a temporary file and renamed, because the reader is another
     * process: a screen that read this halfway through a write saw a truncated
     * list and no error.
     */
    fun rememberRegions(context: Context, regions: List<String>) {
        val known = regions.filter { it.length == 2 }.sorted()
        if (known.isEmpty()) return
        runCatching {
            val destination = regionsFile(context)
            val staging = File(destination.parentFile, destination.name + ".tmp")
            staging.writeText(known.joinToString("\n"))
            if (!staging.renameTo(destination)) {
                destination.writeText(known.joinToString("\n"))
                staging.delete()
            }
        }
    }

    /**
     * How long tunnel-core tries before giving up and letting us decide.
     *
     * Not unlimited, which is its default. An unlimited establish means a
     * carrier that never reports failure, and the service above it can neither
     * retry nor tell the user that this network is not working -- the screen
     * would say "connecting" until the phone was rebooted.
     *
     * But not two minutes either, which is what it was. Psiphon's own app keeps
     * going for as long as it takes, and users reported networks where it got
     * through and this app, stopping at 120 seconds and starting again from
     * nothing, never did. A retry here restarts tunnel-core, and everything it
     * was halfway through dialling is thrown away with it.
     */
    private const val ESTABLISH_TIMEOUT_SECONDS = 300

    /**
     * How many servers tunnel-core dials at once.
     *
     * Its default is ten. On a network that drops most attempts the ones that
     * would have worked are somewhere down the candidate list, and more of them
     * in flight at a time is how that list gets reached inside the window above.
     */
    private const val CONNECTION_WORKERS = 12

    /**
     * The country of the network the phone is on, or null.
     *
     * The operator's answer only. MoatClient falls back to the phone's locale,
     * which is fine for picking a bridge list and wrong here: a phone set to
     * English on wifi would claim to be in the United States and fetch tactics
     * for a network that filters nothing. No answer leaves DeviceRegion out,
     * which is what every build before this one did.
     */
    private fun networkRegion(context: Context): String? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephony?.networkCountryIso
            ?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?.uppercase(java.util.Locale.US)
    }

    /** Everything tunnel-core writes, under one directory we can delete. */
    fun dataDirectory(context: Context): File =
        File(context.filesDir, "psiphon").apply { mkdirs() }

    /**
     * @param egressRegion a two-letter country code to exit from, or empty for
     *   whichever Psiphon considers best. Not a guarantee: tunnel-core treats an
     *   unreachable region as a reason to fail rather than to substitute, which
     *   is why the screen presents it as a preference and defaults to empty.
     */
    fun render(context: Context, egressRegion: String = "", upstreamPort: Int = 0): String {
        val json = JSONObject()
        json.put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
        json.put("SponsorId", SPONSOR_ID)
        // Our own build number, as a string, which is what tunnel-core's sample
        // says and what it rejects the config for getting wrong. Psiphon uses
        // this for their statistics and their upgrade prompt; reporting one of
        // Psiphon's own client versions would put our sessions in someone
        // else's column, and reporting "1" tells them nothing at all.
        json.put("ClientVersion", BuildConfig.VERSION_CODE.toString())
        json.put("DataRootDirectory", dataDirectory(context).absolutePath)
        json.put("ServerEntrySignaturePublicKey", SERVER_ENTRY_SIGNATURE_KEY)
        json.put("RemoteServerListSignaturePublicKey", REMOTE_SERVER_LIST_SIGNATURE_KEY)
        json.put("RemoteServerListUrl", REMOTE_SERVER_LIST_URL)
        json.put("EgressRegion", egressRegion)
        if (upstreamPort > 0) {
            // Everything tunnel-core dials goes through the carrier in front
            // of it. Nothing else needs configuring for that: given an
            // upstream, Psiphon narrows itself to the protocols that can
            // cross one rather than failing on the ones that cannot.
            json.put("UpstreamProxyUrl", "socks5://127.0.0.1:$upstreamPort")
        }
        json.put("EstablishTunnelTimeoutSeconds", ESTABLISH_TIMEOUT_SECONDS)
        json.put("ConnectionWorkerPoolSize", CONNECTION_WORKERS)
        // Where the phone is, so the tactics for that network -- which protocols
        // to lead with, how to pad them, which servers to try first -- arrive
        // before the first connection rather than after it. Without it tunnel-core
        // learns the region from a server handshake, which on the networks that
        // need those tactics most is the handshake that never completes.
        networkRegion(context)?.let { json.put("DeviceRegion", it) }

        // Zero means "pick a free one and tell me", and the port that comes back
        // is what the chain is configured against. A fixed port would be one
        // more thing that can already be taken on a phone we do not control,
        // and the failure would look like Psiphon not starting.
        json.put("LocalSocksProxyPort", 0)
        // Nothing asks for an HTTP proxy. A listener nobody uses is a listener
        // on loopback that some other app on the phone can reach.
        json.put("DisableLocalHTTPProxy", true)

        // Diagnostic notices are how anything here is debuggable at all: without
        // them a failed establish is a silent one. They are written to our log,
        // which never leaves the device unless the user sends a report.
        json.put("EmitDiagnosticNotices", true)
        json.put("EmitDiagnosticNetworkParameters", false)
        return json.toString()
    }
}
