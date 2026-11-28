#!/usr/bin/env bash
# Builds THIRD_PARTY_NOTICES.txt, the license notice (U10) that the app shows and the release attaches.
#
#   scripts/notices.sh generate   writes THIRD_PARTY_NOTICES.txt at the repo root
#   scripts/notices.sh check      builds it in a temporary folder and fails when it differs from the
#                                 committed file
#
# Needs cargo-about 0.9.3 on PATH (cargo install cargo-about --version 0.9.3 --locked --features cli)
# and runs Gradle through the wrapper (set NOTICES_GRADLEW to use another command).
#
# Parts, in this order:
#   1. the app's own license (LICENSE)
#   2. Rust crates linked into the app's native library: cargo-about with rust/about.toml and
#      rust/about.hbs, plus the texts in notices/rust-crates.tsv for crates whose source lacks one
#   3. the Rust standard library (notices/rust-std/)
#   4. where to get the source of the MPL-2.0 crates (notices/mpl-2.0-source.txt)
#   5. Android libraries in the release runtime classpath, mapped by notices/gradle-licenses.tsv
#   6. JNA (the rows of gradle-licenses.tsv whose files are in notices/jna/)
# Nothing that changes between runs or machines (versions of tools, paths, dates) goes in.
set -euo pipefail
export LC_ALL=C

about_version=0.9.3
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
notices=$root/notices
committed=$root/THIRD_PARTY_NOTICES.txt

die() {
    echo "notices.sh: $*" >&2
    exit 1
}

usage() {
    echo "usage: scripts/notices.sh generate|check" >&2
    exit 2
}

rule() { printf '%*s\n' 80 '' | tr ' ' "$1"; }

section() {
    echo
    rule '='
    echo "$1"
    rule '='
    echo
}

# The quoted strings of a TOML array that starts at the line "$2 = [" in file $1, in order.
toml_list() {
    awk -v key="$2" '
        $0 == key " = [" { inside = 1; next }
        inside && /^]/ { exit }
        inside && match($0, /"[^"]*"/) { print substr($0, RSTART + 1, RLENGTH - 2) }
    ' "$1"
}

# Rows of a notices TSV without comments.
rows() { grep -v '^#' "$1" | grep -v '^$' || true; }

print_files() {
    local f
    for f in $1; do
        [ -f "$notices/$f" ] || die "notices/$f is missing"
        echo
        tr -d '\r' < "$notices/$f"
    done
    echo
}

check_license_lists() {
    local deny about
    deny=$(toml_list "$root/rust/deny.toml" allow)
    about=$(toml_list "$root/rust/about.toml" accepted)
    [ -n "$deny" ] || die "no allow list in rust/deny.toml"
    if [ "$deny" != "$about" ]; then
        echo "allow in rust/deny.toml and accepted in rust/about.toml differ:" >&2
        diff <(echo "$deny") <(echo "$about") >&2 || true
        exit 1
    fi
}

run_cargo_about() {
    local have
    have=$(cargo about --version 2>/dev/null) || die "cargo-about is not installed"
    [ "$have" = "cargo-about $about_version" ] || die "need cargo-about $about_version, found: $have"
    (cd "$root/rust" && cargo about generate --locked --offline --fail \
        --manifest-path tasty-mobile-core/Cargo.toml -c about.toml about.hbs) | tr -d '\r' > "$tmp/about"
    # rust/about.hbs writes three parts, each after a line @@<part>.
    awk -v dir="$tmp" '/^@@/ { part = dir "/" substr($0, 3); printf "" > part; next } part { print > part }' \
        "$tmp/about"
    local p
    for p in notice fallback mpl; do
        [ -f "$tmp/$p" ] || die "cargo-about output has no @@$p part"
    done
}

# Crates that fell back to a canonical text get the files listed in notices/rust-crates.tsv instead.
fallback_crates() {
    local tsv=$notices/rust-crates.tsv
    cut -f1,2 "$tmp/fallback" | sort -u > "$tmp/fallback-crates"
    rows "$tsv" | cut -f1,2 | sort -u > "$tmp/listed-crates"
    if ! cmp -s "$tmp/fallback-crates" "$tmp/listed-crates"; then
        echo "crates without a license text in their source and notices/rust-crates.tsv differ" >&2
        echo "(< only in the cargo-about output, > only in the tsv):" >&2
        diff "$tmp/fallback-crates" "$tmp/listed-crates" >&2 || true
        exit 1
    fi
    local files
    rows "$tsv" | cut -f3 | awk '!seen[$0]++' | while IFS= read -r files; do
        echo
        rule '-'
        rows "$tsv" | awk -F'\t' -v f="$files" '$3 == f { print $1 "\t" $2 }' > "$tmp/group"
        awk -F'\t' 'NR == FNR { want[$1 "\t" $2] = 1; next } want[$1 "\t" $2] { print $3 }' \
            "$tmp/group" "$tmp/fallback" | awk '!seen[$0]++' | paste -sd ',' - | sed 's/,/, /g'
        echo "Used by:"
        awk -F'\t' '{ print "  " $1 " " $2 }' "$tmp/group"
        print_files "$files"
    done
}

rust_std() {
    echo "The Rust standard library (std, core, alloc and the crates they use) is linked into the app's"
    echo "native library."
    print_files rust-std/COPYRIGHT-library.txt
    local name
    for name in Apache-2.0 MIT BSD-2-Clause Unicode-3.0; do
        rule '-'
        echo "$name"
        print_files "rust-std/$name.txt"
    done
}

mpl_source() {
    sort -u "$tmp/mpl" | awk '{ print "  " $0 }' > "$tmp/mpl-crates"
    [ -s "$tmp/mpl-crates" ] || die "cargo-about lists no MPL-2.0 crate"
    awk -v list="$tmp/mpl-crates" '
        $0 == "@@crates" { while ((getline line < list) > 0) print line; next }
        { print }
    ' "$notices/mpl-2.0-source.txt" | tr -d '\r'
}

# group:module:version of every external module in :app's releaseRuntimeClasspath. Constraints are
# left out, a requested version that was raised takes the resolved one, and BOMs (modules named
# *-bom) are left out because they ship nothing.
gradle_coordinates() {
    local gradlew=${NOTICES_GRADLEW:-$root/gradlew}
    (cd "$root" && "$gradlew" :app:dependencies --configuration releaseRuntimeClasspath --console=plain) \
        | tr -d '\r' > "$tmp/gradle" || die "Gradle :app:dependencies failed"
    awk '
        /^releaseRuntimeClasspath - / { inside = 1; next }
        inside && /^$/ { exit }
        inside && match($0, /[+\\]--- /) {
            t = substr($0, RSTART + 5)
            if (t ~ / \(c\)$/) next
            sub(/ \((\*|n)\)$/, "", t)
            if (t ~ /^project /) { print "project dependency: " t > "/dev/stderr"; failed = 1; next }
            n = split(t, side, " -> ")
            split(side[1], c, ":")
            v = n == 2 ? side[2] : c[3]
            sub(/ .*/, "", v)
            print c[1] ":" c[2] ":" v
        }
        END { exit failed }
    ' "$tmp/gradle" | sort -u | sort -t: -k1,1 -k2,2 -k3,3 | grep -v -E '^[^:]+:[^:]+-bom:' \
        > "$tmp/coordinates" \
        || die "could not read the releaseRuntimeClasspath report"
    [ -s "$tmp/coordinates" ] || die "the releaseRuntimeClasspath report lists no module"
}

# Writes "row<TAB>coordinate" for each coordinate: the gradle-licenses.tsv row of its key.
map_coordinates() {
    rows "$notices/gradle-licenses.tsv" > "$tmp/gradle-rows"
    awk -F'\t' '
        NR == FNR { row[$1] = FNR; next }
        {
            split($0, c, ":")
            key = c[1] ":" c[2]
            if (key in row) print row[key] "\t" $0
            else if (c[1] in row) print row[c[1]] "\t" $0
            else { print "no row in notices/gradle-licenses.tsv for " $0 > "/dev/stderr"; failed = 1 }
        }
        END { exit failed }
    ' "$tmp/gradle-rows" "$tmp/coordinates" > "$tmp/mapped" || exit 1
}

# Prints the libraries whose files are (jna = 1) or are not (jna = 0) in notices/jna/.
gradle_groups() {
    local jna=$1 files
    awk -F'\t' -v jna="$jna" '(($4 ~ /^jna\//) ? 1 : 0) == jna { print $4 }' "$tmp/gradle-rows" \
        | awk '!seen[$0]++' | while IFS= read -r files; do
        awk -F'\t' -v f="$files" '$4 == f { print FNR }' "$tmp/gradle-rows" > "$tmp/rows-of-group"
        awk -F'\t' 'NR == FNR { want[$1] = 1; next } want[$1]' "$tmp/rows-of-group" "$tmp/mapped" > "$tmp/libs"
        [ -s "$tmp/libs" ] || continue
        rule '-'
        awk -F'\t' -v f="$files" '$4 == f { print $2; exit }' "$tmp/gradle-rows"
        echo "Used by:"
        awk -F'\t' 'NR == FNR { holder[FNR] = $3; next } { print "  " $2 ", " holder[$1] }' \
            "$tmp/gradle-rows" "$tmp/libs"
        print_files "$files"
    done
}

build() {
    check_license_lists
    run_cargo_about
    gradle_coordinates
    map_coordinates
    {
        section "Tasty for Android"
        tr -d '\r' < "$root/LICENSE"
        section "Rust crates"
        echo "Crates linked into the app's native library, grouped by license text."
        echo
        cat "$tmp/notice"
        fallback_crates
        section "Rust standard library"
        rust_std
        section "Source code of the MPL-2.0 crates"
        mpl_source
        section "Android libraries"
        echo "Libraries in the app's release build, grouped by license text."
        echo
        gradle_groups 0
        section "Java Native Access (JNA)"
        gradle_groups 1
    } | cat -s | sed -e '/./,$!d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' > "$1"
}

[ $# -eq 1 ] || usage
tmp=$(mktemp -d)
trap 'rm -rf "${tmp:?}"' EXIT

case $1 in
generate)
    build "$tmp/notice-file"
    mv "$tmp/notice-file" "$committed"
    echo "wrote THIRD_PARTY_NOTICES.txt ($(wc -c < "$committed") bytes)"
    ;;
check)
    [ -f "$committed" ] || die "THIRD_PARTY_NOTICES.txt is missing; run scripts/notices.sh generate"
    build "$tmp/notice-file"
    if ! cmp -s "$committed" "$tmp/notice-file"; then
        echo "THIRD_PARTY_NOTICES.txt is out of date; run scripts/notices.sh generate and commit it." >&2
        echo "First lines of the difference (committed, then rebuilt):" >&2
        diff -u --label committed --label rebuilt "$committed" "$tmp/notice-file" | head -n 60 >&2 || true
        exit 1
    fi
    echo "THIRD_PARTY_NOTICES.txt is up to date"
    ;;
*)
    usage
    ;;
esac
