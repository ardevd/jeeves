#!/bin/bash
# Source this helper, then load_jeb_jvm_options /absolute/selected-install.
# Match the vendor's whitespace-separated jvmopt.txt format without evaluating
# shell syntax or expanding wildcards. Explicit launcher options follow these.
load_jeb_jvm_options() {
  JEB_JVM_OPTIONS=()
  local line
  local -a words
  [ -f "$1/jvmopt.txt" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    IFS=$' \t\r\n' read -r -a words <<< "$line"
    if [ "${#words[@]}" -gt 0 ]; then
      JEB_JVM_OPTIONS+=("${words[@]}")
    fi
  done < "$1/jvmopt.txt"
}
