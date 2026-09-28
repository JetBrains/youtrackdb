# File close and eviction

A file channel is the Java handle for an open file. An in-memory file lock stops concurrent code from changing that channel during close. The operating system closes the file behind the channel.

`AsyncFile.closeAfterFailedCreate()` retries a failed channel close once. The Java channel marks itself closed before it asks the operating system to close the file. A later close call can return at once after that failure. A delay would hold the file lock longer on this error path. A configurable retry count would add a configuration key. Creation cleanup still reports the first close error even if the retry succeeds.

The open-file limit is a soft target for the number of open files.

Eviction releases idle file channels when that limit is exceeded. Eviction first asks the channel to synchronize its pending writes. If synchronization fails, eviction logs the error and closes the channel without another synchronization attempt. The dirty counter records writes that still need synchronization. The counter survives channel close and reopen. A successful channel close frees an open-file slot for another storage.

The owning storage reopens the file and retries synchronization at its next synchronization barrier. A synchronization barrier must complete before cleanup can discard the write-ahead log (WAL). WAL records allow recovery of writes after a crash. A later failure reaches the owning storage and blocks WAL cleanup.

A later success does not prove that the earlier failed write reached disk. On Linux, a failed synchronization can report its error only once. [YTDB-1313](https://youtrack.jetbrains.com/issue/YTDB-1313) tracks that durability gap.

A channel close can also fail. Eviction logs the first close error and retries the channel close once. It logs that error even if the retry succeeds. If both attempts fail, eviction leaves the file entry open and does not free its slot.

Explicit file close uses a different path. It reports synchronization failures to its caller instead of hiding them. Explicit close does not reopen a closed file unless it still needs synchronization.
