#!/usr/bin/env bash
# bench.sh — compare DaCapo benchmark runtime with and without monomorphization.
#
# For each benchmark:
#   1. Run the DaCapo harness N times with -Xint (baseline, no optimization).
#   2. Apply our monomorphization transformer (emitting .class files).
#   3. Run the DaCapo harness N times with -Xint, with the transformed classes
#      prepended to the classpath.
#   4. Report per-run times and the mean for both configurations.

set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")" >/dev/null 2>&1 && pwd)
cd "$ROOT"

SOOT_JAR=soot-4.6.0-jar-with-dependencies.jar
DACAPO_JAR=dacapo-9.12-MR1-bach.jar
MONO_DIR=Monomorphization-Using-Soot
BENCHES=(${BENCHES:-luindex avrora xalan fop batik})
RUNS=${RUNS:-3}
SIZE=${SIZE:-small}
JAVA_XMX=${JAVA_XMX:-6g}
RESULT_DIR=${RESULT_DIR:-bench-results}

mkdir -p "$RESULT_DIR"

# Ensure the transformer is compiled.
javac -cp "$SOOT_JAR" "$MONO_DIR"/*.java
javac -cp ".:$SOOT_JAR:$MONO_DIR" MonoBenchmark.java GetSootArgs.java

median_and_mean() {
  # Reads whitespace-separated numbers from stdin, prints "mean min max" to stdout.
  awk '{
    for (i=1;i<=NF;i++) { n++; s+=$i; if(n==1||$i<mn)mn=$i; if($i>mx)mx=$i; }
  } END {
    if (n==0) { print "0 0 0"; exit }
    printf "%.2f %.2f %.2f\n", s/n, mn, mx
  }'
}

run_baseline() {
  local bench=$1 out=$2
  { /usr/bin/time -p java -Xmx"$JAVA_XMX" -Xint \
      -cp "$DACAPO_JAR" Harness -s "$SIZE" "$bench" > /dev/null; } 2> "$out"
  grep '^real' "$out" | awk '{print $2}'
}

run_optimized() {
  local bench=$1 out=$2 mono_cp=$3
  { /usr/bin/time -p java -Xmx"$JAVA_XMX" -Xint \
      -cp "$mono_cp:$DACAPO_JAR" Harness -s "$SIZE" "$bench" > /dev/null; } 2> "$out"
  grep '^real' "$out" | awk '{print $2}'
}

printf "%-9s %-6s %-30s %-7s %-7s %-30s %-7s %-7s %-8s\n" \
  "bench" "sites" "baseline runs (s)" "mean" "min" "optimized runs (s)" "mean" "min" "speedup"
printf "%s\n" "----------------------------------------------------------------------------------------------------------------------------"

for b in "${BENCHES[@]}"; do
  out_dir="tami-outs/out-$b"
  mono_out="$RESULT_DIR/mono-$b"

  # --- Apply monomorphization (emit class files) ------------------------------
  rm -rf "$mono_out"
  java -Xmx"$JAVA_XMX" -cp ".:$SOOT_JAR:$MONO_DIR:$out_dir" \
    MonoBenchmark "$out_dir" "$mono_out" > "$RESULT_DIR/opt-$b.log" 2>&1 || true
  sites=$(grep 'Monomorphized call sites' "$RESULT_DIR/opt-$b.log" | tail -1 | awk -F': ' '{print $2}')
  sites=${sites:-?}

  # --- Baseline runs ---------------------------------------------------------
  base=""
  for i in $(seq 1 "$RUNS"); do
    t=$(run_baseline "$b" "$RESULT_DIR/base-$b-$i.time")
    base="$base $t"
  done
  read base_mean base_min base_max < <(echo "$base" | median_and_mean)

  # --- Optimized runs --------------------------------------------------------
  opt=""
  for i in $(seq 1 "$RUNS"); do
    t=$(run_optimized "$b" "$RESULT_DIR/opt-$b-$i.time" "$mono_out")
    opt="$opt $t"
  done
  read opt_mean opt_min opt_max < <(echo "$opt" | median_and_mean)

  speedup=$(awk -v a="$base_mean" -v b="$opt_mean" 'BEGIN{ if(b>0) printf "%.2fx", a/b; else print "n/a" }')

  printf "%-9s %-6s %-30s %-7s %-7s %-30s %-7s %-7s %-8s\n" \
    "$b" "$sites" "$base" "$base_mean" "$base_min" "$opt" "$opt_mean" "$opt_min" "$speedup"
done
