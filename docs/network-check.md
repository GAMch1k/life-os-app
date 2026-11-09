# Network check (P18-01)

The phone app's first screen answers one question: **can this phone reach Life OS on this network with the hostname hidden?**

## Why

The owner's work Wi-Fi resets every TLS handshake that carries `lifeos.gamch1k.com` in clear SNI. The browser still gets through, because Cloudflare publishes ECH (Encrypted Client Hello) for the hostname. The DNS HTTPS record carries `ech=…`, with the public name `cloudflare-ech.com`. An ECH-capable browser reads it and sends only `cloudflare-ech.com` in clear. Android 16 has no platform ECH API, so the app embeds **Cronet**, Chromium's network stack, and turns on its **built-in DNS resolver**. That resolver queries HTTPS records, which the system's `getaddrinfo` cannot. The screen shows whether this works on the real phone and the real network.

## Using it

1. Open it from **Settings → Network check**. It uses the service token the app already holds (entered at the first run, or under Settings → **Edit connection**) and says so at the bottom of the screen, with the masked Client ID; without one, step 4 is skipped. The screen itself has no token fields since 0.2.0.
2. Tap **Run check**. It takes a few seconds; every probe uses a fresh connection.
3. Read the **Result** card, then tap **Copy report** and paste the text wherever it is needed. The report never contains the token or the phone's public IP.

Run it on the work Wi-Fi, which is the case that matters. Run it once on mobile data too, for comparison.

## What each part means

### Result

- **GO**: embedded Cronet got `sni=encrypted`, so ECH worked.
- **NO ECH**: Cronet connected, but with the hostname in clear (`sni=plaintext`). On the work Wi-Fi this cannot happen, because such a connection is reset; elsewhere it means Cronet had no ECH key.
- **FAILED**: Cronet got no answer. `ERR_CONNECTION_RESET (-101)` on the work Wi-Fi is the reset this whole effort works around.

When the result isn't GO, hints follow. The most likely cause is **Private DNS** (see below).

### 1. Cronet, embedded, built-in DNS

A GET to `/cdn-cgi/trace` on the server the app is set to (by default `https://lifeos.gamch1k.com/cdn-cgi/trace`); every probe goes to that server, the one the token belongs to. Cloudflare's edge answers it itself, without an Access login, and reports what it saw:

| Line | Meaning |
|---|---|
| `sni=encrypted` | The edge received an encrypted ClientHello: **ECH worked**. `sni=plaintext` means the hostname went in clear. |
| `http=` | The protocol used: `http/2`, `http/1.1`, or `http/3` (only if QUIC is allowed). |
| `tls=` | The TLS version, normally `TLSv1.3` (ECH requires it). |
| `warp=` | `off` unless the phone runs Cloudflare WARP. With WARP on, the work network never sees our handshake, so the test proves nothing. |
| `kex=` | The key exchange, e.g. `X25519MLKEM768`. |
| `colo=` | The Cloudflare data centre that answered. |

Below these lines come the total time and the connection phases (DNS, connect, TLS). The HTTP status and the negotiated protocol are on the first line.

### 2. Android platform stack

The same GET through `HttpURLConnection`, Android's own stack. It cannot do ECH, so it should show `sni=plaintext`. On the work Wi-Fi it should fail with a connection reset. That failure proves the network really does block clear SNI, and therefore that a GO from Cronet is meaningful.

### 2b. HttpEngine (extra)

The same GET through Android's built-in Cronet (`android.net.http.HttpEngine`, part of the system and updated through system updates), configured the same way. If it also says `sni=encrypted`, a later version of the app might not need to ship its own copy of Cronet, which is about 7 MB. "Not available" is fine.

### 3. Device

- **Android version** and model.
- **Network**: Wi-Fi or mobile data (and whether a VPN is on top).
- **Private DNS**: Settings › Network › Private DNS.
  - `not active`: good for ECH.
  - `ACTIVE, automatic` or `ACTIVE, strict (host)`: Chromium's built-in resolver steps aside when Private DNS is active and falls back to the system resolver, which can't fetch HTTPS records. Then there is no ECH key, and the result can't be GO. Set Private DNS to **Off** and run again.
- **DNS servers**: the resolvers Chromium queries directly.
- **Cronet**: the embedded version.

### 3b. DNS HTTPS record via the system resolver

This asks the phone's normal resolver, which honours Private DNS, for the `HTTPS` record of the server's hostname (`lifeos.gamch1k.com` by default). Expect `alpn=h3,h2  ech=yes (… bytes, public name cloudflare-ech.com)`.

- `ech=no` or no record: this network's DNS strips HTTPS records, so ECH can't work here.
- `ech=yes` while Cronet still says plaintext: the key exists, but Cronet didn't use it. Run the check again; see the hints on the screen.

### 4. Service token request

With a token in the app, this sends `GET <server>/api/tasks/v1/tasks?limit=1` to the server the app is set to, through the same Cronet engine, with the headers `CF-Access-Client-Id`, `CF-Access-Client-Secret` and `X-Life-OS-Actor: user`. Redirects are not followed.

| Status | Meaning |
|---|---|
| 200 | The token works: Access let the request through to Life OS. |
| 302 to `…cloudflareaccess.com` | No Service Auth policy accepts this token. |
| 401 | Access refused the token: the ID or secret is wrong or partial (the ID ends in `.access`), or the token expired. |

The token is the app's own (the token store, see the README). It is stored only on the phone, encrypted with an AES-256-GCM key that stays inside the Android Keystore; the check shows it masked and never logs or reports it.

### Allow HTTP/3 (QUIC)

Off by default, so that the check tests TCP and TLS, where the work network resets clear SNI. Turn it on to see whether QUIC (UDP 443) gets through as well.

## How Cronet is configured, and why

`core/…/CronetProbe.kt`, `CronetEngines.build()`:

- **Provider**: the app-packaged Cronet (`CronetProvider.PROVIDER_NAME_APP_PACKAGED`), chosen by name. It is never the Play Services provider or the platform's, so the network stack is the one inside our APK. The artifact is `org.chromium.net:cronet-bundled:500.1.0`, which contains Chromium 155.0.8059.16. Since 500.x, `cronet-embedded` is only an empty, deprecated alias that pulls in `cronet-bundled`.
- **`DnsOptions.useBuiltInDnsResolver(true)`**: this is public API, marked `@DnsOptions.Experimental`. Cronet translates it into the experimental option `{"AsyncDNS":{"enable":true}}` (`ExperimentalOptionsTranslator.dnsOptionsToJson`), and the native side turns that into `InsecureDnsMode::kEnabledBuiltIn` (`components/cronet/url_request_context_config.cc`). The JSON form is also passed explicitly.
- **`UseDnsHttpsSvcb`**: Chromium already queries HTTPS records; `net::features::kUseDnsHttpsSvcb` is enabled by default. But once the A/AAAA answers arrive, it waits only 5–50 ms for the HTTPS answer before it connects without ECH. The check raises that wait to at least 200 ms and at most 1 s (`insecure_extra_time_min/percent/max`, `secure_…`). Note that `HttpsSvcbOptions::FromDict` starts from zeros, so all six values are set.
- **ECH needs no switch.** Chromium 155 has no ECH feature flag left. The default `SSLConfigService` returns `EchMode::kOpportunistic` (`net/ssl/ssl_config_service_defaults.cc`, `net/base/ech_mode.h`). On Android it asks `EchModeGetterAndroid`, which returns opportunistic below Android 17. From Android 17, it follows the app's network-security-config `domainEncryption` setting, and only `disabled` turns ECH off (`AndroidNetworkLibrary.getEchMode`). So ECH is used whenever the HTTPS record carries `ech=`.
- **Private DNS**: on Android, Chromium reads `LinkProperties.isPrivateDnsActive`. While Private DNS is active, it refuses its own insecure DNS client (`DnsClientImpl::CanUseInsecureDnsTransactions` checks `dns_over_tls_active`) and uses the system resolver, which returns no HTTPS records. Cronet cannot enable DoH; its `secure_dns_mode` stays off. The "DNS platform" mode (`android_res_nquery`), which would get HTTPS records through Private DNS, is behind the base::Feature `CronetEnableDnsPlatform`. Only system-provided HTTP flags can set that feature: `HttpFlagsLoader` resolves the flags provider with `MATCH_SYSTEM_ONLY`. So the practical rule is **Private DNS off**.
- **`HostResolverRules`**: not used. It only remaps hostnames to addresses for testing, and cannot supply an HTTPS record or an ECH key.

Sources, all at Chromium tag `155.0.8059.16`, the version inside Cronet 500.1.0: `components/cronet/url_request_context_config.cc`, `components/cronet/android/api/src/org/chromium/net/{DnsOptions,CronetEngine,ExperimentalOptionsTranslator}.java`, `components/cronet/android/java/src/org/chromium/net/httpflags/HttpFlagsLoader.java`, `net/dns/dns_client.cc`, `net/dns/host_resolver.{h,cc}`, `net/base/features.cc`, `net/ssl/ssl_config_service_defaults.cc`, `net/ssl/ech_mode_getter_android.cc`, `net/android/java/src/org/chromium/net/AndroidNetworkLibrary.java`.
