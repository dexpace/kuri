#!/bin/sh
# Copyright (c) 2026 dexpace and Omar Aljarrah
# SPDX-License-Identifier: MIT
#
# check-conventional-commit.sh — validate one commit / PR-title header against the project's
# Conventional Commits convention. Invoked by CI: once for the pull-request title, and once for the
# HEAD commit after a push to main. This is NOT a git hook — enforcement lives entirely in CI.
#
# Usage: check-conventional-commit.sh <path-to-file-containing-the-message>
# Exits 0 when the header is valid or exempt; 1 otherwise, printing guidance to stderr.
#
# Rules: the header is  type(scope)!: subject  — type from the allowed list, optional lowercase
# (scope) and optional ! breaking marker, exactly one space after the colon, a non-empty subject,
# the whole header at most 100 characters, and no trailing period or space. Git-generated
# Merge/Revert/fixup!/squash!/amend! headers are exempt. Portable POSIX sh: grep, wc, printf only.

MSG_FILE="$1"

if [ -z "$MSG_FILE" ] || [ ! -f "$MSG_FILE" ]; then
    printf '%s\n' "check-conventional-commit: missing or unreadable message file: '$MSG_FILE'" >&2
    exit 2
fi

HEADER=$(grep -Ev '^[[:space:]]*(#|$)' "$MSG_FILE" | { IFS= read -r line; printf '%s' "$line"; })

case "$HEADER" in
    "Merge "* | "Revert "* | "fixup!"* | "squash!"* | "amend!"*)
        exit 0
        ;;
esac

TYPES='feat|fix|docs|test|chore|perf|refactor|ci|build|style|revert|merge'
HEADER_REGEX="^($TYPES)(\([a-z0-9._/-]+\))?!?: [^ ].*$"

LENGTH=$(printf '%s' "$HEADER" | wc -m)

REASON=""
if ! printf '%s\n' "$HEADER" | grep -Eq "$HEADER_REGEX"; then
    REASON="header does not match type(scope)!: subject"
elif [ "$LENGTH" -gt 100 ]; then
    REASON="header is longer than 100 characters ($LENGTH)"
else
    case "$HEADER" in
        *.) REASON="subject must not end with a period" ;;
        *" ") REASON="subject must not end with a space" ;;
    esac
fi

if [ -n "$REASON" ]; then
    printf '%s\n' \
        "Invalid conventional commit header." \
        "  Reason: $REASON" \
        "" \
        "  Offending header:" \
        "    $HEADER" \
        "" \
        "  Expected format:  type(scope)!: subject" \
        "    - type is required; (scope) and the ! breaking-change marker are optional" \
        "    - one space after the colon; subject is non-empty, must not start or end with" \
        "      a space, must not end with a period, and the whole header must be <=100 chars" \
        "" \
        "  Allowed types:" \
        "    feat fix docs test chore perf refactor ci build style revert merge" \
        "" \
        "  Scope may contain lowercase letters, digits, and . _ / -" \
        "" \
        "  Examples:" \
        "    feat(parser): support IPv6 zone identifiers" \
        "    ci: run the conformance suite on every push" \
        "    docs(readme)!: rename the primary entry point" >&2
    exit 1
fi

exit 0
