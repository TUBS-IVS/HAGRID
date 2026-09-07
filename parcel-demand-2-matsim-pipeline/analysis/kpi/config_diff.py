# -*- coding: utf-8 -*-
"""Full parameter diff between two MATSim output_config.xml files.

Why this exists: a paired run comparison is only valid if the two runs differ in the one
parameter under test. The POPHASH check guards the input population, but the defect that
invalidated the basew21 pair sat in the config -- multiModeDrt/drt/numberOfThreads was 14
in one half and 12 in the other, worth ~103 DRT rides (METHODS-LOG 2.59 / 3.14). Hash the
population AND diff the config.

usage:  python config_diff.py A.output_config.xml B.output_config.xml [--labels A B]

Run-tag substrings and machine-specific path prefixes are normalised away, so only real
parameter differences remain. Exit code 1 if any substantive difference is found.
"""
import argparse
import re
import sys

BS = chr(92)
TAG_RE = re.compile(r'DRT_(?:BASELINE|SHAREDUSE|MODULAR)_\d+_[A-Za-z0-9_]+')
HOME_RE = re.compile(r'(?i)[a-z]:/Users/[^/]+')
NODE_RE = re.compile(
    r'<(module|parameterset)\s+(?:name|type)="([^"]+)"'
    r'|</(?:module|parameterset)>'
    r'|<param name="([^"]+)" value="([^"]*)"')

# differences in these paths are bookkeeping, not model behaviour
COSMETIC = ('controller/outputDirectory',)


def params(path):
    """Map 'module/parameterset/param' -> list of normalised values."""
    with open(path, encoding='utf-8', errors='replace') as fh:
        text = fh.read()
    out, stack = {}, []
    for m in NODE_RE.finditer(text):
        if m.group(1):
            stack.append(m.group(2))
        elif m.group(3) is None:
            if stack:
                stack.pop()
        else:
            key = "/".join(stack) + "/" + m.group(3)
            val = m.group(4).replace(BS, '/')
            val = TAG_RE.sub('<RUN>', val)
            val = HOME_RE.sub('<HOME>', val)
            val = val.replace('/./', '/')
            out.setdefault(key, []).append(val)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('a')
    ap.add_argument('b')
    ap.add_argument('--labels', nargs=2, default=None)
    args = ap.parse_args()
    la, lb = args.labels if args.labels else ('A', 'B')

    A, B = params(args.a), params(args.b)
    keys = sorted(set(A) | set(B))
    diffs = [(k, A.get(k), B.get(k)) for k in keys if A.get(k) != B.get(k)]
    real = [d for d in diffs if not d[0].startswith(COSMETIC)]

    print("%d parameter paths compared; %d differ (%d after dropping bookkeeping paths)"
          % (len(keys), len(diffs), len(real)))
    if not diffs:
        print("IDENTICAL - the two runs are configured the same in every parameter.")
        return 0
    for k, a, b in diffs:
        mark = '   ' if k.startswith(COSMETIC) else ' * '
        print("%s%s" % (mark, k))
        print("      %-22s %s" % (la, a[0] if a and len(a) == 1 else a))
        print("      %-22s %s" % (lb, b[0] if b and len(b) == 1 else b))
    if real:
        print()
        print("SUBSTANTIVE DIFFERENCES: %d. A paired comparison across these is not valid"
              % len(real))
        print("unless the differing parameter IS the one under test.")
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
