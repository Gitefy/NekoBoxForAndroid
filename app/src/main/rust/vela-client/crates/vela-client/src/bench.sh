#!/bin/bash
echo "=== Testing 10 Concurrent Streams (100KB each, total 1MB) through SOCKS5 proxy ==="
for i in $(seq 1 10); do
  curl -m 30 -x socks5h://127.0.0.1:1080 -s -o /dev/null -w "stream $i: code=%{http_code} size=%{size_download} time=%{time_total}s speed=%{speed_download} B/s\n" https://speed.cloudflare.com/__down?bytes=102400 &
done
wait
echo "=== Concurrent Test Complete ==="
