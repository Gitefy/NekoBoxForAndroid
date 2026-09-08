import copy
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
src = root / 'asteria_v1.0.json'
dst = root / 'asteria_v1.0.1.json'
old = json.loads(src.read_text(encoding='utf-8'))
new = copy.deepcopy(old)
tags = set()
for section in ('dns', 'route'):
    for rule in new[section]['rules']:
        for key in ('geosite', 'geoip'):
            if key not in rule:
                continue
            values = rule.pop(key)
            values = [values] if isinstance(values, str) else values
            # A preceding ip_is_private rule already handles geoip:private.
            refs = [f'{key}:{value}' for value in values if not (key == 'geoip' and value == 'private')]
            assert refs
            rule['rule_set'] = refs
            tags.update(refs)
new['route']['rule_set'] = [dict(type='local', tag=t, format='binary', path=t) for t in sorted(tags)]
for i, rule in enumerate(new['route']['rules']):
    if 'source_ip_cidr' in rule and 'ip_cidr' in rule:
        new['route']['rules'][i] = dict(type='logical', mode='or', rules=[
            {'ip_cidr': rule['ip_cidr']}, {'source_ip_cidr': rule['source_ip_cidr']}], action=rule['action'])
api = new['experimental']['clash_api']
new['experimental']['cache_file'] = dict(enabled=True, path=api.pop('cache_file'))
api.pop('store_selected')
# Audit every non-target section and rule; never print node data or credentials.
for key in ('log', 'inbounds', 'outbounds'):
    assert old[key] == new[key]
for section in ('dns', 'route'):
    assert len(old[section]['rules']) == len(new[section]['rules'])
    for a, b in zip(old[section]['rules'], new[section]['rules']):
        if not any(k in a for k in ('geoip', 'geosite', 'source_ip_cidr')):
            assert a == b
    for k, v in old[section].items():
        if k != 'rules':
            assert v == new[section][k]
outbound_tags = [o['tag'] for o in new['outbounds']]
assert len(outbound_tags) == len(set(outbound_tags))
for o in new['outbounds']:
    assert all(t in outbound_tags for t in o.get('outbounds', []))
for r in new['route']['rules']:
    assert 'outbound' not in r or r['outbound'] in outbound_tags
assert new['route']['final'] in outbound_tags
assert not dst.exists(), 'Do not overwrite an existing version'
dst.write_text(json.dumps(new, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
for p in (src, dst):
    print(p.name, p.stat().st_size, hashlib.sha256(p.read_bytes()).hexdigest())
print('Non-target audit PASS; references PASS; rule counts preserved')
