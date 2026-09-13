"""Stage with an ephemeral TEST key, verify every artifact, reject a tampered copy.

Never publishes remotely. This validates mechanics, not ownership of a release key.
Requires gpg, JDK 21 and the repository's Gradle wrapper. Retains only public evidence.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from contextlib import contextmanager


def gpg_path(gpg, path):
    """Git for Windows GPG needs an MSYS path for its agent socket directory."""
    path = Path(path).resolve()
    if os.name == "nt" and Path(gpg).with_name("msys-2.0.dll").is_file():
        value = path.as_posix()
        if path.drive:
            return "/" + value[0].lower() + value[2:]
    return str(path)


@contextmanager
def test_key_home(gpg):
    with tempfile.TemporaryDirectory(prefix="kastor-sign-") as directory:
        try:
            yield directory
        finally:
            gpgconf = Path(gpg).with_name("gpgconf.exe" if os.name == "nt" else "gpgconf")
            if gpgconf.is_file():
                subprocess.run([str(gpgconf), "--homedir", gpg_path(gpg, directory), "--kill", "gpg-agent"],
                               capture_output=True, timeout=15)


def main():
    root = Path(__file__).resolve().parents[1]
    gpg = shutil.which("gpg")
    if gpg is None and os.name == "nt":
        candidate = Path("C:/Program Files/Git/usr/bin/gpg.exe")
        if candidate.exists():
            gpg = str(candidate)
    if gpg is None:
        raise SystemExit("gpg is required")
    report = root / "build/review"
    report.mkdir(parents=True, exist_ok=True)
    with test_key_home(gpg) as directory:
        base = [gpg, "--homedir", gpg_path(gpg, directory), "--batch", "--pinentry-mode", "loopback"]
        subprocess.run(base + ["--passphrase", "", "--quick-generate-key", "Kastor LOCAL TEST ONLY <test@example.invalid>", "rsa2048", "sign", "1d"], check=True, capture_output=True)
        key = subprocess.check_output(base + ["--armor", "--export-secret-keys"], text=True)
        public = subprocess.check_output(base + ["--armor", "--export"], text=True)
        (report / "test-signing-public.asc").write_text(public, encoding="ascii")
        env = os.environ.copy()
        env["KASTOR_SIGNING_KEY"] = key
        env["KASTOR_SIGNING_PASSWORD"] = ""
        wrapper = str(root / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        with (report / "local-signing.log").open("w", encoding="utf-8") as log:
            subprocess.run([wrapper, "publishAllPublicationsToStagingRepository", "--no-daemon", "--no-configuration-cache", "--no-parallel", "--max-workers=1",
                            "-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8",
                            "-Pkotlin.compiler.execution.strategy=in-process"], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        repository = root / "build/release-repository"
        artifacts = sorted(p for p in repository.rglob("*") if p.suffix in (".pom", ".jar", ".module"))
        if not artifacts:
            raise SystemExit("No staged artifacts")
        for artifact in artifacts:
            signature = Path(str(artifact) + ".asc")
            if not signature.is_file():
                raise SystemExit(f"Missing signature: {artifact.relative_to(root)}")
            subprocess.run(base + ["--verify", str(signature), str(artifact)], check=True, capture_output=True)
        tampered = Path(directory) / "tampered-artifact"
        tampered.write_bytes(artifacts[0].read_bytes() + b"tampered")
        negative = subprocess.run(base + ["--verify", str(artifacts[0]) + ".asc", str(tampered)], capture_output=True)
        if negative.returncode == 0:
            raise SystemExit("Tamper detection failed")
        (report / "signing-verification.json").write_text(json.dumps({"key_purpose": "ephemeral LOCAL TEST ONLY", "verified_artifacts": len(artifacts),
            "tampered_artifact_rejected": True, "artifacts": [str(p.relative_to(repository)) for p in artifacts]}, indent=2) + "\n", encoding="utf-8")
        print(f"Verified {len(artifacts)} local test signatures and rejection of tampered data")


if __name__ == "__main__":
    main()
