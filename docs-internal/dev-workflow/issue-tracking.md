# YTDB Issue Tracking

Follow these rules for issues in the [YouTrackDB project (`YTDB`)](https://youtrack.jetbrains.com/issues/YTDB)
in YouTrack.

For agent access to YouTrack, see
[YouTrack access for worker threads](machine-local-setup.md#youtrack-access-for-worker-threads).

## Create an issue

Set `Type` to exactly one of `Bug`, `Feature`, `Performance Problem`, `Task`, or `Security Problem`.
Set `Subsystem` at creation to `storage` for storage or `YQL` for the YouTrackDB Query Language.
Leave `Subsystem` empty unless the issue clearly belongs to one of those subsystems.

## Security issue visibility

When you first publish a `Security Problem` issue, set its visibility to the group
`jetbrains-team` only. Do not add any other group or user.
Agents use `youtrack_create_issue` with `permittedGroups: ["jetbrains-team"]`.
Agents must not publish a `Security Problem` issue from a draft.
The draft publication tool cannot set visibility. The issue update tool cannot change visibility.

The visibility setting does not list every reader. YouTrack always lets the reporter read the
issue. An account with both `Read Issue` and `Override Visibility Restrictions` for `YTDB` can
also read the issue.

Read the issue back after creation. Classify the visibility check as follows:

- **Confirmed:** The group list contains only `jetbrains-team` and the user list is empty.
- **Mismatch:** Both lists are available, but the setting differs from the confirmed setting.
- **Unknown:** You cannot read the issue or either list is unavailable.

On mismatch or unknown, do not report success. Do not create a second issue. Give the issue
identifier (ID) to someone who can correct the visibility. If you are an agent, give the issue ID
to the user.

## Link issues

Attach a sub-issue to its parent only with a `subtask of` link from the sub-issue to the parent.
If one issue must finish before another, you may link the earlier issue to the later issue with
`is required for`. Do not create any other link type directly.

## Start implementation

For each YTDB issue whose implementation starts, set its `State` to `In progress`.
Set the `State` of every ancestor of that issue to `In progress` too. An ancestor is any issue
you reach by following `subtask of` links from a sub-issue to its parent and continuing to each
parent.
Report any ancestor you cannot read or update. Do not claim the whole chain is `In progress`
if you cannot read or update an ancestor.

For agents, implementation starts immediately before the first worker thread changes files.
