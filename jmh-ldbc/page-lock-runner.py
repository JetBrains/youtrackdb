"""Paired throughput statistics and durable run records. No processes run here."""

import json
import math
import os
import statistics

QUANTILES = {20: 2.625105913, 50: 2.478953032, 100: 2.435339300}
LIMIT = -math.log1p(-0.02)


def paired_log(develop, branch):
    """Use one positive finite throughput from each side, never JMH scoreError."""
    if any(not math.isfinite(v) or v <= 0 for v in (develop, branch)):
        raise ValueError("throughput must be positive and finite")
    # log1p preserves the exact 2 percent boundary for nearby throughput values.
    if abs(develop - branch) <= branch / 2:
        return math.log1p((develop - branch) / branch)
    return math.log(develop) - math.log(branch)


def look(values):
    """Return log bounds, drop bounds and the decision only at a planned look."""
    n = len(values)
    if n not in QUANTILES:
        return None
    mean = statistics.mean(values)
    radius = QUANTILES[n] * statistics.stdev(values) / math.sqrt(n)
    low, high = mean - radius, mean + radius
    decision = "pass" if high <= LIMIT else "fail" if low > LIMIT else "undecided"
    if n == 100 and decision == "undecided":
        decision = "goes to the user"
    return {"log_bounds": (low, high), "drop_bounds": (-math.expm1(-low),
            -math.expm1(-high)), "decision": decision}


def goal9_passes(required_cells, *decision_maps):
    """Combine server decisions. Missing or conflicting decisions cannot pass."""
    cells = set(required_cells)
    return len(cells) == 6 and all(
        any(cell in decisions for decisions in decision_maps)
        and all(decisions[cell] == "pass" for decisions in decision_maps if cell in decisions)
        for cell in cells)


def append_event(path, event):
    """Append one JSON Lines event and synchronize it before returning."""
    payload = json.dumps(event, allow_nan=False) + "\n"
    with open(path, "a", encoding="utf-8") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())


class State:
    """Replay state. Completed pairs derive decisions even before annotations persist."""

    def __init__(self, settings, checksums):
        cells = settings["cells"]
        if not cells or len(set(cells)) != len(cells) or any(
                not isinstance(cell, str) or not cell for cell in cells):
            raise ValueError("cells must be distinct nonempty strings")
        if type(settings["pilot_pairs"]) is not int or settings["pilot_pairs"] < 0:
            raise ValueError("pilot_pairs must be a nonnegative integer")
        self.experiment = {"type": "experiment", "settings": settings, "checksums": checksums}
        self.initialized = False
        self.runs, self.started, self.excluded, self.decisions = {}, [], set(), {}
        self.values = {(cell, phase): [] for cell in cells for phase in ("pilot", "result")}
        self.attempts = dict.fromkeys(self.values, 0)
        self.stopped = None

    def next_run(self):
        """Use listed cell order, with all pilot cells before all result cells."""
        if self.stopped:
            return None
        for phase in ("pilot", "result"):
            for cell in self.experiment["settings"]["cells"]:
                values = self.values[cell, phase]
                if (phase == "pilot" and len(values) >= self.experiment["settings"]["pilot_pairs"]
                        or phase == "result"
                        and self.decisions.get(cell) not in (None, "undecided")):
                    continue
                attempt = self.attempts[cell, phase]
                first = "branch" if attempt % 2 == 0 else "develop"
                order = "second" if (cell, phase, attempt, "first") in self.runs else "first"
                return {"cell": cell, "phase": phase, "attempt": attempt, "order": order,
                        "side": first if order == "first" else
                        ("develop" if first == "branch" else "branch")}
        return None

    def apply(self, event):
        """Reject inconsistent events. A repeated start preserves an interrupted run."""
        kind = event["type"]
        if kind == "experiment":
            if self.initialized or event != self.experiment:
                raise ValueError("experiment settings or checksums differ")
            self.initialized = True
            return
        if not self.initialized:
            raise ValueError("experiment event required")
        if kind in ("pair_excluded", "cell_decision"):
            if kind == "pair_excluded":
                if ((event["cell"], event["phase"], event["attempt"]) not in self.excluded
                        or event["reason"] != "processor model mismatch"):
                    raise ValueError("exclusion does not match the recorded pair")
            elif (event["pairs"] not in QUANTILES
                    or event["decision"] != self.decisions.get(event["cell"])
                    or event["pairs"] != len(self.values[event["cell"], "result"])):
                raise ValueError("decision does not match the recorded look")
            return
        if kind not in ("run_started", "run_finished"):
            raise ValueError("unknown event type")
        expected = self.next_run()
        if expected is None or any(event.get(k) != v for k, v in expected.items()):
            raise ValueError("run is out of schedule")
        key = tuple(event[k] for k in ("cell", "phase", "attempt", "order"))
        if kind == "run_started":
            self.started.append(key)
            return
        if key not in self.started:
            raise ValueError("finished run has no start")
        valid = event["validity"]
        if valid not in ("valid", "invalid") or valid == "invalid" and not event["reason"]:
            raise ValueError("finished run needs a validity state and invalid reason")
        if not isinstance(event["processor_model"], str) or not event["processor_model"]:
            raise ValueError("processor model required")
        if not isinstance(event["counters"], dict) or any(
                v != "unavailable" and (not isinstance(v, (int, float)) or not math.isfinite(v))
                for v in event["counters"].values()):
            raise ValueError("counters must be numeric or explicitly unavailable")
        share = event["protocol_share"]
        if share != "unavailable" and not (isinstance(share, (int, float)) and 0 <= share <= 1):
            raise ValueError("protocol share must be a fraction or unavailable")
        if valid == "valid":
            paired_log(event["throughput"], event["throughput"])
        self.runs[key] = event
        if valid == "invalid":
            self.stopped = event
            return
        if event["order"] == "first":
            return
        cell, phase, attempt, _ = key
        first = self.runs[cell, phase, attempt, "first"]
        if first["processor_model"] != event["processor_model"]:
            self.excluded.add((cell, phase, attempt))
        else:
            sides = {run["side"]: run["throughput"] for run in (first, event)}
            self.values[cell, phase].append(paired_log(sides["develop"], sides["branch"]))
            if phase == "result":
                result = look(self.values[cell, phase])
                if result:
                    self.decisions[cell] = result["decision"]
        self.attempts[cell, phase] += 1


def replay(path, settings, checksums):
    """Read every line strictly. An empty file has no experiment yet."""
    state = State(settings, checksums)
    with open(path, encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            try:
                if not line.endswith("\n"):
                    raise ValueError("torn line")
                state.apply(json.loads(line))
            except (ValueError, KeyError, TypeError) as error:
                raise ValueError(f"record line {number}: {error}") from error
    return state
