import json
import base64
import struct

# Node definitions extracted directly from asteria_backup_20260907-164621-313.json

nodes = [
    {
        "type": "anytls",
        "tag": "新加坡01",
        "server": "asg1.asahione.com",
        "server_port": 53041,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.youku.com",
            "insecure": True
        }
    },
    {
        "type": "vless",
        "tag": "日本 RFC JP-CO",
        "server": "104.251.231.10",
        "server_port": 11245,
        "uuid": "9304ebcd-0e05-4601-a3f7-baee05e5a453",
        "flow": "xtls-rprx-vision",
        "packet_encoding": "xudp",
        "tls": {
            "enabled": True,
            "server_name": "www.amazon.com",
            "utls": {
                "enabled": True,
                "fingerprint": "chrome"
            }
        }
    },
    {
        "type": "vless",
        "tag": "美国 DMIT LAX EB CORONA 01",
        "server": "dmit01.aurorapipe.com",
        "server_port": 16770,
        "uuid": "439dc297-401d-4a78-9ffa-15ef40424f89",
        "flow": "xtls-rprx-vision",
        "packet_encoding": "xudp",
        "tls": {
            "enabled": True,
            "server_name": "www.amazon.com",
            "utls": {
                "enabled": True,
                "fingerprint": "chrome"
            }
        }
    },
    {
        "type": "vless",
        "tag": "美国 DMIT LAX EB CORONA 02",
        "server": "dmit01.aurorapipe.com",
        "server_port": 39115,
        "uuid": "4a10ed84-1569-4f67-bfd0-7769c746bda4",
        "flow": "xtls-rprx-vision",
        "packet_encoding": "xudp",
        "tls": {
            "enabled": True,
            "server_name": "www.amazon.com",
            "utls": {
                "enabled": True,
                "fingerprint": "chrome"
            }
        }
    },
    {
        "type": "vless",
        "tag": "美国 DMIT LAX EB CORONA 03",
        "server": "dmit01.aurorapipe.com",
        "server_port": 16234,
        "uuid": "cfdab903-c083-4a5e-9d07-fa5a0ab3998a",
        "flow": "xtls-rprx-vision",
        "packet_encoding": "xudp",
        "tls": {
            "enabled": True,
            "server_name": "www.amazon.com",
            "utls": {
                "enabled": True,
                "fingerprint": "chrome"
            }
        }
    },
    {
        "type": "vless",
        "tag": "美国 DMIT AI家庭 01",
        "server": "rfc01.aurorapipe.com",
        "server_port": 19403,
        "uuid": "709f1bd3-33d8-447f-a3a4-d56425d82982",
        "flow": "xtls-rprx-vision",
        "packet_encoding": "xudp",
        "tls": {
            "enabled": True,
            "server_name": "www.amazon.com",
            "utls": {
                "enabled": True,
                "fingerprint": "chrome"
            }
        }
    },
    {
        "type": "vless",
        "tag": "美国 DMIT AI家庭 02",
        "server": "stonehd.aurorapipe.com",
        "server_port": 16771,
        "uuid": "c576ad6a-e041-4137-9b8f-0a4b3e9671a9",
        "flow": "xtls-rprx-vision",
        "packet_encoding": "xudp",
        "tls": {
            "enabled": True,
            "server_name": "www.amazon.com",
            "utls": {
                "enabled": True,
                "fingerprint": "chrome"
            }
        }
    },
    {
        "type": "vless",
        "tag": "美国 DMIT AI家庭 03",
        "server": "stonehd.aurorapipe.com",
        "server_port": 15418,
        "uuid": "ac503525-fbd6-45af-9d6e-12cbe49e91eb",
        "flow": "xtls-rprx-vision",
        "packet_encoding": "xudp",
        "tls": {
            "enabled": True,
            "server_name": "www.amazon.com",
            "utls": {
                "enabled": True,
                "fingerprint": "chrome"
            }
        }
    },
    {
        "type": "anytls",
        "tag": "日本01",
        "server": "ajp1.asahione.com",
        "server_port": 53021,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.youku.com",
            "insecure": True
        }
    },
    {
        "type": "anytls",
        "tag": "日本02",
        "server": "ajp2.asahione.com",
        "server_port": 53031,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.youku.com",
            "insecure": True
        }
    },
    {
        "type": "anytls",
        "tag": "日本03 | 优化 | 2倍率",
        "server": "zjp5.webkirin.com",
        "server_port": 443,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.temu.com",
            "insecure": True
        }
    },
    {
        "type": "anytls",
        "tag": "日本04 | 优化 | 2倍率",
        "server": "zjp6.webkirin.com",
        "server_port": 443,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.temu.com",
            "insecure": True
        }
    },
    {
        "type": "anytls",
        "tag": "新加坡02",
        "server": "asg2.asahione.com",
        "server_port": 53201,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.youku.com",
            "insecure": True
        }
    },
    {
        "type": "anytls",
        "tag": "美国01",
        "server": "aus1.asahione.com",
        "server_port": 53101,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.youku.com",
            "insecure": True
        }
    },
    {
        "type": "anytls",
        "tag": "美国02",
        "server": "aus2.asahione.com",
        "server_port": 53221,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.youku.com",
            "insecure": True
        }
    },
    {
        "type": "anytls",
        "tag": "美国03 | 优化 | 2倍率",
        "server": "zus3.webkirin.com",
        "server_port": 443,
        "password": "fRLBRfMz4loQdpMQ",
        "tls": {
            "enabled": True,
            "server_name": "www.temu.com",
            "insecure": True
        }
    }
]

# Groups
us_core_nodes = [
    "美国 DMIT AI家庭 01",
    "美国 DMIT AI家庭 02",
    "美国 DMIT AI家庭 03",
    "美国03 | 优化 | 2倍率",
    "美国01",
    "美国02"
]

us_media_nodes = [
    "美国 DMIT LAX EB CORONA 01",
    "美国 DMIT LAX EB CORONA 02",
    "美国 DMIT LAX EB CORONA 03",
    "美国01",
    "美国02"
]

sg_tg_nodes = [
    "新加坡01",
    "新加坡02",
    "日本 RFC JP-CO"
]

all_node_tags = [n["tag"] for n in nodes]

# Build complete config
config = {
    "log": {
        "level": "info",
        "timestamp": True
    },
    "dns": {
        "servers": [
            {
                "tag": "dns-direct",
                "address": "https://dns.alidns.com/dns-query",
                "detour": "direct",
                "address_resolver": "dns-local",
                "strategy": "prefer_ipv4"
            },
            {
                "tag": "dns-remote",
                "address": "https://8.8.8.8/dns-query",
                "detour": "PROXY",
                "address_resolver": "dns-direct",
                "strategy": "prefer_ipv4"
            },
            {
                "tag": "dns-fake",
                "address": "fakeip",
                "strategy": "ipv4_only"
            },
            {
                "tag": "dns-block",
                "address": "rcode://success"
            },
            {
                "tag": "dns-local",
                "address": "local",
                "detour": "direct"
            }
        ],
        "rules": [
            {
                "outbound": "any",
                "server": "dns-direct"
            },
            {
                "domain": [
                    "dns.alidns.com",
                    "8.8.8.8"
                ],
                "server": "dns-direct"
            },
            {
                "geosite": "category-ads-all",
                "server": "dns-block",
                "disable_cache": True
            },
            {
                "domain_suffix": [
                    "ads-twitter.com",
                    "ads.twitter.com",
                    "ad.weixin.qq.com",
                    "as.weixin.qq.com"
                ],
                "server": "dns-block",
                "disable_cache": True
            },
            {
                "geosite": "cn",
                "server": "dns-direct"
            },
            {
                "domain_suffix": [
                    "local",
                    "lan",
                    "home.arpa",
                    "internal",
                    "cn",
                    "qq.com",
                    "weixin.qq.com",
                    "gtimg.cn",
                    "ugdtimg.com",
                    "gdtimg.com",
                    "wxamedia.com",
                    "taobao.com",
                    "idlefish.com"
                ],
                "server": "dns-direct"
            },
            {
                "inbound": "tun-in",
                "query_type": [
                    "A",
                    "AAAA"
                ],
                "server": "dns-fake"
            }
        ],
        "final": "dns-remote",
        "strategy": "prefer_ipv4",
        "independent_cache": True,
        "fakeip": {
            "enabled": True,
            "inet4_range": "198.18.0.0/15",
            "inet6_range": "fc00::/18"
        }
    },
    "inbounds": [
        {
            "type": "tun",
            "tag": "tun-in",
            "interface_name": "tun0",
            "inet4_address": "172.19.0.1/30",
            "inet6_address": "fdfe:dcba:9876::1/126",
            "mtu": 1420,
            "auto_route": True,
            "strict_route": True,
            "stack": "system",
            "sniff": True,
            "sniff_override_destination": True
        },
        {
            "type": "mixed",
            "tag": "mixed-in",
            "listen": "127.0.0.1",
            "listen_port": 2080,
            "sniff": True,
            "sniff_override_destination": True
        }
    ],
    "outbounds": [
        {
            "type": "selector",
            "tag": "PROXY",
            "outbounds": [
                "US-Core",
                "US-Media",
                "SG-TG",
                "AUTO-ALL"
            ] + all_node_tags,
            "default": "US-Core"
        },
        {
            "type": "urltest",
            "tag": "US-Core",
            "outbounds": us_core_nodes,
            "url": "https://cp.cloudflare.com/generate_204",
            "interval": "5m",
            "tolerance": 50
        },
        {
            "type": "urltest",
            "tag": "US-Media",
            "outbounds": us_media_nodes,
            "url": "https://cp.cloudflare.com/generate_204",
            "interval": "5m",
            "tolerance": 50
        },
        {
            "type": "urltest",
            "tag": "SG-TG",
            "outbounds": sg_tg_nodes,
            "url": "https://cp.cloudflare.com/generate_204",
            "interval": "5m",
            "tolerance": 50
        },
        {
            "type": "urltest",
            "tag": "AUTO-ALL",
            "outbounds": all_node_tags,
            "url": "https://cp.cloudflare.com/generate_204",
            "interval": "5m",
            "tolerance": 50
        }
    ] + nodes + [
        {
            "type": "direct",
            "tag": "direct"
        },
        {
            "type": "direct",
            "tag": "bypass"
        },
        {
            "type": "block",
            "tag": "block"
        },
        {
            "type": "dns",
            "tag": "dns-out"
        }
    ],
    "route": {
        "rules": [
            {
                "port": [53],
                "action": "hijack-dns"
            },
            {
                "protocol": ["dns"],
                "action": "hijack-dns"
            },
            {
                "ip_cidr": [
                    "224.0.0.0/3",
                    "ff00::/8"
                ],
                "source_ip_cidr": [
                    "224.0.0.0/3",
                    "ff00::/8"
                ],
                "action": "reject"
            },
            {
                "ip_is_private": True,
                "outbound": "direct"
            },
            {
                "geosite": "category-ads-all",
                "action": "reject"
            },
            {
                "domain": [
                    "ad.weixin.qq.com",
                    "as.weixin.qq.com",
                    "wxsnnsad.tc.qq.com",
                    "wxsnsdy.tc.qq.com",
                    "gdt.qq.com",
                    "e.qq.com",
                    "ad.qq.com",
                    "adnet.qq.com",
                    "adsense.html5.qq.com",
                    "ads-twitter.com",
                    "ads-api.twitter.com",
                    "ads-bidder-api.twitter.com",
                    "ads.twitter.com",
                    "ads-api.x.com",
                    "ads.x.com",
                    "analytics.twitter.com",
                    "analytics.x.com",
                    "p.twitter.com",
                    "scribe.twitter.com"
                ],
                "domain_suffix": [
                    "ads-twitter.com",
                    "ads.twitter.com"
                ],
                "action": "reject"
            },
            {
                "package_name": [
                    "com.tencent.mobileqq",
                    "cn.soulapp.android",
                    "com.tencent.mm",
                    "com.tencent.wetype",
                    "com.ss.android.ugc.aweme",
                    "com.dragon.read",
                    "com.phoenix.read",
                    "com.taobao.idlefish"
                ],
                "outbound": "direct"
            },
            {
                "geoip": [
                    "private",
                    "cn"
                ],
                "outbound": "direct"
            },
            {
                "geosite": "cn",
                "outbound": "direct"
            },
            {
                "domain": [
                    "localhost",
                    "localhost.localdomain"
                ],
                "domain_suffix": [
                    "local",
                    "lan",
                    "home.arpa",
                    "localdomain",
                    "internal"
                ],
                "outbound": "direct"
            },
            {
                "package_name": [
                    "org.telegram.messenger"
                ],
                "outbound": "SG-TG"
            },
            {
                "domain": [
                    "telegram.org",
                    "telegram.me",
                    "t.me",
                    "telegram-cdn.org",
                    "dns.google"
                ],
                "domain_suffix": [
                    "telegram.org",
                    "telegram.me",
                    "t.me",
                    "telegram-cdn.org"
                ],
                "ip_cidr": [
                    "8.8.8.8/32",
                    "8.8.4.4/32",
                    "2001:4860:4860::8888/128",
                    "2001:4860:4860::8844/128",
                    "91.108.4.0/22",
                    "91.108.8.0/22",
                    "91.108.12.0/22",
                    "91.108.16.0/22",
                    "91.108.20.0/22",
                    "91.108.56.0/22",
                    "149.154.160.0/20",
                    "2001:b28:f23d::/48",
                    "2001:b28:f23f::/48",
                    "2001:67c:4e8::/48"
                ],
                "outbound": "SG-TG"
            },
            {
                "package_name": [
                    "com.google.android.apps.authenticator2",
                    "com.openai.chatgpt",
                    "com.google.android.configupdater",
                    "com.google.android.gm",
                    "com.google.android.googlequicksearchbox",
                    "com.google.ar.core",
                    "com.android.vending",
                    "com.google.android.gms",
                    "com.google.android.syncadapters.calendar",
                    "com.google.android.gsf",
                    "ai.x.grok",
                    "notion.id",
                    "ai.perplexity.app.android",
                    "org.thoughtcrime.securesms",
                    "com.google.android.apps.docs",
                    "com.google.android.apps.docs.editors.docs",
                    "com.android.chrome",
                    "com.google.android.ondevicepersonalization.services",
                    "com.google.android.federatedcompute",
                    "com.google.android.sdksandbox",
                    "com.google.android.adservices.api"
                ],
                "outbound": "US-Core"
            },
            {
                "domain_suffix": [
                    "anthropic.com",
                    "claude.ai",
                    "perplexity.ai",
                    "groq.com",
                    "x.ai",
                    "generativelanguage.googleapis.com",
                    "ai.google.dev",
                    "cohere.com",
                    "midjourney.com",
                    "github.com",
                    "githubusercontent.com",
                    "githubassets.com",
                    "github.io",
                    "githubcopilot.com",
                    "gitlab.com",
                    "npmjs.org",
                    "npmjs.com",
                    "nodejs.org",
                    "pypi.org",
                    "pythonhosted.org",
                    "docker.com",
                    "docker.io",
                    "stackoverflow.com",
                    "cloudflare.com",
                    "amazonaws.com",
                    "oaistatic.com",
                    "oaiusercontent.com",
                    "chatgpt.com",
                    "sora.com",
                    "deepmind.google",
                    "gemini.google.com",
                    "cursor.sh",
                    "cursor.com",
                    "v0.dev",
                    "google.com",
                    "google.com.hk",
                    "google.cn",
                    "googleapis.com",
                    "gstatic.com",
                    "googleusercontent.com",
                    "googlevideo.com",
                    "googlelabs.com",
                    "googleadservices.com",
                    "googleanalytics.com",
                    "googleblog.com",
                    "googledrive.com",
                    "googlegroups.com",
                    "googlemail.com",
                    "googleplay.com",
                    "googlesource.com",
                    "gvt1.com",
                    "gvt2.com",
                    "1e100.net",
                    "2mdn.net",
                    "appspot.com",
                    "chrome.com",
                    "chromebook.com",
                    "chromecast.com",
                    "chromeexperiments.com",
                    "crx4chrome.com",
                    "gmail.com",
                    "ggpht.com",
                    "g.co",
                    "goo.gl",
                    "withgoogle.com",
                    "android.com",
                    "google-analytics.com",
                    "doubleclick.net",
                    "recaptcha.net",
                    "openai.com"
                ],
                "ip_cidr": [
                    "2001:4860::/32",
                    "2404:6800::/32",
                    "2607:f8b0::/32",
                    "2800:3f0::/32",
                    "2a00:1450::/32",
                    "2c0f:fb50::/32",
                    "172.217.0.0/16",
                    "142.250.0.0/15",
                    "173.194.0.0/16",
                    "74.125.0.0/16",
                    "216.58.192.0/19",
                    "64.233.160.0/19",
                    "66.249.64.0/19",
                    "72.14.192.0/18",
                    "108.177.0.0/17",
                    "209.85.128.0/17",
                    "216.239.32.0/19"
                ],
                "outbound": "US-Core"
            },
            {
                "package_name": [
                    "com.alibaba.aliexpresshd",
                    "com.twitter.android",
                    "com.google.android.youtube",
                    "com.whatsapp"
                ],
                "outbound": "US-Media"
            },
            {
                "domain_suffix": [
                    "amazon.com",
                    "shopify.com",
                    "myshopify.com",
                    "aliexpress.com",
                    "alicdn.com",
                    "temu.com",
                    "ebay.com",
                    "ebaystatic.com",
                    "etsy.com",
                    "stripe.com",
                    "paypal.com",
                    "youtube.com",
                    "youtu.be",
                    "youtube-nocookie.com",
                    "googlevideo.com",
                    "ytimg.com",
                    "twitter.com",
                    "x.com",
                    "twimg.com",
                    "t.co",
                    "facebook.com",
                    "fbcdn.net",
                    "messenger.com",
                    "instagram.com",
                    "cdninstagram.com",
                    "tiktok.com",
                    "tiktokcdn.com",
                    "byteoversea.com",
                    "musical.ly",
                    "netflix.com",
                    "nflxvideo.net",
                    "nflximg.net",
                    "nflxso.net",
                    "disneyplus.com",
                    "disney-plus.net",
                    "bamgrid.com",
                    "spotify.com",
                    "scdn.co",
                    "spotifycdn.com"
                ],
                "outbound": "US-Media"
            }
        ],
        "final": "US-Core",
        "auto_detect_interface": True
    },
    "experimental": {
        "clash_api": {
            "external_controller": "127.0.0.1:9090",
            "secret": "e35ab90eb78f4394a1d30e8b6e581113",
            "store_selected": True,
            "cache_file": "clash.db"
        }
    }
}

with open('asteria_v1.0.json', 'w', encoding='utf-8') as f:
    json.dump(config, f, ensure_ascii=False, indent=2)

print('Successfully generated asteria_v1.0.json')

import urllib.parse
links = []
for p in nodes:
    ty = p.get("type")
    name = p.get("tag", "")
    if ty == "anytls":
        server = p["server"]
        port = p["server_port"]
        pwd = p["password"]
        sni = p.get("tls", {}).get("server_name", "")
        insecure = p.get("tls", {}).get("insecure", False)
        params = []
        if insecure:
            params.append("insecure=1")
        if sni:
            params.append(f"sni={sni}")
        query = ("?" + "&".join(params)) if params else ""
        link = f"anytls://{pwd}@{server}:{port}{query}#{urllib.parse.quote(name)}"
        links.append(link)
    elif ty == "vless":
        server = p["server"]
        port = p["server_port"]
        uuid = p["uuid"]
        flow = p.get("flow", "xtls-rprx-vision")
        sni = p.get("tls", {}).get("server_name", "www.amazon.com")
        fp = p.get("tls", {}).get("utls", {}).get("fingerprint", "chrome")
        link = f"vless://{uuid}@{server}:{port}?encryption=none&flow={flow}&security=tls&sni={sni}&fp={fp}&type=tcp&headerType=none#{urllib.parse.quote(name)}"
        links.append(link)

with open('asteria_nodes_links.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(links))

print(f'Successfully generated asteria_nodes_links.txt with {len(links)} links')

