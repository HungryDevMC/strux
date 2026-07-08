#!/usr/bin/env python3
"""Rewrite JMH results.json benchmark names into plain English.

The public repo publishes benchmarks/build/results/jmh/results.json to a chart via
benchmark-action/github-action-benchmark (tool: jmh). By default that chart labels
each series with the raw method FQN (e.g. dev.gesp...SolverBenchmark.solveAll). This
script maps each entry's "benchmark" field through benchmarks/display-names.json to a
human-readable description and appends the JMH @Param values in parentheses so the
param variants stay distinct on the chart (the jmh extractor does not append params
itself). Unknown names pass through unchanged with a warning to stderr.

Usage:
    humanize-results.py [results.json] [-o OUT] [-m display-names.json]
    humanize-results.py --self-test

With no OUT, the file is rewritten in place. Stdlib only.
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MAPPING = os.path.normpath(os.path.join(HERE, os.pardir, "display-names.json"))


def load_mapping(path):
    with open(path, encoding="utf-8") as fh:
        raw = json.load(fh)
    # Drop documentation keys (anything starting with "_").
    return {k: v for k, v in raw.items() if not k.startswith("_")}


def format_params(params):
    """Render an ordered '(a = 1, b = 2)' suffix, or '' when there are none."""
    if not params:
        return ""
    parts = ["{} = {}".format(k, params[k]) for k in params]
    return " (" + ", ".join(parts) + ")"


def humanize(results, mapping, warn=None):
    """Return results with each 'benchmark' rewritten. Mutates entries in place."""
    for entry in results:
        fqn = entry.get("benchmark")
        desc = mapping.get(fqn)
        if desc is None:
            if warn is not None:
                warn("humanize-results: no display name for '{}'; left unchanged".format(fqn))
            continue
        entry["benchmark"] = desc + format_params(entry.get("params"))
    return results


def run(in_path, out_path, mapping_path):
    mapping = load_mapping(mapping_path)
    with open(in_path, encoding="utf-8") as fh:
        results = json.load(fh)
    humanize(results, mapping, warn=lambda m: print(m, file=sys.stderr))
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(results, fh, indent=2)
        fh.write("\n")
    print("humanize-results: wrote {} entries to {}".format(len(results), out_path))


def self_test():
    mapping = {
        "_comment": "ignored",
        "pkg.Foo.bar": "Solve every structure in a big world",
        "pkg.Foo.baz": "Blast a small tower",
    }
    results = [
        {"benchmark": "pkg.Foo.bar", "params": {"structures": "200"}, "primaryMetric": {"score": 1.0}},
        {"benchmark": "pkg.Foo.baz", "params": None},
        {"benchmark": "pkg.Foo.baz"},
        {"benchmark": "pkg.Unknown.qux", "params": {"x": "1"}},
    ]
    warnings = []
    out = humanize([dict(r) for r in results], {k: v for k, v in mapping.items() if not k.startswith("_")}, warn=warnings.append)

    assert out[0]["benchmark"] == "Solve every structure in a big world (structures = 200)", out[0]
    assert out[1]["benchmark"] == "Blast a small tower", out[1]
    assert out[2]["benchmark"] == "Blast a small tower", out[2]
    assert out[3]["benchmark"] == "pkg.Unknown.qux", out[3]
    assert len(warnings) == 1 and "pkg.Unknown.qux" in warnings[0], warnings
    # Underscore keys must be filtered out of a loaded mapping.
    assert format_params({"a": "1", "b": "2"}) == " (a = 1, b = 2)"
    assert format_params(None) == ""
    assert format_params({}) == ""
    print("humanize-results self-test: OK")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", nargs="?", help="path to JMH results.json")
    parser.add_argument("-o", "--out", help="output path (default: rewrite in place)")
    parser.add_argument("-m", "--mapping", default=DEFAULT_MAPPING, help="display-names.json path")
    parser.add_argument("--self-test", action="store_true", help="run built-in unit tests and exit")
    args = parser.parse_args(argv)

    if args.self_test:
        self_test()
        return 0
    if not args.results:
        parser.error("results.json path is required (or use --self-test)")
    run(args.results, args.out or args.results, args.mapping)
    return 0


if __name__ == "__main__":
    sys.exit(main())
