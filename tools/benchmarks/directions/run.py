"""Reproduce the Hannover directional jsprit benchmark, without changing installed JARs."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input-root', type=Path, required=True)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--classpath-file', type=Path, required=True)
parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', '.')))
parser.add_argument('--penalty', type=float, choices=[0, .1, 1], default=.1)
parser.add_argument('--seed', type=int, default=42)
parser.add_argument('--iterations', type=int, default=2000)
parser.add_argument('--seconds', type=float, help='Common elapsed deadline; reserves 0.5 s for finalization')
parser.add_argument('--profile', choices=['default', 'small', 'string'], default='default')
parser.add_argument('--native', action='store_true', help='Disable all three optimizations')
parser.add_argument('--no-position-pruning', action='store_true', help='Disable the proven directional bound for paired comparisons')
parser.add_argument('--validate-insertions', action='store_true', help='Enable expensive candidate spot checks; disable in both arms for timing')
args = parser.parse_args()
if args.seconds is not None and args.seconds <= .5:
    parser.error('--seconds must exceed the 0.5 second finalization reserve')
if args.seconds is None and args.profile != 'default':
    parser.error('Alternative profiles require --seconds and the common cooling clock')
if args.iterations < 1:
    parser.error('--iterations must be positive')

here = Path(__file__).resolve().parent
repo = here.parents[2]
out = args.output_dir.resolve()
out.mkdir(parents=True, exist_ok=False)  # Never replace an earlier measurement.
jars = [Path(p) for p in args.classpath_file.read_text().strip().split(os.pathsep) if p.endswith('.jar')]
assert jars and all(p.is_file() for p in jars), 'Classpath must contain existing dependency JARs'
native = [p for p in jars if p.name.startswith('jsprit-core-')]
assert len(native) == 1 and native[0].name == 'jsprit-core-2.0.0.jar', 'Use only native jsprit 2.0.0'
classes = out / 'classes'
classes.mkdir()
src = repo / 'hagrid/simulation/src/main/java'
sources = sorted((here / 'src').glob('*.java')) + [
    src / 'com/graphhopper/jsprit/core/algorithm/recreate/HagridServiceInsertionCalculator.java',
    src / 'hagrid/core/routing/directions/RouteOrderCache.java',
]
cp = os.pathsep.join(map(str, jars))
subprocess.run([str(args.java_home / 'bin/javac'), '-proc:none', '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
cp = str(classes) + os.pathsep + cp
subprocess.run([str(args.java_home / 'bin/java'), '-cp', cp, 'hagrid.core.routing.EqualClockContractTest'], check=True)
job = dict(inputRoot=str(args.input_root.resolve()), output=str(out / 'result.json'),
           penalty=args.penalty, seed=args.seed, version='2.0.0', iterations=args.iterations,
           capSeconds=120, fastService=not args.native, fastOrderCache=not args.native,
           indexedInputs=not args.native, tuningProfile=args.profile,
           positionPruning=not args.native and not args.no_position_pruning, validateInsertions=args.validate_insertions,
           jspritSha256=hashlib.sha256(native[0].read_bytes()).hexdigest())
main = 'OptimizedJspritRun'
if args.seconds is not None:
    main = 'TimedJspritRun'
    job.update(iterations=1000000, capSeconds=args.seconds-.5, deadlineSeconds=args.seconds)
(out / 'job.json').write_text(json.dumps(job, indent=2))
(out / 'provenance.json').write_text(json.dumps({
    'sources': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    'jars': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in jars},
}, indent=2))
subprocess.run([str(args.java_home / 'bin/java'), '-Xmx2048m', '-XX:ActiveProcessorCount=1',
                '-cp', cp, main, str(out / 'job.json')], check=True)
result = json.loads((out / 'result.json').read_text())
if args.seconds is None and result['completedIterations'] != args.iterations:
    raise RuntimeError('Incomplete fixed-work run: the 120 second safety cap stopped the search. '
                       'Keep this result as incomplete; do not compare it as the requested iteration budget.')
print(out / 'result.json')
