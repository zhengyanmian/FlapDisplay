# -*- coding: utf-8 -*-
"""校验所有存档的 level.dat / level.dat_old / region 文件完整性"""
import gzip, os, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

base = r'C:\.minecraft\versions\1.21.1-NeoForge_21.1.241\saves'

def check_level(path):
    try:
        d = gzip.open(path, 'rb').read()
        return f'OK ({len(d)}B decompressed, NBT root type={d[0]})'
    except Exception as e:
        return f'!! CORRUPT: {type(e).__name__}: {e}'

def check_region_dir(rdir):
    bad = []
    n = 0
    for root, _, files in os.walk(rdir):
        for f in files:
            if f.endswith('.mca'):
                n += 1
                p = os.path.join(root, f)
                sz = os.path.getsize(p)
                if sz == 0:
                    bad.append(f + ' ZERO-BYTE')
                elif sz % 4096 != 0:
                    bad.append(f + f' size={sz} (not 4K aligned)')
    return n, bad

for w in os.listdir(base):
    wd = os.path.join(base, w)
    if not os.path.isdir(wd):
        continue
    print('=' * 60)
    print('WORLD:', w)
    for f in ('level.dat', 'level.dat_old'):
        p = os.path.join(wd, f)
        if os.path.exists(p):
            print(' ', f, check_level(p))
        else:
            print(' ', f, 'MISSING')
    for sub in ('region', 'DIM-1/region', 'DIM1/region', 'entities', 'poi'):
        rd = os.path.join(wd, sub.replace('/', os.sep))
        if os.path.isdir(rd):
            n, bad = check_region_dir(rd)
            print(f'  {sub}: {n} .mca files' + ('' if not bad else '  BAD: ' + '; '.join(bad[:8])))
