# Cloudflare Worker and proxy profiles

## Deploy a Worker

1. Open **Methods → Cloudflare Workers → Create API token**. The prefilled Cloudflare form includes Account Settings Read, Workers Scripts Edit and Cloudflare Pages Edit. It also includes Zone Read and DNS Edit for custom domains, and Account Analytics Read for request counts. Restrict Account Resources and Zone Resources to the account and domains you manage, then create the token. [Cloudflare token templates](https://developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template/)
2. Paste the token, tap **Load accounts**, select the account and choose **Workers** or **Pages**. Tap **Deploy**.
3. Open **Worker connection settings** on the deployment. **Normal** tests the configured clean addresses and TLS ports and chooses a candidate by latency. **Fragment** uses the saved fragment lengths and delays. **Browser Dialer** opens the WebSocket through Android WebView on port 443. ECH, custom CDN and the upstream TCP relay apply to Normal mode.
4. Tap **Save Worker settings**. On the dashboard choose **Cloudflare Worker · VLESS** and tap **Connect**. The app uses the selected deployment or the only ready deployment. If several are ready and none is selected, choose one from the list. You can also use **Connect through this deployment** from its card.

The Worker carries TCP using VLESS over WebSocket with TLS. Choose **Proxy profiles** from the dashboard's **Connection method** menu to use a VMess, Trojan, Shadowsocks or another imported node. Changing Worker proxy IPs or the fallback website updates the deployed script and requires an API token. Sites behind Cloudflare refuse direct Worker connections; the Worker retries them through your proxy IP, then through public NAT64 gateways, which see destination addresses but not encrypted content.

**Attach or retry custom domain** sets up the hostname on the selected deployment. Use an unused subdomain in your Cloudflare account. DNS and certificate activation can take time. The app keeps the original deployment hostname while setup is incomplete. **Detach custom domain** switches back to that hostname. The request counter uses Cloudflare Analytics for the last 24 hours.

The token stays in memory while the screen is open. Incomplete deployments can be retried with the same name and connection identity. Disconnect before updating the active Worker or deleting a deployment.

## Connect with an imported protocol

1. In **Methods**, select **Proxy profiles** and open the profile list. Add a subscription or paste server profiles such as VLESS, VMess, Trojan or Shadowsocks.
2. Use **Protocol** to filter the list and pick a node. Pasted profiles and cached subscription nodes can be selected before connecting. A new subscription is fetched on the first connection.
3. Return to the home screen and connect. Proxy profiles dial the selected node directly. To use a node as an exit after another connection, use **Exit chain** under that method.
