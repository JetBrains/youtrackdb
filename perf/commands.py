from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass
class CommandResult:
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0


def local_run(
    cmd: str, timeout: int = 300, cwd: Path | None = None, check: bool = True
) -> CommandResult:
    result = subprocess.run(
        cmd, shell=True, capture_output=True, text=True, timeout=timeout, cwd=cwd
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"Command failed: {cmd}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}")
    return CommandResult(
        returncode=result.returncode,
        stdout=result.stdout.strip(),
        stderr=result.stderr.strip(),
    )


class RemoteHost:
    def __init__(self, host: str, timeout: int = 30) -> None:
        self.host = host
        self.timeout = timeout

    def run(self, command: str, timeout: int | None = None, check: bool = True) -> CommandResult:
        result = subprocess.run(
            ["ssh", self.host, command],
            capture_output=True,
            text=True,
            timeout=timeout or self.timeout,
        )
        cmd_result = CommandResult(result.returncode, result.stdout, result.stderr)

        if check and not cmd_result.ok:
            raise RuntimeError(
                f"SSH command failed (exit {result.returncode}): ssh {self.host} {command}\n"
                f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
            )
        return cmd_result

    def upload(self, local: str | Path, remote: str) -> None:
        subprocess.run(["scp", str(local), f"{self.host}:{remote}"], check=True, capture_output=True)

    def download(self, remote: str, local: str | Path) -> None:
        Path(local).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["scp", f"{self.host}:{remote}", str(local)], check=True, capture_output=True)

    def is_reachable(self) -> bool:
        try:
            return self.run("echo ok", timeout=10, check=False).ok
        except subprocess.TimeoutExpired:
            return False
