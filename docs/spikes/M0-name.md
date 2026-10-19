# M0 name check

Run on 2026-10-13. 02 §16 ends the M0 "Done when" with "The name's availability is checked", and 01 §10 lists what that covers: GitHub, the Android package name, a domain and trademarks. Everything below is a read-only lookup; nothing was registered or reserved.

| Where | Name | Result |
|---|---|---|
| GitHub | `vitofico/spunto` | Free (HTTP 404) |
| GitHub, other repositories | `spunto` | Nothing in the same space. The closest are `trofimovilya/Spunto` (a Hammerspoon keyboard-layout plugin, 1 star) and `bnto/spunto` (a developer shell tool, 0 stars) |
| PyPI | `spunto` | Free (HTTP 404), should the server ever be published there |
| Google Play | `io.theficos.spunto` | No listing (HTTP 404) |
| F-Droid | `io.theficos.spunto` | No listing (HTTP 404) |
| Image | `ghcr.io/vitofico/spunto` | The owner's own namespace |
| Domains | `spunto.com`, `spunto.it`, `spunto.io` | Registered (2006, an old `.it` record, and 2026-03-03). No product page turned up in a web search |
| Domains | `getspunto.com`, `spuntoapp.com` | Free |
| Domains | `spunto.app`, `spunto.dev` | Inconclusive: the registry's whois did not answer |
| Web search | "Spunto" notes app or AI app | No notes or AI product by that name |
| Trademarks | EUIPO, UIBM (Italy), USPTO | **Not checked**: needs the owner, see below |

**Commands.**

```sh
gh api repos/vitofico/spunto                                   # 404 Not Found
gh search repos spunto --sort stars --limit 8
curl -s -o /dev/null -w '%{http_code}' https://pypi.org/pypi/spunto/json                                  # 404
curl -s -o /dev/null -w '%{http_code}' 'https://play.google.com/store/apps/details?id=io.theficos.spunto'  # 404
curl -s -o /dev/null -w '%{http_code}' https://f-droid.org/api/v1/packages/io.theficos.spunto              # 404
whois -h whois.verisign-grs.com spunto.com       # Creation Date: 2006-09-06
whois -h whois.nic.io spunto.io                  # Creation Date: 2026-03-03
whois -h whois.nic.it spunto.it                  # Status: ok
whois -h whois.verisign-grs.com getspunto.com    # No match for domain "GETSPUNTO.COM".
whois -h whois.verisign-grs.com spuntoapp.com    # No match for domain "SPUNTOAPP.COM".
```

**Verdict: clear for a self-hosted project, pending trademarks.** Every identifier the spec uses (repository, package, application ID, image, MCP server name) is free or already the owner's. The project needs no domain of its own, since instances live on their owners' hosts, so the taken `spunto.*` domains do not block anything. "Spunto" is an ordinary Italian word ("a cue, a starting point"), which makes a conflicting registration in the software class less likely but does not rule it out.

**Owner action.** Search "Spunto" in Nice class 9 (software) and 42 (software services) on [TMview](https://www.tmdn.org/tmview/) (covers EUIPO and UIBM) and on the USPTO search, and note the result here before the repository goes public.
