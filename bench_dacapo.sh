#!/usr/bin/env bash
# bench_dacapo.sh — compare DaCapo benchmark runtime with and without monomorphization.
#
# Uses DaCapo's own "PASSED in X msec" internal timing (reported on stderr) which
# excludes JVM startup so the numbers are purely benchmark execution time.
# Both sides run with -Xint (no JIT) so virtual-dispatch elimination is directly visible.
#
# Prerequisites:
#   - dacapo-9.12-MR1-bach.jar in the project root
#   - tami-outs/out-{luindex,avrora,xalan,fop,batik}/ from TamiFlex
#   - soot-4.6.0-jar-with-dependencies.jar in the project root
#
# Usage:
#   bash bench_dacapo.sh
#   BENCHES="luindex avrora" RUNS=3 SIZE=small bash bench_dacapo.sh

set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")" >/dev/null 2>&1 && pwd)
cd "$ROOT"

SOOT_JAR=soot-4.6.0-jar-with-dependencies.jar
DACAPO_JAR=dacapo-9.12-MR1-bach.jar
TAMI_DIR=tami-outs
BENCHES=(${BENCHES:-luindex avrora xalan fop batik})
RUNS=${RUNS:-3}
SIZE=${SIZE:-small}
JAVA_XMX=${JAVA_XMX:-6g}
RESULT_DIR=${RESULT_DIR:-bench-results}

mkdir -p "$RESULT_DIR"

# Java classpath separator: ; on Windows, : on Unix
case "${OSTYPE:-}" in
  msys*|cygwin*|win32*) SEP=";" ;;
  *) SEP=":" ;;
esac

die() { echo "ERROR: $*" >&2; exit 1; }

[[ -f "$SOOT_JAR"   ]] || die "Missing $SOOT_JAR"
[[ -f "$DACAPO_JAR" ]] || die "Missing $DACAPO_JAR"
[[ -d "$TAMI_DIR"   ]] || die "Missing $TAMI_DIR directory"

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
echo "==> Compiling optimizer + MonoBenchmark..."
javac -cp "$SOOT_JAR" \
    Optimizer.java TypeState.java TypeFlowAnalysis.java \
    MonomorphizationTransformer.java MonoBenchmark.java

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Run DaCapo benchmark once; print its internal timing in milliseconds.
# DaCapo prints "===== DaCapo ... PASSED in X msec =====" to stderr.
# -Xint disables JIT so virtual-dispatch elimination is the dominant effect.
run_dacapo() {
    local cp="$1"; shift  # classpath
    local bench="$1"; shift
    { java -Xmx"$JAVA_XMX" -Xint -cp "$cp" "$@" \
          Harness -s "$SIZE" "$bench" >/dev/null; } 2>&1 \
      | grep 'PASSED' | awk '{print $(NF-2)}'
}

mean_ms() {
    # Print integer mean of space-separated millisecond values.
    echo "$*" | awk '{s=0;n=0;for(i=1;i<=NF;i++){s+=$i;n++} if(n>0) printf "%d",int(s/n+0.5)}'
}

# ---------------------------------------------------------------------------
# Header
# ---------------------------------------------------------------------------
printf "\n%-9s %5s  %-30s %7s  %-30s %7s  %7s\n" \
    "bench" "sites" "baseline -Xint (ms)" "mean" \
    "optimized -Xint (ms)" "mean" "speedup"
printf '%s\n' "$(printf '%.0s-' {1..100})"

# ---------------------------------------------------------------------------
# Per-benchmark loop
# ---------------------------------------------------------------------------
for b in "${BENCHES[@]}"; do
    tami_out="$TAMI_DIR/out-$b"
    mono_out="$RESULT_DIR/mono-out-$b"
    opt_log="$RESULT_DIR/opt-$b.log"

    [[ -d "$tami_out" ]] || { echo "SKIP $b: $tami_out not found"; continue; }

    # --- Apply monomorphization to tami-out classes -------------------------
    rm -rf "$mono_out"
    java -Xmx"$JAVA_XMX" \
        -cp ".${SEP}${SOOT_JAR}" \
        MonoBenchmark "$tami_out" "$mono_out" \
        > "$opt_log" 2>&1 || true

    sites=$(grep -m1 'Monomorphized call sites' "$opt_log" | awk -F': ' '{print $2}')
    sites=${sites:-0}

    base_cp="${tami_out}${SEP}${DACAPO_JAR}"
    opt_cp="${mono_out}${SEP}${tami_out}${SEP}${DACAPO_JAR}"

    # --- Baseline runs: original tami-out classes, no JIT ------------------
    base_times=""
    for i in $(seq 1 "$RUNS"); do
        t=$(run_dacapo "$base_cp" "$b")
        base_times="$base_times $t"
    done

    # --- Optimized runs: monomorphized classes prepended, no JIT -----------
    opt_times=""
    for i in $(seq 1 "$RUNS"); do
        t=$(run_dacapo "$opt_cp" "$b")
        opt_times="$opt_times $t"
    done

    # --- Stats --------------------------------------------------------------
    base_mean=$(mean_ms $base_times)
    opt_mean=$(mean_ms $opt_times)
    speedup=$(awk -v b="$base_mean" -v o="$opt_mean" \
        'BEGIN{ if(o+0>0) printf "%.2fx",b/o; else print "n/a" }')

    printf "%-9s %5s  %-30s %7s  %-30s %7s  %7s\n" \
        "$b" "$sites" "$(echo $base_times)" "$base_mean" \
        "$(echo $opt_times)" "$opt_mean" "$speedup"
done

printf '%s\n' "$(printf '%.0s-' {1..100})"
echo ""
echo "Timing: DaCapo internal 'PASSED in X msec' (stderr) — excludes JVM startup."
echo "Both sides: -Xint (interpreter only, no JIT). Logs: $RESULT_DIR/opt-<bench>.log"
