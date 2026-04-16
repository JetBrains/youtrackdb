import os
import sys
from github import Github, GithubException

def get_env_var(name):
  val = os.environ.get(name)
  if not val:
    print(f"[ERROR] Missing environment variable: {name}")
    sys.exit(1)
  return val

def main():
  # 1. Load Configuration from Environment
  token = get_env_var("GH_TOKEN")
  source_repo_name = get_env_var("SOURCE_REPO")
  # Target repos come in as a comma-separated string
  target_repos_str = get_env_var("TARGET_REPOS")
  target_repo_names = [r.strip() for r in target_repos_str.split(',') if r.strip()]

  print(f"--- Configuration ---")
  print(f"Source: {source_repo_name}")
  print(f"Targets: {target_repo_names}")
  print(f"---------------------")

  # 2. Authenticate
  g = Github(token)

  try:
    source_repo = g.get_repo(source_repo_name)
  except GithubException as e:
    print(f"[ERROR] Could not fetch source repo '{source_repo_name}'. Check permissions. \n{e}")
    sys.exit(1)

  # 3. Prepare Settings Payload (Attributes available in PyGithub)
  settings_payload = {
    "allow_squash_merge": source_repo.allow_squash_merge,
    "allow_merge_commit": source_repo.allow_merge_commit,
    "allow_rebase_merge": source_repo.allow_rebase_merge,
    "delete_branch_on_merge": source_repo.delete_branch_on_merge,
    "allow_update_branch": source_repo.allow_update_branch,
    "squash_merge_commit_title": source_repo.squash_merge_commit_title,
    "squash_merge_commit_message": source_repo.squash_merge_commit_message,
    "merge_commit_title": source_repo.merge_commit_title,
    "merge_commit_message": source_repo.merge_commit_message,
  }

  # 4. Fetch Rulesets (Using internal requester for raw JSON access)
  print("\n[Source] Fetching Rulesets...")
  # This uses the PyGithub connection to make a raw request
  _, rulesets, _ = source_repo._requester.requestJson(
    "GET",
    f"{source_repo.url}/rulesets"
  )

  # 5. Apply to Targets
  for target_name in target_repo_names:
    print(f"\n>>> Processing Target: {target_name}")
    try:
      target_repo = g.get_repo(target_name)

      # A. Apply General Settings
      print("   [Settings] Syncing PR & Merge settings...")
      # Filter out None values to avoid API errors
      clean_settings = {k: v for k, v in settings_payload.items() if v is not None}
      target_repo.edit(**clean_settings)
      print("   [Settings] Done.")

      # B. Apply Rulesets
      if rulesets:
        # Fetch existing rulesets in target to prevent duplicates
        _, target_current_rules, _ = target_repo._requester.requestJson(
          "GET", f"{target_repo.url}/rulesets"
        )

        for r in rulesets:
          # Fetch full details for the specific source ruleset
          _, full_rule, _ = source_repo._requester.requestJson(
            "GET", f"{source_repo.url}/rulesets/{r['id']}"
          )

          # Check if a ruleset with this name already exists in target
          existing_id = next((tr['id'] for tr in target_current_rules if tr['name'] == r['name']), None)

          # Construct payload (removing read-only fields like ID, NodeID)
          payload = {
            "name": full_rule["name"],
            "target": full_rule["target"],
            "enforcement": full_rule["enforcement"],
            "bypass_actors": full_rule.get("bypass_actors", []),
            "conditions": full_rule.get("conditions", {}),
            "rules": full_rule.get("rules", [])
          }

          if existing_id:
            print(f"   [Ruleset] Updating existing ruleset '{r['name']}' (ID: {existing_id})...")
            target_repo._requester.requestJson(
              "PUT", f"{target_repo.url}/rulesets/{existing_id}", input=payload
            )
          else:
            print(f"   [Ruleset] Creating new ruleset '{r['name']}'...")
            target_repo._requester.requestJson(
              "POST", f"{target_repo.url}/rulesets", input=payload
            )
      else:
        print("   [Ruleset] No rulesets found in source.")

    except GithubException as e:
      print(f"   [ERROR] Failed to process {target_name}: {e.data.get('message', e)}")
    except Exception as e:
      print(f"   [ERROR] Unexpected error on {target_name}: {e}")

if __name__ == "__main__":
  main()