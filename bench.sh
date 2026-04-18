#!/usr/bin/env bash
# bench.sh — compare runtime of bench_* testcases with and without monomorphization.
#
# Both baseline and optimized runs use -Xint (interpreter only, no JIT) so that
# the devirtualization benefit from static dispatch is visible.
#
# Usage:
#   bash bench.sh
#   RUNS=5 bash bench.sh
#   BENCHES="bench_array bench_field" bash bench.sh

set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")" >/dev/null 2>&1 && pwd)
cd "$ROOT"

SOOT_JAR=soot-4.6.0-jar-with-dependencies.jar
BENCHES=(${BENCHES:-bench_array bench_field bench_mixed})
RUNS=${RUNS:-3}

# Java classpath separator: ; on Windows, : on Unix
case "${OSTYPE:-}" in
  msys*|cygwin*|win32*) SEP=";" ;;
  *) SEP=":" ;;
esac

die() { echo "ERROR: $*" >&2; exit 1; }

[[ -f "$SOOT_JAR" ]] || die "Missing $SOOT_JAR — place it in $ROOT"

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
echo "==> Compiling optimizer..."
javac -cp "$SOOT_JAR" Optimizer.java TypeState.java TypeFlowAnalysis.java MonomorphizationTransformer.java

echo "==> Compiling testcases..."
for b in "${BENCHES[@]}"; do
    src="testcases/$b/Test.java"
    [[ -f "$src" ]] || die "Missing $src"
    javac -d . "$src"
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Run java under bash's built-in time, return elapsed seconds (e.g. "12.345").
timed_run() {
    local elapsed
    TIMEFORMAT='%R'
    elapsed=$( { time java "$@" > /dev/null; } 2>&1 )
    echo "$elapsed"
}

mean_of() {
    # Print mean of space-separated numbers.
    echo "$*" | awk '{s=0;n=0;for(i=1;i<=NF;i++){s+=$i;n++} if(n>0) printf "%.2f",s/n}'
}

# ---------------------------------------------------------------------------
# Header
# ---------------------------------------------------------------------------
printf "\n%-13s %5s  %-28s %7s  %-28s %7s  %7s\n" \
    "benchmark" "sites" "baseline runs -Xint (s)" "mean" \
    "optimized runs -Xint (s)" "mean" "speedup"
printf '%s\n' "$(printf '%.0s-' {1..100})"

# ---------------------------------------------------------------------------
# Run each benchmark
# ---------------------------------------------------------------------------
for b in "${BENCHES[@]}"; do
    main_class="testcases.$b.Test"
    log_file="/tmp/mono_${b}.log"

    # --- Apply monomorphization --------------------------------------------
    rm -rf sootOutput
    java -cp ".${SEP}${SOOT_JAR}" Optimizer "$b" > "$log_file" 2>&1 || true
    sites=$(grep -m1 'Monomorphized call sites' "$log_file" | awk -F': ' '{print $2}')
    sites=${sites:-0}

    # --- Baseline runs (original classes, no JIT) --------------------------
    base_times=""
    for i in $(seq 1 "$RUNS"); do
        t=$(timed_run -Xint -cp "." "$main_class")
        base_times="$base_times $t"
    done

    # --- Optimized runs (monomorphized classes prepended, no JIT) ----------
    opt_times=""
    for i in $(seq 1 "$RUNS"); do
        t=$(timed_run -Xint -cp "sootOutput${SEP}." "$main_class")
        opt_times="$opt_times $t"
    done

    # --- Stats --------------------------------------------------------------
    base_mean=$(mean_of $base_times)
    opt_mean=$(mean_of $opt_times)
    speedup=$(awk -v b="$base_mean" -v o="$opt_mean" \
        'BEGIN{ if(o+0>0) printf "%.2fx",b/o; else print "n/a" }')

    printf "%-13s %5s  %-28s %7s  %-28s %7s  %7s\n" \
        "$b" "$sites" "$(echo $base_times)" "$base_mean" \
        "$(echo $opt_times)" "$opt_mean" "$speedup"
done

printf '%s\n' "$(printf '%.0s-' {1..100})"
echo ""
echo "Note: -Xint disables JIT so dispatch overhead is directly measurable."
echo "      A speedup > 1x confirms monomorphization reduces virtual dispatch cost."
