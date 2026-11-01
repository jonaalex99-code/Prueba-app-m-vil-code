"""Genera codes.json para la app a partir de la base Flipper-IRDB (TVs) y tv.ir.

Uso: python3 -I gen.py <dir TVs> <tv.ir> <salida.json>
Todos los códigos se convierten a patrones crudos (microsegundos, empezando
con pulso encendido) listos para ConsumerIrManager.transmit().
"""
import sys, os, json, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from parse import load_all, parse
from families import ALIASES, REV

TVS, TVIR, OUT = sys.argv[1], sys.argv[2], sys.argv[3]

# ---------------------------------------------------------------- encoders
def le_bytes(hexstr):
    return [int(x, 16) for x in hexstr.split()]

def le32(hexstr):
    b = le_bytes(hexstr)
    return b[0] | b[1] << 8 | b[2] << 16 | b[3] << 24

def bits_lsb(value, n):
    return [(value >> i) & 1 for i in range(n)]

def bytes_bits(bs):
    out = []
    for b in bs:
        out += bits_lsb(b, 8)
    return out

def pdwm(header, bits, mark, sp0, sp1, stop=True):
    p = list(header)
    for b in bits:
        p += [mark, sp1 if b else sp0]
    if stop:
        p.append(mark)
    return p

def merge_levels(halves):
    """halves: lista de (nivel, duración). Devuelve patrón que empieza en marca."""
    out = []
    for lvl, d in halves:
        if out and out[-1][0] == lvl:
            out[-1][1] += d
        else:
            out.append([lvl, d])
    while out and out[0][0] == 0:
        out.pop(0)
    while out and out[-1][0] == 0:
        out.pop()
    return [d for _, d in out]

def enc_nec(proto, a, c):
    A, C = le32(a), le32(c)
    if proto == 'NEC':
        data = bytes_bits([A & 0xFF, ~A & 0xFF, C & 0xFF, ~C & 0xFF])
    elif proto == 'NECext':
        data = bits_lsb(A & 0xFFFF, 16) + bits_lsb(C & 0xFFFF, 16)
    elif proto == 'NEC42':
        data = bits_lsb(A & 0x1FFF, 13) + bits_lsb(~A & 0x1FFF, 13) + bits_lsb(C & 0xFF, 8) + bits_lsb(~C & 0xFF, 8)
    elif proto == 'NEC42ext':
        data = bits_lsb(A & 0x3FFFFFF, 26) + bits_lsb(C & 0xFFFF, 16)
    return 38000, pdwm([9000, 4500], data, 560, 560, 1690)

def enc_samsung(a, c):
    A, C = le32(a) & 0xFF, le32(c) & 0xFF
    return 38000, pdwm([4500, 4500], bytes_bits([A, A, C, ~C & 0xFF]), 550, 550, 1650)

def enc_rca(a, c):
    A, C = le32(a) & 0xF, le32(c) & 0xFF
    data = A | C << 4 | (~A & 0xF) << 12 | (~C & 0xFF) << 16
    return 38000, pdwm([4000, 4000], bits_lsb(data, 24), 500, 1000, 2000)

def enc_kaseikyo(a, c):
    A, C = le32(a), le32(c)
    ident = (A >> 24) & 3
    vendor = (A >> 8) & 0xFFFF
    g1, g2 = (A >> 4) & 0xF, A & 0xF
    d = [vendor & 0xFF, vendor >> 8, 0, 0, 0, 0]
    par = d[0] ^ d[1]
    par = (par & 0xF) ^ (par >> 4)
    d[2] = (par & 0xF) | (g1 << 4)
    d[3] = (g2 & 0xF) | ((C & 0xF) << 4)
    d[4] = ((ident << 6) | (C >> 4)) & 0xFF
    d[5] = d[2] ^ d[3] ^ d[4]
    return 38000, pdwm([3456, 1728], bytes_bits(d), 432, 432, 1296)

def enc_sirc(proto, a, c):
    A, C = le32(a), le32(c)
    if proto == 'SIRC':
        data, n = (C & 0x7F) | (A & 0x1F) << 7, 12
    elif proto == 'SIRC15':
        data, n = (C & 0x7F) | (A & 0xFF) << 7, 15
    else:
        data, n = (C & 0x7F) | (A & 0x1FFF) << 7, 20
    frame = [2400, 600]
    for b in bits_lsb(data, n):
        frame += [1200 if b else 600, 600]
    p = []
    for i in range(3):
        f = list(frame)
        if i < 2:
            f[-1] = 45000 - sum(frame[:-1])  # completa el periodo de 45 ms
        else:
            f.pop()
        p += f
    return 40000, p

def enc_rc5(a, c, toggle):
    A, C = le32(a) & 0x1F, le32(c) & 0x3F
    bits = [1, 1, toggle] + [(A >> i) & 1 for i in range(4, -1, -1)] + [(C >> i) & 1 for i in range(5, -1, -1)]
    h = []
    for b in bits:  # RC5: 1 = espacio->pulso, 0 = pulso->espacio
        h += [(0, 889), (1, 889)] if b else [(1, 889), (0, 889)]
    return 36000, merge_levels(h)

def enc_rc6(a, c, toggle):
    A, C = le32(a) & 0xFF, le32(c) & 0xFF
    h = [(1, 2666), (0, 889)]
    bits = [(1, 444), (0, 444), (0, 444), (0, 444), (toggle, 889)]
    bits += [((A >> i) & 1, 444) for i in range(7, -1, -1)]
    bits += [((C >> i) & 1, 444) for i in range(7, -1, -1)]
    for b, t in bits:  # RC6: 1 = pulso->espacio, 0 = espacio->pulso
        h += [(1, t), (0, t)] if b else [(0, t), (1, t)]
    return 36000, merge_levels(h)

def enc_pioneer(a, c):
    A, C = le32(a) & 0xFF, le32(c) & 0xFF
    frame = pdwm([8500, 4225], bytes_bits([A, ~A & 0xFF, C, ~C & 0xFF]) + [0], 500, 500, 1500)
    return 40000, frame + [26000] + frame

def trim_raw(data):
    """Conserva como máximo 3 tramas y ~450 ms de una captura cruda."""
    vals = [int(x) for x in data.split()]
    out, frames, total = [], 1, 0
    for i, v in enumerate(vals):
        v = max(v, 10)
        is_space = i % 2 == 1
        if is_space and v >= 8000:
            if frames >= 3 or total + v > 450000:
                break
            frames += 1
        out.append(v)
        total += v
    if len(out) % 2 == 0:
        out.pop()
    return out

def encode(sig):
    """Devuelve lista de (freq, patrón) – dos variantes si hay bit de alternancia."""
    if sig.get('type') == 'raw':
        f = int(float(sig.get('frequency', 38000)))
        p = trim_raw(sig['data'])
        return [(f, p)] if len(p) >= 3 else []
    proto, a, c = sig['protocol'], sig['address'], sig['command']
    if proto in ('NEC', 'NECext', 'NEC42', 'NEC42ext'):
        return [enc_nec(proto, a, c)]
    if proto == 'Samsung32':
        return [enc_samsung(a, c)]
    if proto == 'RCA':
        return [enc_rca(a, c)]
    if proto == 'Kaseikyo':
        return [enc_kaseikyo(a, c)]
    if proto in ('SIRC', 'SIRC15', 'SIRC20'):
        return [enc_sirc(proto, a, c)]
    if proto == 'RC5':
        return [enc_rc5(a, c, 0), enc_rc5(a, c, 1)]
    if proto == 'RC6':
        return [enc_rc6(a, c, 0), enc_rc6(a, c, 1)]
    if proto == 'Pioneer':
        return [enc_pioneer(a, c)]
    return []  # protocolo no soportado (RC5X, etc.)

# --------------------------------------------- identidad para no duplicar
def identity(freq, p):
    """Decodifica NEC/Samsung para detectar el mismo código capturado en crudo."""
    if len(p) >= 67:
        hdr = None
        if 8000 < p[0] < 10000 and 3800 < p[1] < 5200:
            hdr = 'nec'
        elif 4000 < p[0] < 5000 and 4000 < p[1] < 5000:
            hdr = 'sam'
        if hdr:
            v = 0
            ok = True
            for i in range(32):
                m, s = p[2 + 2 * i], p[3 + 2 * i]
                if not (300 < m < 800 and 300 < s < 2000):
                    ok = False
                    break
                if s > 1100:
                    v |= 1 << i
            if ok:
                return (hdr, v)
    return ('raw', freq // 1000, tuple(round(x / 100) for x in p[:80]))

# -------------------------------------------------------------- familias
FAMILIES = [
    # id, nombre visible, protocolo, dirección, universal (se usa sin detectar)
    ('tcl_roku', 'TCL Roku TV · Onn', 'NECext', 'EA C7 00 00', True),
    ('tcl', 'TCL · Thomson', 'RCA', '0F 00 00 00', True),
    ('lg', 'LG · Vizio · Hisense', 'NEC', '04 00 00 00', True),
    ('samsung', 'Samsung', 'Samsung32', '07 00 00 00', True),
    ('sony', 'Sony', 'SIRC', '01 00 00 00', True),
    ('hisense', 'Hisense · BGH · Manta', 'NECext', '00 BF 00 00', True),
    ('xiaomi', 'Xiaomi', 'raw', 'Xiaomi', True),
    ('toshiba', 'Toshiba', 'NEC', '40 00 00 00', True),
    ('toshiba2', 'Toshiba · Element', 'NECext', '02 7D 00 00', True),
    ('insignia', 'RCA · Insignia · Dynex', 'NECext', '86 05 00 00', True),
    ('philips', 'Philips', 'RC6', '00 00 00 00', True),
    ('panasonic', 'Panasonic', 'Kaseikyo', ['80 02 20 00', '80 02 20 01'], True),
    ('philips_rc5', 'Philips · Grundig', 'RC5', '00 00 00 00', False),
    ('telefunken', 'Telefunken · Medion', 'RC5', '01 00 00 00', False),
    ('sharp', 'Sharp · Apex · Blaupunkt', 'NECext', '00 7F 00 00', False),
    ('rca_nec', 'RCA · Elements', 'NEC', '20 00 00 00', False),
    ('rca2', 'RCA · Manta', 'NEC', 'A0 00 00 00', False),
    ('generic01', 'Strong · genéricos', 'NEC', '01 00 00 00', False),
    ('sanyo', 'Sanyo', 'NEC', '38 00 00 00', False),
    ('hitachi', 'Hitachi', 'RC5', '03 00 00 00', False),
    ('jvc', 'JVC', 'Samsung32', '0E 00 00 00', False),
    ('emerson', 'Emerson · Funai', 'NECext', '84 E0 00 00', False),
    ('soniq', 'Soniq', 'NECext', '72 DD 00 00', False),
    ('akai', 'Akai · Bauhn', 'NECext', '01 72 00 00', False),
    ('roku', 'Roku (reproductor)', 'NECext', 'EA C2 00 00', False),
]

allb = load_all(TVS)
all_sigs = [s for sigs in allb.values() for s in sigs]

def norm_hex(h):
    return ' '.join(x.upper() for x in h.split())

def pack(codes):
    """codes: lista [(f,p), ...] -> objeto JSON compacto."""
    f, p = codes[0]
    o = {'f': f, 'p': p}
    if len(codes) > 1:
        o['t'] = codes[1][1]
    return o

fam_out = []
fam_index = {}
for fid, name, proto, addr, universal in FAMILIES:
    keys = {}
    if proto == 'raw':
        for s in allb[addr]:
            n = s['name'].lower()
            if n == 'open':
                n = 'power'
            if n in REV and s.get('type') == 'raw':
                k = REV[n][0]
                if k not in keys:
                    keys[k] = pack(encode(s))
        fam_index[fid] = None
    else:
        addrs = addr if isinstance(addr, list) else [addr]
        votes = collections.defaultdict(collections.Counter)
        for s in all_sigs:
            if s.get('type') != 'parsed' or s['protocol'] != proto or norm_hex(s['address']) not in addrs:
                continue
            n = s['name'].lower()
            if n not in REV:
                continue
            k, pri = REV[n]
            votes[k][(pri, norm_hex(s['address']), norm_hex(s['command']))] += 1
        for k, cnt in votes.items():
            best_pri = min(pri for pri, _, _ in cnt)
            _, (a, cmd) = max((n, (a, c)) for (pri, a, c), n in cnt.items() if pri == best_pri)
            enc = encode({'type': 'parsed', 'protocol': proto, 'address': a, 'command': cmd})
            if enc:
                keys[k] = pack(enc)
        for a in addrs:
            fam_index[(proto, a)] = fid
    fam_out.append({'id': fid, 'name': name, 'universal': universal, 'keys': keys})

# ---------------------------------------------------------- barrido power
sweep, seen = [], set()

def add_sweep(sig, fid):
    enc = encode(sig)
    if not enc:
        return
    ident = identity(*enc[0])
    if ident in seen:
        return
    seen.add(ident)
    o = pack(enc[:1])
    if fid:
        o['fam'] = fid
    sweep.append(o)

for fam in fam_out:
    pw = fam['keys'].get('power')
    if pw:
        ident = identity(pw['f'], pw['p'])
        if ident not in seen:
            seen.add(ident)
            sweep.append({'f': pw['f'], 'p': pw['p'], 'fam': fam['id']})

def fam_of(sig):
    if sig.get('type') != 'parsed':
        return None
    return fam_index.get((sig['protocol'], norm_hex(sig['address'])))

for s in parse(TVIR):
    if s['name'].lower() == 'power':
        add_sweep(s, fam_of(s))
n_tvir = len(sweep)
for s in all_sigs:
    if s['name'].lower() in ('power', 'on_off', 'power_toggle'):
        add_sweep(s, fam_of(s))

out = {
    'source': 'Flipper-IRDB (Lucaslhm/Flipper-IRDB) y biblioteca universal tv.ir de Flipper Zero',
    'keys': list(ALIASES.keys()),
    'families': fam_out,
    'sweep': sweep,
}
with open(OUT, 'w') as fh:
    json.dump(out, fh, separators=(',', ':'))

dur = lambda o: sum(o['p'])
print('familias:', len(fam_out))
for f in fam_out:
    print('  %-12s %2d teclas  faltan: %s' % (f['id'], len(f['keys']), sorted(set(ALIASES) - set(f['keys']))))
print('barrido:', len(sweep), '(tv.ir+familias:', n_tvir, ')  duración total aprox: %.1fs' % (sum(dur(o) for o in sweep) / 1e6 + 0.05 * len(sweep)))
print('tamaño JSON: %d KB' % (os.path.getsize(OUT) // 1024))
