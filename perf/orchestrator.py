#!/usr/bin/env python3
from __future__ import annotations

import argparse
import logging
import os
import shutil
import subprocess
import sys
import time
import yaml
from datetime import datetime
from pathlib import Path
from itertools import islice

from jinja2 import Environment, FileSystemLoader

from commands import RemoteHost, local_run
from config import Config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


class Orchestrator:
    def __init__(self, config: Config) -> None:
        self.config = config

        self.ytdb_repo = Path(config.get("paths", "ytdb_repo") or ".")
        self.driver_repo = Path(p) if (p := config.get("paths", "driver_repo")) else None
        self.loader_repo = Path(p) if (p := config.get("paths", "loader_repo")) else None

        ssh_alias = config.get("hosts", "db", "ssh_alias", default="bench-db")
        self.db_host = RemoteHost(ssh_alias, timeout=60)

        self.ytdb_sha = config.get("ytdb", "sha") or "unknown"
        self.driver_sha = config.get("driver", "sha") or "unknown"
        self.loader_sha = config.get("loader", "sha") or "unknown"

        self.driver_jar: Path | None = None
        self.docker_image = config.get("docker", "server_image", default="youtrackdb/youtrackdb-server")
        self.docker_tag = config.get("docker", "server_tag") or f"bench-{self.ytdb_sha[:7]}"
        self.container_name = config.get("docker", "container_name", default="ytdb-bench")
        self.loader_image = config.get("docker", "loader_image", default="youtrackdb/youtrackdb-ldbc-snb-loader")
        self.loader_tag = config.get("docker", "loader_tag") or f"bench-{self.loader_sha[:7]}"
        self.loader_container_name = config.get("docker", "loader_container_name", default="ytdb-loader")
        self.db_home_dir = config.get("paths", "db_home", default="/home/bench/ytdb")
        self.server_conf_path = self._resolve_server_conf_path()
        # Remote data root used by loader/db containers on the DB host.
        self.ldbc_snb_data_dir = (
                config.get("paths", "loader_data")
                or config.get("paths", "ldbc_snb_data")
                or "/data/ldbc-snb"
        )
        self.results_dir = self._create_results_dir()

    @property
    def ldbc_data_path(self) -> str:
        """
        Path to LDBC SNB dataset on the remote host, shared by loader and db containers.

        NB: The loader and database server containers are assumed to run on the same remote host (db_host)
        """
        scale_factor = self.config.get("ldbc", "scale_factor", default=0.1)
        return f"{self.ldbc_snb_data_dir}/sf{scale_factor}/initial_snapshot"

    @property
    def ldbc_data_backup_path(self) -> str:
        scale_factor = self.config.get("ldbc", "scale_factor", default=0.1)
        backup_path = f"{self.db_home_dir}/backups/sf{scale_factor}"
        self.db_host.run(f"mkdir -p {backup_path}")
        return backup_path

    @property
    def ldbc_data_backup_container_path(self) -> str:
        scale_factor = self.config.get("ldbc", "scale_factor", default=0.1)
        return f"/opt/ytdb-server/backups/sf{scale_factor}"

    def _create_results_dir(self) -> Path:
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        sha = self.ytdb_sha[:7]
        results_dir = Path("results") / f"{ts}_{sha}"
        results_dir.mkdir(parents=True, exist_ok=True)
        (results_dir / "config.yml").write_text(yaml.dump(self.config.to_safe_dict()))
        self._set_github_output("results_dir", str(results_dir))
        return results_dir

    def _resolve_server_conf_path(self) -> Path | None:
        conf_path = self.config.get("paths", "server_conf")
        if not conf_path:
            return None
        conf = Path(conf_path)
        if not conf.is_absolute():
            conf = (self.config.config_path.parent / conf).resolve()
        return conf

    def _set_github_output(self, name: str, value: str) -> None:
        if github_output := os.environ.get("GITHUB_OUTPUT"):
            with open(github_output, "a") as f:
                f.write(f"{name}={value}\n")

    def run(self) -> Path:
        try:
            self._build()
            self._preflight()
            self._deploy()
            self._start_db()
            self._load_data()  # first load also creates a backup
            self._run_validation()
            # Validation can mutate the SUT; restore a clean state for the benchmark phase.
            self._load_data()
            self._run_benchmark()
        finally:
            self._collect_logs()
            self._stop_db()

        return self.results_dir

    def _build(self) -> None:
        logger.info("Building YouTrackDB Docker image")
        self._build_docker_image()

        if not self.loader_repo:
            raise RuntimeError("Loader repo path not configured (set LOADER_REPO_PATH)")
        logger.info("Building LDBC SNB loader Docker image")
        self._build_loader_image()

        if not self.driver_repo:
            raise RuntimeError("Driver repo path not configured (set DRIVER_REPO_PATH)")
        logger.info("Building LDBC SNB driver")
        self._mvn_build(self.driver_repo)

    def _build_docker_image(self) -> None:
        if not (self.ytdb_repo / "pom.xml").exists():
            raise RuntimeError(f"pom.xml not found at {self.ytdb_repo}")

        cmd = "./mvnw package -P docker-images"
        cmd += " -Ddocker.platforms=linux/amd64"
        cmd += f" -Ddocker.tags.majorVersion.minorVersion={self.docker_tag}"
        cmd += f" -Ddocker.tags.latest={self.docker_tag}"
        cmd += " -DskipTests"
        cmd += " -q"

        local_run(cmd, timeout=900, cwd=self.ytdb_repo)
        logger.info(f"Built Docker image: {self.docker_image}:{self.docker_tag}")

    def _build_loader_image(self) -> None:
        if not self.loader_repo:
            raise RuntimeError("Loader repo path not configured (set LOADER_REPO_PATH)")
        if not (self.loader_repo / "Dockerfile").exists():
            raise RuntimeError(f"Dockerfile not found at {self.loader_repo}")

        image_tag = f"{self.loader_image}:{self.loader_tag}"
        local_run(
            f"docker build --platform linux/amd64 -t {image_tag} .",
            timeout=600,
            cwd=self.loader_repo
        )
        logger.info(f"Built loader image: {image_tag}")

    def _mvn_build(self, repo: Path) -> None:
        if not (repo / "pom.xml").exists():
            raise RuntimeError(f"pom.xml not found at {repo}")

        cmd = "./mvnw package"
        cmd += " -DskipTests"
        cmd += " -q"

        local_run(cmd, timeout=600, cwd=repo)

    def _preflight(self) -> None:
        if not self.db_host.is_reachable():
            raise RuntimeError(f"Cannot reach {self.db_host.host}")

        result = self.db_host.run("docker --version", check=False)
        if not result.ok:
            raise RuntimeError(f"Docker not available on {self.db_host.host}")

        result = local_run(
            f"docker image inspect {self.docker_image}:{self.docker_tag}",
            check=False, timeout=30
        )
        if result.returncode != 0:
            raise RuntimeError(f"Docker image {self.docker_image}:{self.docker_tag} not found locally")

        result = local_run(
            f"docker image inspect {self.loader_image}:{self.loader_tag}",
            check=False, timeout=30
        )
        if result.returncode != 0:
            raise RuntimeError(f"Loader image {self.loader_image}:{self.loader_tag} not found locally")

        if not self.driver_repo:
            raise RuntimeError("Driver repo path not configured (set DRIVER_REPO_PATH)")
        driver_target = self.driver_repo / "runner" / "target"
        runner_jars = [
            jar for jar in driver_target.glob("*.jar")
            if not jar.name.startswith("original-")
        ]
        if not runner_jars:
            raise RuntimeError(f"Driver runner JAR not found under {driver_target} (run mvn package)")
        self.driver_jar = runner_jars[0]
        logger.info(f"Found driver JAR: {self.driver_jar}")

    def _transfer_image(self, image_tag: str, name: str, timeout: int = 300) -> None:
        local_tarball = f"/tmp/{name}.tar"
        remote_tarball = f"/tmp/{name}.tar"

        logger.info(f"Exporting {name} Docker image")
        local_run(f"docker save {image_tag} -o {local_tarball}", timeout=timeout)

        logger.info(f"Uploading {name} Docker image")
        self.db_host.upload(local_tarball, remote_tarball)

        self.db_host.run(f"docker load -i {remote_tarball}", timeout=timeout)
        self.db_host.run(f"rm {remote_tarball}")

    def _deploy(self) -> None:
        logger.info(f"Deploying to {self.db_host.host}")
        for dir in ("conf", "databases", "log", "secrets", "memory-dumps"):
            self.db_host.run(f"rm -rf {self.db_home_dir}/{dir}/*", check=False)

        image_tag = f"{self.docker_image}:{self.docker_tag}"
        self._transfer_image(image_tag, "ytdb-server")

        for dir in ("conf", "databases", "log", "secrets", "memory-dumps", "backups"):
            self.db_host.run(f"mkdir -p {self.db_home_dir}/{dir}")
        self.db_host.run(f"mkdir -p {self.ldbc_data_backup_path}")
        self.db_host.run(f"chmod 777 {self.db_home_dir}/backups {self.ldbc_data_backup_path}", check=False)
        root_password = self.config.get("database", "root_password", default="root")
        self.db_host.run(f"echo '{root_password}' > {self.db_home_dir}/secrets/root_password")
        if self.server_conf_path:
            if not self.server_conf_path.exists():
                raise RuntimeError(f"Server config not found: {self.server_conf_path}")
            remote_conf = f"{self.db_home_dir}/conf/youtrackdb-server.yaml"
            self.db_host.upload(self.server_conf_path, remote_conf)
        self.db_host.run(f"docker rm -f {self.container_name} 2>/dev/null || true", check=False)

        loader_tag = f"{self.loader_image}:{self.loader_tag}"
        self._transfer_image(loader_tag, "ytdb-ldbc-loader", timeout=120)
        self.db_host.run(f"docker rm -f {self.loader_container_name} 2>/dev/null || true", check=False)

        logger.info(f"Deployed to {self.db_host.host}")

    def _start_db(self) -> None:
        logger.info("Starting database container")
        heap = self.config.get("database", "jvm_heap", default="8g")
        port = self.config.get("database", "port", default=8182)
        image_tag = f"{self.docker_image}:{self.docker_tag}"

        docker_run_cmd = f"""docker run -d \\
            --name {self.container_name} \\
            -p {port}:{port} \\
            -e YOUTRACKDB_OPTS_MEMORY="-Xms{heap} -Xmx{heap}" \\
            -v {self.db_home_dir}/secrets:/opt/ytdb-server/secrets \\
            -v {self.db_home_dir}/databases:/opt/ytdb-server/databases \\
            -v {self.db_home_dir}/conf:/opt/ytdb-server/conf \\
            -v {self.db_home_dir}/log:/opt/ytdb-server/log \\
            -v {self.db_home_dir}/memory-dumps:/opt/ytdb-server/memory-dumps \\
            -v {self.db_home_dir}/backups:/opt/ytdb-server/backups \\
            {image_tag}"""

        result = self.db_host.run(docker_run_cmd)
        container_id = result.stdout.strip()[:12]
        logger.info(f"Started container: {container_id}")
        self._wait_for_db()

    def _wait_for_db(self, timeout: int = 30) -> None:
        host = self.config.get("hosts", "db", "public_ip")
        port = self.config.get("database", "port", default=8182)
        start = time.time()
        while time.time() - start < timeout:
            # Use curl to check if Gremlin endpoint responds (any HTTP response means it's ready)
            result = local_run(
                f"curl -s -o /dev/null -w '%{{http_code}}' --max-time 5 http://{host}:{port}/gremlin",
                check=False, timeout=10
            )
            if result.ok and result.stdout.strip():
                logger.info(f"Database ready ({time.time() - start:.1f}s)")
                return
            time.sleep(2)
        raise TimeoutError(f"Database not ready after {timeout}s")

    def _load_data(self) -> None:
        if not self.loader_repo:
            raise RuntimeError("Loader not configured (set LOADER_REPO_PATH)")

        logger.info("Loading LDBC SNB data")
        loader_tag = f"{self.loader_image}:{self.loader_tag}"
        port = self.config.get("database", "port", default=8182)
        root_password = self.config.get("database", "root_password", default="root")
        db_name = self.config.get("database", "name", default="ldbc_snb")
        db_user = self.config.get("database", "user", default="admin")
        db_password = self.config.get("database", "password", default="admin")

        docker_run_cmd = f"""docker run --rm \\
            --name {self.loader_container_name} \\
            --network host \\
            -v {self.ldbc_data_path}:/data:ro \\
            -e YTDB_MODE=remote \\
            -e YTDB_SERVER_HOST=localhost \\
            -e YTDB_SERVER_PORT={port} \\
            -e YTDB_SERVER_USER=root \\
            -e YTDB_SERVER_PASSWORD={root_password} \\
            -e YTDB_DATABASE_NAME={db_name} \\
            -e YTDB_DATABASE_USER={db_user} \\
            -e YTDB_DATABASE_PASSWORD={db_password} \\
            -e YTDB_DATASET_PATH=/data \\
            -e YTDB_BACKUP_PATH={self.ldbc_data_backup_container_path} \\
            {loader_tag}"""

        loader_timeout = int(self.config.get("loader", "timeout", default=7200))
        start = time.time()
        result = self.db_host.run(docker_run_cmd, timeout=loader_timeout)
        elapsed = time.time() - start
        logger.info(f"Data loading completed ({elapsed:.1f}s)")

        (self.results_dir / "loader.stdout.log").write_text(result.stdout)
        (self.results_dir / "loader.stderr.log").write_text(result.stderr)


    def _get_driver_params(self) -> Path:
        scale_factor = self.config.get("ldbc", "scale_factor", default=0.1)
        update_partitions = self.config.get("ldbc", "update_partitions", default=1)
        sf_dir = f"sf{scale_factor}"
        numpart = f"numpart-{update_partitions}"

        driver_params_root = Path(self.config.get("paths", "driver_params", default="/data/ldbc-snb"))
        params_dir = driver_params_root / sf_dir
        subst_params_dir = params_dir / "substitution_parameters"
        updates_dir = params_dir / "update_streams" / numpart

        if not subst_params_dir.exists():
            raise RuntimeError(f"substitution_parameters not found at {subst_params_dir}")

        if not updates_dir.exists():
            raise RuntimeError(f"update_streams/{numpart} not found at {updates_dir}")

        return params_dir

    def _resolve_validation_params_file(self) -> Path:
        scale_factor = self.config.get("ldbc", "scale_factor", default=0.1)
        data_root = Path(
            self.config.get("paths", "driver_params")
            or self.ldbc_snb_data_dir
            or "/data/ldbc-snb"
        )

        params = data_root / f"sf{scale_factor}" / "validation_params" / f"validation_params-sf{scale_factor}.csv"
        if params.exists():
            return params

        raise RuntimeError(
            f"Validation params not found for sf{scale_factor}. "
            f"Expected under {data_root / f'sf{scale_factor}'}."
        )

    def _slice_validation_params(self, src: Path, dst: Path, limit: int) -> None:
        dst.parent.mkdir(parents=True, exist_ok=True)
        with open(src, "r", encoding="utf-8", errors="replace") as original:
            with open(dst, "w", encoding="utf-8") as sliced:
                for line in islice(original, limit):
                    sliced.write(line)

    def _prepare_validation_params_subset(self, validation_dir: Path) -> Path:
        limit = int(self.config.get("ldbc", "validation_params_limit", default=200))
        if limit <= 0:
            raise RuntimeError(f"Invalid ldbc.validation_params_limit: {limit}")

        src = self._resolve_validation_params_file()
        sf = self.config.get("ldbc", "scale_factor", default=0.1)

        # Always copy/slice into results so validation failure artifacts land in the uploaded artifact.
        if src.name == f"validation_params-sf{sf}.csv":
            dst = validation_dir / f"validation_params-sf{sf}-{limit}.csv"
            self._slice_validation_params(src, dst, limit)
            return dst.resolve()

        dst = validation_dir / src.name
        shutil.copy2(src, dst)
        return dst.resolve()

    def _run_driver(self, *, mode: str, output_dir: Path,
                    validation_params_file: Path | None = None,
                    force_thread_count: int | None = None) -> None:
        if not self.driver_repo or not self.driver_jar:
            raise RuntimeError(
                "Driver not configured (set DRIVER_REPO_PATH and build runner JAR)")

        output_dir.mkdir(exist_ok=True)

        params_dir = self._get_driver_params()
        props_file = self._generate_driver_properties(
            params_dir=params_dir,
            driver_results_dir=output_dir,
            mode=mode,
            validation_params_file=validation_params_file,
            force_thread_count=force_thread_count,
        )

        logger.info(f"Running LDBC driver ({mode})")

        java_cmd = [
            "java",
            "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
            # Required for LDBC driver's SBE library
            "-jar", str(self.driver_jar),
            "-P", str(props_file.absolute()),
        ]

        driver_timeout = int(self.config.get("driver", "timeout", default=3600))
        start_time = time.time()
        result = subprocess.run(
            java_cmd,
            capture_output=True,
            text=True,
            timeout=driver_timeout,
            cwd=self.driver_repo,
        )
        elapsed = time.time() - start_time

        (output_dir / "stdout.log").write_text(result.stdout)
        (output_dir / "stderr.log").write_text(result.stderr)

        if result.returncode != 0:
            logger.error(f"LDBC driver failed with code {result.returncode}")
            logger.error(f"stdout: {result.stdout[:1000]}")
            logger.error(f"stderr: {result.stderr[:1000]}")
            raise RuntimeError(f"LDBC driver failed with code {result.returncode}")

        logger.info(f"LDBC driver completed ({mode}, {elapsed:.1f}s)")

    def _run_validation(self) -> None:
        validation_dir = self.results_dir / "validation_output"
        validation_dir.mkdir(exist_ok=True)

        dst = self._prepare_validation_params_subset(validation_dir)

        self._run_driver(
            mode="validate_database",
            output_dir=validation_dir,
            validation_params_file=dst,
            force_thread_count=1,
        )

        combined = (
                (validation_dir / "stdout.log").read_text(errors="ignore")
                + "\n"
                + (validation_dir / "stderr.log").read_text(errors="ignore")
        )
        if ("Validation Result: PASS" in combined) or ("Database Validation Successful" in combined):
            logger.info("Validation PASSED")
            return
        if "Validation Result: FAIL" in combined:
            logger.error("Validation FAILED")
            raise RuntimeError("LDBC validation failed (see validation_output logs)")

        raise RuntimeError(
            "LDBC validation did not report PASS (see validation_output logs)"
        )

    def _generate_driver_properties(self, params_dir: Path,
                                    driver_results_dir: Path,
                                    mode: str,
                                    validation_params_file: Path | None = None,
                                    force_thread_count: int | None = None) -> Path:
        logger.info(f"Generating driver properties file ({mode})")

        scale_factor = self.config.get("ldbc", "scale_factor", default=0.1)
        thread_count = (
            force_thread_count
            if force_thread_count is not None
            else self.config.get("ldbc", "thread_count", default=1)
        )
        update_partitions = self.config.get("ldbc", "update_partitions", default=1)
        time_compression_ratio = float(self.config.get("ldbc", "time_compression_ratio", default=0.1))
        ignore_scheduled_start_times = self.config.get("ldbc", "ignore_scheduled_start_times", default=True)

        template_dir = Path(__file__).parent
        env = Environment(loader=FileSystemLoader(template_dir), trim_blocks=True,
                          lstrip_blocks=True)
        template = env.get_template("ldbc-driver.properties.j2")

        properties = template.render(
            mode=mode,
            scale_factor=scale_factor,
            validation_file=str(validation_params_file) if validation_params_file else "",
            thread_count=thread_count,
            operation_count=self.config.get("ldbc", "operation_count", default=1000),
            warmup=self.config.get("ldbc", "warmup_count", default=0),
            params_path=params_dir / "substitution_parameters",
            updates_path=params_dir / "update_streams" / f"numpart-{update_partitions}",
            db_host=self.config.get("hosts", "db", "public_ip", default="localhost"),
            db_port=self.config.get("database", "port", default=8182),
            db_name=self.config.get("database", "name", default="ldbc_snb"),
            db_user=self.config.get("database", "user", default="admin"),
            db_password=self.config.get("database", "password", default="admin"),
            results_dir=driver_results_dir.absolute(),
            time_compression_ratio=time_compression_ratio,
            ignore_scheduled_start_times=ignore_scheduled_start_times,
        )

        props_file = driver_results_dir / f"ldbc-driver-{mode}.properties"
        props_file.write_text(properties)
        logger.info(f"Driver properties written to {props_file}")
        return props_file

    def _run_benchmark(self) -> None:
        driver_results_dir = self.results_dir / "driver_output"
        self._run_driver(mode="execute_benchmark", output_dir=driver_results_dir)

    def _collect_logs(self) -> None:
        logs_dir = self.results_dir / "db_logs"
        logs_dir.mkdir(exist_ok=True)

        try:
            result = self.db_host.run(f"docker logs {self.container_name} 2>&1", check=False)
            (logs_dir / "container.log").write_text(result.stdout)

            remote_log_dir = f"{self.db_home_dir}/log"
            result = self.db_host.run(f"ls {remote_log_dir}/*.log* 2>/dev/null || true", check=False)
            for log_file in result.stdout.strip().split("\n"):
                if log_file and not log_file.endswith(".lck"):
                    self.db_host.download(log_file, logs_dir / Path(log_file).name)

            dump_result = self.db_host.run(
                f"ls {self.db_home_dir}/memory-dumps/*.hprof 2>/dev/null || true",
                check=False
            )
            for dump_file in dump_result.stdout.strip().split("\n"):
                if dump_file:
                    self.db_host.download(dump_file, logs_dir / Path(dump_file).name)
        except Exception as e:
            logger.warning(f"Failed to collect logs: {e}")

    def _stop_db(self) -> None:
        logger.info("Stopping database container")
        self.db_host.run(f"docker stop {self.container_name} 2>/dev/null || true", check=False, timeout=30)
        self.db_host.run(f"docker rm {self.container_name} 2>/dev/null || true", check=False)


def main() -> None:
    parser = argparse.ArgumentParser(description="YTDB Benchmark Orchestrator")
    parser.add_argument("config", type=Path)
    args = parser.parse_args()

    config = Config(args.config)
    logger.info(f"Benchmark: {config.get('benchmark', 'name', default=args.config.stem)}")

    try:
        results = Orchestrator(config).run()
        logger.info(f"Done: {results}")
    except Exception as e:
        logger.error(f"Failed: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
