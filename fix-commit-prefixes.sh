#!/bin/bash
#
# Script to prepend issue number to commits that are missing it.
# Usage: fix-commit-prefixes.sh <issue-number> [base-branch]
#
# Examples:
#   fix-commit-prefixes.sh YTDB-123
#   fix-commit-prefixes.sh 123
#   fix-commit-prefixes.sh YTDB-123 main
#
# This script will:
# 1. Find commits unique to the current branch (not in base branch)
# 2. Check which commits are missing the issue prefix
# 3. Rebase and prepend the issue prefix to those commits
#
# WARNING: This rewrites history. Only use on branches not yet pushed,
# or be prepared to force-push.

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

usage() {
    echo "Usage: $0 <issue-number> [base-branch]"
    echo ""
    echo "Arguments:"
    echo "  issue-number  The issue number (e.g., YTDB-123 or just 123)"
    echo "  base-branch   The base branch to compare against (default: auto-detect)"
    echo ""
    echo "Examples:"
    echo "  $0 YTDB-123"
    echo "  $0 123"
    echo "  $0 YTDB-123 develop"
    exit 1
}

# Check arguments
if [ -z "$1" ]; then
    usage
fi

# Parse issue number - normalize to YTDB-NNN format
ISSUE_INPUT="$1"
if [[ "$ISSUE_INPUT" =~ ^[0-9]+$ ]]; then
    ISSUE_PREFIX="YTDB-$ISSUE_INPUT"
elif [[ "${ISSUE_INPUT,,}" =~ ^ytdb-?([0-9]+)$ ]]; then
    ISSUE_PREFIX="YTDB-${BASH_REMATCH[1]}"
else
    echo -e "${RED}Error: Invalid issue number format: $ISSUE_INPUT${NC}"
    echo "Expected format: YTDB-123, YTDB123, or just 123"
    exit 1
fi

ISSUE_PREFIX_LOWER="${ISSUE_PREFIX,,}"
echo -e "${GREEN}Using issue prefix: $ISSUE_PREFIX${NC}"

# Determine base branch
if [ -n "$2" ]; then
    BASE_BRANCH="$2"
else
    # Try to auto-detect base branch
    if git show-ref --verify --quiet refs/heads/develop; then
        BASE_BRANCH="develop"
    elif git show-ref --verify --quiet refs/heads/main; then
        BASE_BRANCH="main"
    elif git show-ref --verify --quiet refs/heads/master; then
        BASE_BRANCH="master"
    else
        echo -e "${RED}Error: Could not auto-detect base branch.${NC}"
        echo "Please specify the base branch as the second argument."
        exit 1
    fi
fi

echo -e "Base branch: ${YELLOW}$BASE_BRANCH${NC}"

# Get current branch
CURRENT_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null)
if [ -z "$CURRENT_BRANCH" ]; then
    echo -e "${RED}Error: Not on a branch (detached HEAD).${NC}"
    exit 1
fi

if [ "$CURRENT_BRANCH" = "$BASE_BRANCH" ]; then
    echo -e "${RED}Error: Cannot run on the base branch itself.${NC}"
    exit 1
fi

echo -e "Current branch: ${YELLOW}$CURRENT_BRANCH${NC}"

# Find merge base
MERGE_BASE=$(git merge-base "$BASE_BRANCH" HEAD)
echo -e "Merge base: ${YELLOW}${MERGE_BASE:0:7}${NC}"

# Get commits unique to this branch
COMMITS=$(git rev-list --reverse "$MERGE_BASE"..HEAD)
COMMIT_COUNT=$(echo "$COMMITS" | grep -c . || true)

if [ "$COMMIT_COUNT" -eq 0 ]; then
    echo -e "${GREEN}No commits to process.${NC}"
    exit 0
fi

echo -e "Found ${YELLOW}$COMMIT_COUNT${NC} commit(s) to check."
echo ""

# Check which commits need fixing
COMMITS_TO_FIX=()
while read -r COMMIT; do
    MSG=$(git log --format=%s -n 1 "$COMMIT")
    MSG_LOWER="${MSG,,}"
    if [[ ! "$MSG_LOWER" =~ $ISSUE_PREFIX_LOWER ]]; then
        COMMITS_TO_FIX+=("$COMMIT")
        echo -e "  ${RED}Missing prefix:${NC} ${COMMIT:0:7} - $MSG"
    else
        echo -e "  ${GREEN}OK:${NC} ${COMMIT:0:7} - $MSG"
    fi
done <<< "$COMMITS"

echo "--------"

if [ ${#COMMITS_TO_FIX[@]} -eq 0 ]; then
    echo -e "${GREEN}All commits already have the issue prefix. Nothing to do.${NC}"
    exit 0
fi

echo -e "${YELLOW}${#COMMITS_TO_FIX[@]} commit(s) need the prefix added.${NC}"
echo ""

# Confirm before proceeding
read -p "This will rewrite history. Continue? [y/N] " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
fi

# Create a temporary script for the message filter
FILTER_SCRIPT=$(mktemp)
cat > "$FILTER_SCRIPT" << 'FILTER_EOF'
#!/bin/bash
ISSUE_PREFIX="$1"
ISSUE_PREFIX_LOWER="${ISSUE_PREFIX,,}"

# Read the entire commit message
MSG=$(cat)
MSG_LOWER="${MSG,,}"

# Check if prefix already exists (case-insensitive)
if [[ "$MSG_LOWER" =~ $ISSUE_PREFIX_LOWER ]]; then
    echo "$MSG"
else
    echo "$ISSUE_PREFIX: $MSG"
fi
FILTER_EOF
chmod +x "$FILTER_SCRIPT"

# Run git filter-branch to rewrite commit messages
echo -e "${YELLOW}Rewriting commits...${NC}"

# Use git filter-branch with msg-filter
FILTER_BRANCH_SETUP="ISSUE_PREFIX='$ISSUE_PREFIX'"
git filter-branch -f --msg-filter "$FILTER_SCRIPT '$ISSUE_PREFIX'" "$MERGE_BASE"..HEAD

# Clean up
rm -f "$FILTER_SCRIPT"

# Remove the backup ref created by filter-branch
git update-ref -d refs/original/refs/heads/"$CURRENT_BRANCH" 2>/dev/null || true

echo ""
echo -e "${GREEN}Done! Commits have been updated.${NC}"
echo ""
echo "Updated commits:"
git log --oneline "$MERGE_BASE"..HEAD
echo ""
echo -e "${YELLOW}Note: If you've already pushed this branch, you'll need to force-push:${NC}"
echo "  git push --force-with-lease"
