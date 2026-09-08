import json
import base64
import struct
import re

with open('asteria_backup_20260907-164621-313.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

print('--- Profiles ---')
for i, p in enumerate(data['profiles']):
    raw = base64.urlsafe_b64decode(p + '==')
    m = re.search(rb'sn://[^\x00\x01\x02\x03\x04\x05\x06\x07\x08]+', raw)
    if m:
        link = m.group(0).decode('utf-8', errors='ignore')
        print(f'[{i+1}] {link}')
    else:
        print(f'[{i+1}] No link found, raw: {raw[:60]}')
