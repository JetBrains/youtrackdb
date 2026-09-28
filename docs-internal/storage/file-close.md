# AsyncFile close retry

`AsyncFile` closes its Java file channel in two cleanup paths. The first path runs when `WOWCache.createFile()` fails to create, open, shrink, or synchronize a file, in `AsyncFile.closeAfterFailedCreate()`. The second path runs when the open-file container evicts an idle file, in `AsyncFile.closeForEviction()`. Both paths retry a failed channel close exactly one time. A backoff is a delay that grows after each failed attempt.

## Why one retry is enough

The Java file channel marks itself closed before it asks the operating system to close the file. If the operating-system close then fails, the channel is already marked closed. A second close call sees that mark and returns at once. A third or later call does the same. More attempts therefore cannot change the result.

The single retry still has a purpose. After a failed close, `AsyncFile` still holds its reference to the channel. The retry returns normally, so `AsyncFile` clears that reference. No later code then uses the closed channel.

## Why a delay or backoff does not help

A delay between attempts cannot change the result, because the second attempt does not repeat the operating-system close. The file lock is an in-memory lock inside `AsyncFile`, not an operating-system file lock. Each attempt runs while `AsyncFile` holds its file lock. A delay would only hold that lock longer on an error path. A configurable attempt count or delay would add a configuration key without improving cleanup.
