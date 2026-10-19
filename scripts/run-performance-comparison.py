"""Measure both JMH harnesses on the same two CPUs, JVM and options; retain affinity evidence.

Windows selects distinct fast cores on hybrid CPUs. Linux uses the first two allowed CPUs.
Only this process and its children are affinitized; system scheduling settings are unchanged.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys


def decode_cpu_sets(data):
    """Parse the variable-sized Windows SYSTEM_CPU_SET_INFORMATION records."""
    records, offset = [], 0
    while offset < len(data):
        if len(data) - offset < 8:
            raise ValueError('Truncated CPU-set header')
        size, kind = struct.unpack_from('<II', data, offset)
        if size < 8 or offset + size > len(data):
            raise ValueError('Invalid CPU-set record size')
        if kind == 0:
            if size < 32:
                raise ValueError('Truncated CPU-set information')
            records.append(dict(group=struct.unpack_from('<H', data, offset+12)[0],
                                logical=data[offset+14], core=data[offset+15],
                                efficiency=data[offset+18], flags=data[offset+19]))
        offset += size
    return records


def select_cpus(allowed, topology=()):
    allowed = set(allowed)
    # Higher EfficiencyClass means faster, less power-efficient processors. Use one logical CPU per core.
    # https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-system_cpu_set_information
    eligible = [r for r in topology if r['group'] == 0 and r['logical'] in allowed
                and (not r['flags'] & 2 or r['flags'] & 4)]
    if eligible:
        fastest = max(r['efficiency'] for r in eligible)
        cores = {}
        for row in sorted(eligible, key=lambda r: r['logical']):
            if row['efficiency'] == fastest:
                cores.setdefault(row['core'], row['logical'])
        selected = [cores[core] for core in sorted(cores, reverse=True)[:2]]
    else:
        selected = sorted(allowed)[:2]
    if len(selected) != 2:
        raise ValueError('Performance comparison requires two eligible distinct CPUs/cores')
    return sorted(selected)


def pin_cpus():
    if sys.platform == 'linux':
        allowed = sorted(os.sched_getaffinity(0))
        selected = select_cpus(allowed)
        os.sched_setaffinity(0, selected)
        if set(os.sched_getaffinity(0)) != set(selected):
            raise RuntimeError('CPU affinity was not applied')
        return dict(platform=sys.platform, allowed=allowed, selected=selected)
    if os.name != 'nt':
        raise RuntimeError('CPU-affinity measurement is supported on Linux and Windows')
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    get_sets = kernel.GetSystemCpuSetInformation
    get_sets.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.c_void_p, ctypes.c_ulong]
    get_sets.restype = ctypes.c_int
    handle = ctypes.c_void_p(-1)  # GetCurrentProcess pseudo-handle
    length = ctypes.c_ulong()
    get_sets(None, 0, ctypes.byref(length), handle, 0)
    if not length.value:
        raise ctypes.WinError(ctypes.get_last_error())
    buffer = ctypes.create_string_buffer(length.value)
    if not get_sets(buffer, length.value, ctypes.byref(length), handle, 0):
        raise ctypes.WinError(ctypes.get_last_error())
    topology = decode_cpu_sets(buffer.raw[:length.value])
    get_mask = kernel.GetProcessAffinityMask
    get_mask.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]
    get_mask.restype = ctypes.c_int
    mask, system = ctypes.c_size_t(), ctypes.c_size_t()
    if not get_mask(handle, ctypes.byref(mask), ctypes.byref(system)):
        raise ctypes.WinError(ctypes.get_last_error())
    allowed = [i for i in range(ctypes.sizeof(mask)*8) if mask.value & (1 << i)]
    selected = select_cpus(allowed, topology)
    selected_mask = sum(1 << i for i in selected)
    set_mask = kernel.SetProcessAffinityMask
    set_mask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    set_mask.restype = ctypes.c_int
    if not set_mask(handle, selected_mask):
        raise ctypes.WinError(ctypes.get_last_error())
    if not get_mask(handle, ctypes.byref(mask), ctypes.byref(system)) or mask.value != selected_mask:
        raise RuntimeError('CPU affinity was not applied')
    return dict(platform=sys.platform, allowed=allowed, selected=selected, topology=topology)


def command(java, jar, benchmark, threads, output, warmups=5):
    return [str(java), '-jar', str(jar), benchmark, '-wi', str(warmups), '-i', '8', '-w', '1s', '-r', '1s',
            '-f', '2', '-t', str(threads), '-foe', 'true', '-jvmArgs', '-Xmx1024m -XX:ActiveProcessorCount=2',
            '-prof', 'gc', '-rf', 'json', '-rff', str(output)]


def merge_scaling_reports(output, name):
    """Retain complete original JMH records from both scaling groups for the existing strict gate."""
    records = []
    for suffix in ['-scaling', '-isomorphism']:
        records.extend(json.loads((output/(name+suffix+'.json')).read_text(encoding='utf-8')))
    (output/(name+'.json')).write_text(json.dumps(records, indent=2)+'\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--java', default=shutil.which('java'))
    args = parser.parse_args()
    if not args.java:
        parser.error('Java executable was not found')
    jars = {name: jar.resolve(strict=True) for name, jar in [('baseline', args.baseline), ('candidate', args.candidate)]}
    args.output.mkdir(parents=True, exist_ok=True)
    affinity = pin_cpus()
    print('Pinned comparison CPUs: ' + ', '.join(map(str, affinity['selected'])), flush=True)
    profile = dict(affinity=affinity, java=args.java,
                   javaVersion=subprocess.check_output([args.java, '-version'], stderr=subprocess.STDOUT).decode(errors='replace'),
                   jars={k: dict(path=str(v), sha256=hashlib.file_digest(v.open('rb'), 'sha256').hexdigest()) for k,v in jars.items()},
                   commands=[])
    profile_path = args.output/'profile.json'
    # Isomorphism was still compiling during early measured iterations after five seconds of warmup.
    # Give BOTH revisions fifteen warmup iterations for that workload; keep every measured sample.
    stages = [
        (r'.*ScalingBenchmark\.(?!symmetricIsomorphism$).*', 1, '-scaling', 5),
        (r'.*CoreScalingBenchmark\.symmetricIsomorphism', 1, '-isomorphism', 15),
        ('.*SharedValidationBenchmark.*', 4, '-contention', 5),
    ]
    for benchmark, threads, suffix, warmups in stages:
        for name, jar in jars.items():
            label = name + suffix
            argv = command(args.java, jar, benchmark, threads, args.output/(label+'.json'), warmups)
            profile['commands'].append(argv)
            profile_path.write_text(json.dumps(profile, indent=2)+'\n', encoding='utf-8')
            print('Measuring ' + label, flush=True)
            with (args.output/(label+'.log')).open('w', encoding='utf-8') as log:
                subprocess.run(argv, check=True, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    for name in jars:
        merge_scaling_reports(args.output, name)
    print('All measurements complete; apply scripts/check-performance.py to both report pairs', flush=True)


if __name__ == '__main__':
    main()
